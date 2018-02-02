(ns lemonade.coordinates
  (:require [lemonade.core :as core]
            [lemonade.math :as math]))

(defn get-coord-inversion [height]
  (math/atx [1 0 0 -1] [0 height]))

(defn invert-coordinates [shape height]
  (with-meta
    (core/transform shape (get-coord-inversion height))
    {:atx-type ::flip}))

(defn wrap-invert-coordinates [render]
  (fn [state]
    (invert-coordinates (render state) (-> state ::core/window :height))))
