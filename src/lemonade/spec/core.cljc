(ns lemonade.spec.core
  (:require [clojure.spec.alpha :as s]
            [lemonade.math :as geo]
            [lemonade.spec.gen :as gen]
            [lemonade.spec.math :as math]
            [lemonade.spec.style :as style]))

;;; Paths

(s/def ::line
  (s/keys :req-un [::math/from ::math/to]  :opt-un [::style/style]))

(s/def ::bezier
   (s/keys :req-un [::math/from ::math/to ::math/c1 ::math/c2]
           :opt-un [::style/style]))

(s/def ::arc
  (s/keys :req-un [::math/centre ::math/radius :lemonade.math.angle/to
                   :lemonade.math.angle/from]
          :opt-un [::style/style]))

(defmulti path-segment :type)
(defmethod path-segment ::line [_] ::line)
(defmethod path-segment ::bezier [_] ::bezier)
(defmethod path-segment ::arc [_] ::arc)
(s/def ::path-segment (s/multi-spec path-segment :type))

(s/def ::segments
  (s/with-gen
    (s/and (s/coll-of ::path-segment :kind sequential?)
           geo/connected?)
    gen/segment-gen))

(s/def ::path
  (s/or :single-segment  ::path-segment
        :joined-segments (s/keys :req-un [::segments] :opt-un [::style/style])))

;;; Shapes in General
;;
;; Templates? Macros? What the hell are these going to be exactly?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::shape-template any? #_(s/multi-spec shape-template :type))

(s/def ::primitive-shape
  (s/or :path     ::path
        :template ::shape-template))

(s/def ::shape
  (s/or :primitive   ::primitive-shape
        :composite   ::composite
        :transformed ::affine-transform))

(s/def ::shapes
  (s/coll-of ::shape :kind sequential?))

(s/def ::composite
  (s/keys :req-un [::shapes] :opt-un [::style/style]))

;;;;; Affine Transforms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::base-shape ::shape)

(s/def ::affine-transform
  (s/keys :req-un [::math/atx ::base-shape]))
