(ns ubik.interactive.rt
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [net.cgrand.macrovich :as macros :include-macros true]
            [ubik.interactive.base :as base]
            [ubik.core :as core]
            [ubik.interactive.events :as events]
            [ubik.hosts :as hosts]
            [ubik.interactive.subs :as subs :include-macros true]
            [ubik.interactive.process :as process]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Signal Graph Analysis
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn source?
  "Returns true iff p is an event source.

  Currently that just means it's a keyword, but that will probably change."
  [p]
  (keyword? p))

(defn process?
  "Returns true if x is a multiplexer process or a var that points to one."
  [x]
  (let [x (if (var? x) @x x)]
    (satisfies? process/Multiplexer x)))

(defn walk-signal-graph
  "Walks backwards along inputs through signal graph from current and returns a
  reversed representation where each entry in the returned map corresponds to
  the processes which listen to that process."
  [current]
  (when-not (source? current)
    (let [co     (if (var? current) @current current)
          inputs (base/inputs co)
          pm     (into {} (map (fn [i] [i #{current}])) inputs)]
      (apply merge-with set/union pm
             (map walk-signal-graph inputs)))))

(defn root-fibres
  "Returns the subset of the given forward signal graph where the only keys
  remaining are sources, that is processes which never receive input from the
  graph."
  [pm]
  (let [valset (into #{} cat (vals pm))]
    (apply dissoc pm valset)))

(defn fibres
  "Returns a tree of forward process links in the signal graph.

  Prunes the tree any time it encounters a lazy subscription. This is
  intentional since any process that listens to a lazy sub will never get events
  and thus be inherently broken."
  ;; FIXME: There is currently no checking that the graph doesn't contain
  ;; cycles. If it does, this will loop forever. Signal graphs should in general
  ;; contain cycles, so this will have to be overhauled with a depth first
  ;; search algo.
  [pm]
  (let [roots (root-fibres pm)]
    (walk/prewalk (fn [x]
                    (if (set? x)
                      (into {} (map (fn [x]
                                      (when (process? x)
                                        [x (get pm x)]))) x)
                      x))
                  roots)))

(defn debranch
  "Scans tree and collapes nodes with a single edge into vectors of nodes. This
  tells up where in the graph we can directly compose operations rather than
  sending messages."
  [tree]
  (into {} (map (fn [[k v]]
                  (loop [run [k]
                         sub v]
                    (if (= 1 (count sub))
                      (let [[k v] (first sub)]
                        (recur (conj run k) v))
                      [run (debranch sub)]))))
        tree))

(defn build-transduction-pipeline
  "Returns a collection of processes extracted from the given collapsed tree."
  ([tree]
   (build-transduction-pipeline ::source tree))
  ([source tree]
   (into [] (mapcat (fn [[pipe subtree]]
                      (let [res (last pipe)]
                        (into [{:in source :xform pipe :out res}]
                              (build-transduction-pipeline res subtree)))))
         tree)))

(defn- correct-source-pipe
  "Cleans up the erronous ::source inputs that signal a source process. "
  ;; FIXME: This is pretty kludginous
  [{:keys [in out xform] :as p}]
  (if (= ::source in)
    {:in (first xform) :xform (rest xform) :out out}
    p))

(defn system-parameters
  "Analyses signal graph from root and returns the set of expected inputs to the
  resulting system as well as the set of processes that will need to be
  created and connected to initialise the system."
  [root]
  (let [pipelines (-> root
                      walk-signal-graph
                      fibres
                      debranch
                      build-transduction-pipeline)]
    {:event-sources (into #{} (comp (filter (fn [x] (= ::source (:in x))))
                                    (map :xform)
                                    (map first))
                          pipelines)
     :event-pipes (into #{} (comp (map correct-source-pipe)
                                  (remove (comp empty? :xform)))
                        pipelines)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;; Runtime logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-method-chain
  "Returns a vector of functions corresponding to the stages of process."
  [{:keys [in xform]}]
  (loop [s in
         [x & xs] xform
         ms []]
    (if x
      (let [dx (if (var? x) @x x)]
        (recur x xs (conj ms (process/method dx s))))
      ms)))

(defn- shunt-rf
  "Reducing function that discards the accumulated value and just collects
  further arguments.

  This is something of a kludge to implement the semantics of foldp, or if you
  prefer, scan over time."
  ([]
   {::shunted true})
  ([db]
   (if (::shunted db)
     db
     {::shunted true}))
  ([db ev]
   (if (::shunted db)
     (update db ::events conj ev)
     {::shunted true ::events [ev]})))

(defn go-machine
  "Creates a go-loop which reads messages off of input, transduces them
  according to process and distributes the resulting events (if any) to all
  listeners."
  [process input listeners]
  (let [xform (apply comp (get-method-chain process))]
    (async/go-loop []
      (when-let [events (async/<! input)]
        (try
          ;; TODO: Logging
          #_(println "Processing event " events " on " process
                   "\n"
                   "Sending to " (count listeners) " subscribers")
          (let [events (::events (transduce xform shunt-rf events))]
            (when (seq events)
              (run! (fn [ch]
                      (async/put! ch events))
                    listeners)))
          (catch #?(:clj Exception :cljs js/Error) e
            (println "Error in signal process " process ": " (.-stack e))))
        (recur)))))

(defn initialise-processes
  "Initialise a go machine for each process which connects the signal
  transduction with its inputs and outputs. Returns a map from processes to
  their input channels"
  [pipes]
  (let [pipes (map #(assoc % ::ch (async/chan 100)) pipes)
        ch-map (apply merge-with concat
                      (map (fn [k v] {k [v]}) (map :in pipes) (map ::ch pipes)))]
    (run! (fn [p] (go-machine p (::ch p) (get ch-map (:in p)))) pipes)
    ch-map))
