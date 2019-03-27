(ns ubik.topology
  (:require [clojure.pprint :refer [pprint]]
            [taoensso.timbre :as log]
            [ubik.codebase :as codebase]
            [ubik.process :as process]))

(defn set-topology! [t])
(defn current-topology [])

(def
  ^{:doc "Maps node names to actual runtime objects."}
  node-map
  (atom {::image codebase/image-signal}))

(def
  ^{:doc "Maps each node to the set of nodes to which it listens."}
  connectome
  (atom {}))

(defn destroy! []
  (reset! node-map {::image codebase/image-signal})
  (reset! connectome {}))

(defn init-topology! [{:keys [nodes wires]}]
  (doseq [node nodes]
    (let [name (:name node)]
      (if (contains? @node-map name)
        (log/warn "Trying to readd" name "to node graph.")
        (swap! node-map assoc name node))))
  (log/debug "Current node list:"
             (with-out-str (pprint (keys @node-map))))
  (doseq [[out in] wires]
    (let [in-node (get @node-map in)
          out (if (map? out) out {:in out})]
      (doseq [[wire sig] out]
        (let [out-node (get @node-map sig)]
          (log/debug "Connecting:" sig wire in)
          (process/wire in-node wire out-node)
          (swap! connectome update in assoc wire out))))))
