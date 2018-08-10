(ns ubik.interactive.rt
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [net.cgrand.macrovich :as macros :include-macros true]
            [ubik.interactive.base :as base]
            [ubik.core :as core]
            [ubik.interactive.db :as db]
            [ubik.interactive.events :as events]
            [ubik.hosts :as hosts]
            [ubik.interactive.subs :as subs :include-macros true]
            [ubik.interactive.process :as process]))

(defn merge-graphs [gs]
  (reduce (fn [acc g]
            (-> acc
                (update :all-procs set/union (:all-procs g))
                (update :push-map (partial merge-with set/union) (:push-map g))))
          {} gs))

(defn walk-signal-graph
  ([c]
   (walk-signal-graph #{} {} c))
  ([all push-map current]
   (if (keyword? current)
     {:push-map push-map :all-procs (conj all current)}
     (let [co     (if (var? current) @current current)
           inputs (base/inputs co)
           pm     (reduce (fn [push-map i]
                            (if (contains? push-map i)
                              (update push-map i conj current)
                              (assoc push-map i #{current})))
                          push-map inputs)]
       (merge-graphs (map #(walk-signal-graph (conj all current) pm %) inputs))))))

(defn external-events [w]
  (into #{} (filter keyword? (:all-procs (walk-signal-graph w)))))

(defn internal-events [w]
  (:push-map (walk-signal-graph w)))

(defn rooted-fibres [pm]
  (let [valset (into #{} cat (vals pm))]
    (apply dissoc pm valset)))

(defn linearise-fibres [fibres]
  (into [] (comp (map (fn [[k v]] (map (fn [v] [k v]) v))) cat) fibres))

(defn expand-set [s pm]
  (into {} (map (fn [x] [x (get pm x)])) s))

(defn fibres [pm]
  (let [roots (rooted-fibres pm)]
    (walk/prewalk (fn [x]
                    (if (set? x)
                      (expand-set x pm)
                      x))
                  roots)))

(defn debranch [tree]
  (into {} (map (fn [[k v]]
                  (loop [run [k]
                         sub v]
                    (if (= 1 (count sub))
                      (let [[k v] (first sub)]
                        (recur (conj run k) v))
                      [run (debranch sub)]))))
        tree))

(defn build-transduction-pipeline [source tree]
  (into [] (mapcat (fn [[pipe subtree]]
                     (let [res (last pipe)]
                       (into [{:in source :xform pipe :out res}]
                             (build-transduction-pipeline res subtree)))))
        tree))

(defn correct-source-pipe [{:keys [in out xform] :as p}]
  (if (= ::source in)
    {:in (first xform) :xform (rest xform) :out out}
    p))

(defn system-parameters [root]
  (let [{:keys [push-map all-procs]} (walk-signal-graph root)
        pipelines (build-transduction-pipeline ::source
                                               (debranch (fibres push-map)))]
    {:processes all-procs
     :event-sources (into #{} (comp (filter (fn [x] (= ::source (:in x))))
                                    (map :xform)
                                    (map first))
                          pipelines)
     :event-pipes (into #{} (comp (map correct-source-pipe)
                                  (remove (comp empty? :xform)))
                        pipelines)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Event Queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn error [m]
  (throw (#?(:clj Exception. :cljs js/Error) m)))

(defprotocol IEventQueue
  (enqueue [this ev]))

(defrecord EventQueue [chan buf-count buf-size]
   IEventQueue
   (enqueue [this ev]
     (swap! buf-count inc)
     (if (<= buf-size buf-count)
       (error "Too many backlogged events. Cannot recover. Aborting.")
       (async/put! chan ev))
     this))

(defn set-if-nil [ms k v]
  (map (fn [m]
         (if (contains? m k)
           m
           (assoc m k v)))
       ms))

(defn shunt-rf
  ([db]
   (if (::shunted db)
     db
     {::shunted true}))
  ([db ev]
   (if (::shunted db)
     (update db ::events conj ev)
     {::shunted true ::events [ev]})))

(defn transduce-1 [xform ev]
  (let [f (xform shunt-rf)]
    (f (f ev))))

(defn run-queue [handlers [etype event]]
  (let [relevant (get handlers etype)]
    (loop [evs []
           hs  relevant]
      (if (seq hs)
        (let [h       (first hs)
              handler (if (var? h) @h h)
              res     (transduce-1 (process/method handler etype) event)
              evs     (into evs (map (fn [ev] [handler ev]) (::events res)))]
          (recur  evs (rest hs)))
        evs))))

#_(defn handle-effects [effects event]
  (run! #(% event) (get effects (:type event))))

(defn create-queue []
  (let [chan (async/chan 1000)
        queue (EventQueue. chan (atom 0) 1000)]
    queue))

(defn start-queue-loop-process! [queue handlers]
  (async/go-loop []
    (when-let [ev (async/<! (:chan queue))]
      (try
        (swap! (:buf-count queue) dec)
        (let [evs (run-queue handlers ev)]
          (reduce enqueue queue evs))
        #_(handle-effects effects ev)
        (catch #?(:clj Exception :cljs js/Error) e
          (println "We have a problem: " e)))
      (recur))))
