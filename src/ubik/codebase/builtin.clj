(ns ubik.codebase.builtin
  (:require [falloleen.jfx :as fx]
            [ubik.codebase.storage :as store]
            [ubik.codebase.config :as config]
            [ubik.events :as events]
            [ubik.process :as process]
            [ubik.topology :as topo])
  (:import javafx.scene.control.TextArea))

(def stages (atom {}))

(defn create-code-stage [k]
  (if (contains? @stages k)
    (@stages k)
    (let [p @(fx/code-stage)
          ev-map (events/bind-text-area! k (:area p))
          res {:node (:area p) :stage (:stage p) :event-streams ev-map}]
      (swap! stages assoc k res)
      res)))

;; FIXME: This jfx specific code does not belong in Ubik. But it also doesn't
;; belong in Falloleen. It's a kludge anyway so why worry about factoring?
(defn text-renderer [^TextArea node]
  (fn [text]
    (fx/fx-thread
     (let [caret (.getCaretPosition node)]
       (.setText node text)
       (.positionCaret node caret)))))

(defn topo-effector [t]
  (topo/init-topology! t))

(def make-node process/make-node)
(def signal process/signal)
(def process process/process)
(def effector process/effector)

(defn source-effector [sym]
  (fn [form]
    (println form)))

(defn edit
  "Returns snippet in easily editable form by id."
  [id]
  (let [{:keys [form links]} (store/lookup config/*store* id)]
    `(snippet ~links
       ~form)))
