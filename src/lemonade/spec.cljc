(ns lemonade.spec
  #?(:cljs (:require-macros [lemonade.spec :refer [derive-spec]]))
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators]
            [clojure.pprint :refer [pprint pp]]))

;;;;; Helpers

(defn pe [spec]
  (->> spec
       s/exercise
       (map first)
       pprint))

(defmacro derive-spec
  "Shorthand for repeated derivations"
  {:style/indent 1}
  [spec & keys]
  `(do
     ~@(for [key keys]
         (list 's/def key spec))))

;;;;; Styles

(s/def ::style
  (s/keys :req-un []))

;;;;; Geometry

;;; Scalars

(defn nan? [x]
  #?(:clj (Double/isNaN x)
     :cljs (js/isNaN x)))

(defn finite? [x]
  (and
   (not= x ##Inf)
   (not= x ##-Inf)))

;; Real numbers
(s/def ::real
  (s/and number?
         finite?
         (comp not nan?)))

(s/def ::scalar ::real)
(s/def ::non-negative (s/and ::scalar (comp not neg?)))

(derive-spec ::non-negative
  ::radius
  ::width
  ::length
  ::height)

(s/def ::units keyword?)
(s/def ::amount ::scalar)

(s/def ::scalar-with-units
  (s/keys :req-un [::units ::amount]))

;;; Points 0d

(s/def ::point
  (s/coll-of ::scalar :kind sequential? :count 2))

(derive-spec ::point
  ::from
  ::to
  ::centre
  ::c1
  ::c2)

;;; Paths 1d

(s/def ::type keyword?)

(s/def ::typed
  (s/keys :req-un [::type]))

(s/def ::line
  (s/keys :req-un [::from ::to]  :opt-un [::style]) )

(s/def ::bezier
   (s/keys :req-un [::from ::to ::c1 ::c2] :opt-un [::style]))

(defmulti path-segment :type)
(defmethod path-segment ::line [_] ::line)
(defmethod path-segment ::bezier [_] ::bezier)
(s/def ::path-segment (s/multi-spec path-segment :type))

(defn connected?
  "Returns true if the sequential of paths passed in are pairwise connected."
  ;; TODO: spec
  [[x & more]]
  (if (empty? more)
    true
    (let [y (first more)]
      (and (= (:to x) (:from y)) (recur more)))))

(defn closed?
  "Returns true if paths form the boundary of a connected surface.
  Technically I'm requiring a connecting bridge of non-zero measure. Not sure if
  that's a good call...'"
  [paths]
  ;; TODO: spec.
  (and (connected? paths) (= (:from (first paths)) (:to (last paths)))))

(s/def ::segments
  (s/and (s/coll-of ::path-segment :kind sequential? :min-count 2) connected?))

(s/def ::path
  (s/or :single-segment  ::path-segment
        :joined-segments (s/keys :req-un [::segments] :opt-un [::style])))

;;; Surfaces 2d

;; REVIEW: How to distinguish the circle from the disc? A circle is technically
;; a 1d object, a path. A circle's interior is a disc which is a surface. Is
;; this pedantic or important?
(s/def ::circle
  (s/keys :req-un [::centre ::radius] :opt-un [::style]))

(defmulti builtin-shape :type)
(defmethod builtin-shape ::circle [_] ::circle)

(s/def ::builtin-shape (s/multi-spec builtin-shape :type))

(s/def ::primitive-shape
  (s/or :path    ::path
        :builtin ::builtin-shape))

(s/def ::shape
  (s/or :primitive   ::primitive-shape
        :composite   ::composite
        :transformed ::affine-transform))

(s/def ::shapes
  (s/coll-of ::shape :kind sequential?))

(s/def ::composite
  (s/keys :req-un [::shapes] :opt-un [::style]))

;;; No 3d yet

;;;;; Affine Transforms

;; REVIEW: This is idential to ::point above. They're semantically different, so
;; I've separated them. Is that necessary? Warranted?
(s/def ::vector (s/coll-of ::scalar :kind sequential? :count 2))

;; (a b c d), canvas takes them as (a c b d) because of silliness.
(s/def ::matrix (s/coll-of ::scalar :kind sequential? :count 4))

(s/def ::translation ::vector)

(s/def ::affine-transformation
  (s/keys :req-un [::matrix ::translation]))

(s/def ::composed-atx
  (s/coll-of ::atx :kind sequential?))

(s/def ::atx
  (s/or :composition ::composed-atx
        :single (s/keys :req-un [::matrix ::translation])))

(s/def ::base-shape ::shape)

(s/def ::affine-transform
  (s/keys :req-un [::atx ::base-shape]))

;;;;; Geometry

(s/def ::angle
  (s/or :unitless ::scalar
        :with-unit ::scalar-with-units))


;;;;; fns from elsewhere
;; REVIEW: where do they go?

(s/fdef lemonade.core/parse-angle
        :args (s/cat :angle ::angle)
        :ret ::real)

(defn nest-count [a]
  (if (or (empty? a) (map? a))
    0
    (inc (apply max (map nest-count a)))))
