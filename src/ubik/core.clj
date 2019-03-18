(ns ubik.core
  (:require [falloleen.jfx :as fx]
            [ubik.codebase :as codebase]
            [ubik.events :as events]
            [ubik.rt :as rt]
            [ubik.topology :as topo])
  (:import javafx.scene.control.TextArea))

(def stages (atom {}))

(defn create-code-stage [branch sym]
  (let [k (str branch "-" sym)]
    (if (contains? @stages k)
      (@stages k)
      (let [p @(fx/code-stage)
            ev-map (events/bind-text-area! (:area p))
            res {:node (:area p) :stage (:stage p) :event-streams ev-map}]
        (swap! stages assoc k res)
        res))))

(defn text-renderer [^TextArea node]
  (fn [text]
    (fx/fx-thread
     (let [caret (.getCaretPosition node)]
       (.setText node text)
       (.positionCaret node caret)))))

(def image-signal codebase/image-signal)

(def source-effector codebase/source-effector)

(def make-node topo/make-node)
