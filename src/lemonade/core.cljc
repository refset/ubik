(ns lemonade.core
  #?(:cljs (:require-macros [lemonade.core :refer [deftemplate]]))
  (:require [clojure.pprint :refer [pprint pp]]
            [lemonade.geometry :as geometry :refer [atx cos idm pi sin]]))

(defmulti template-expand :type)

#?(:clj
   (defn namespace-qualified-kw [sym]
     (if (namespace sym)
       (keyword sym)
       (let [current-ns (namespace `x)]
         (keyword current-ns (name sym))))))

#?(:clj
   (defn resolve-name [n]
     (cond
       (keyword? n) n

       (and (sequential? n)
            (= 'quote (first n))) (second n)

       :else (throw (Exception. "inapprorpriate template name")))))

#?(:clj
   (defmacro deftemplate
     "Defines a new shape template. Something like a macro"
     [template-name template expansion]
     (let [template-name (resolve-name template-name)]
       (if-not (namespace template-name)
         (throw (Exception. "Template names must be namespace qualified"))
         `(do
            (def ~(symbol (name template-name))
              ~(assoc template :type (keyword template-name)))

            (defmethod template-expand ~(keyword template-name)
              [{:keys [~@(map (comp symbol name) (keys (dissoc template :type)))]}]
              ~expansion))))))

(defn template-expand-all [shape]
  (if (contains? (methods template-expand) (:type shape))
    (recur (template-expand shape))
    shape))

(defn classify
  "Shape classifier. Returns a keyword type for any valid shape. Returns nil for
  invalid shapes. Intended for use in multimethods."
  [shape]
  (if-let [type (:type shape)]
    (if (contains? (methods template-expand) type)
      ::template
      type)
    (when (sequential? shape)
      ::sequential)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Core Geometry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def line
  {:type ::line
   :from [0 0]
   :to [1 1]})

(def bezier
  "Bezier cubic to be precise."
  {:type ::bezier
   :from [0 0]
   :c1 [0 0]
   :c2 [1 1]
   :to [1 1]})

(def arc
  {:type   ::arc
   :centre [0 0]
   :radius 1
   :from   0
   :to     (* 2 pi)
   :clockwise? false})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Higher Order Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn path
  ([segments] (path {} segments))
  ([style segments]
   (let [closed? (or (:closed (meta segments))
                     (geometry/closed? segments))]
     {:type ::path
      :closed? closed?
      :style style
      :contents segments})))

(defn conj-path [{:keys [style contents]} segment]
  ;; TODO: Assert that the composition is a path.
  (path style (conj contents segment)))

(defn composite
  ([shapes] (composite {} shapes))
  ([style shapes]
   {:type ::composite
    :style style
    :contents shapes}))

;; TODO: Create a group helper and stop allowing vectors of shapes.
;;
;; TODO: Make sure nothing assumes :contents is a vector. It's important that it
;; be allowed to be any sequential.

(defn with-style [style & shapes]
  (composite style shapes))

(defn style [shape style]
  (with-style style shape))

(defn radians [r]
  (/ (* r 180) geometry/pi))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Shape Templates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn full-arc [c r & [cw?]]
  (assoc arc
         :centre c
         :radius r
         :from   0
         :to     (* 2 pi)
         :clockwise? cw?))

(deftemplate ::circle
  {:style {} :radius 1 :centre [0 0]}
  (path style ^:closed [(full-arc centre radius)]))

(deftemplate ::annulus
  {:style {} :inner-radius 1 :outer-radius 2 :centre [0 0]}
  (path style
        ^:closed [(full-arc centre inner-radius)
                  (with-meta (full-arc centre outer-radius true)
                    ;; REVIEW: Abstraction leakage.
                    ;;
                    ;; We need this annotation to tell the path system to call
                    ;; moveTo in this one instance.
                    ;;
                    ;; TODO: This can probably be handled by keeping track of
                    ;; the point and jumping when in a path and from(n) !=
                    ;; to(n-1)
                    ;; Maybe keep a shared atom in the path state passed to
                    ;; segments? Uck, but could work.
                    {:jump true})]))

(deftemplate ::polyline
  {:style {} :points []}
  (let [segs (map (fn [[x y]]
                      {:type ::line
                       :from x
                       :to   y})
                   (partition 2 (interleave points (rest points))))]
    (path style (with-meta segs (if (= (first points) (last points))
                                  {:closed true}
                                  {})))))

(deftemplate ::rectangle
  {:style  {}
   :corner [0 0]
   :height 1
   :width  1}
  (let [[x1 y1] corner
        x2      (+ x1 width)
        y2      (+ y1 height)]
    {:type ::polyline
     :style style
     :points [[x1 y1] [x2 y1] [x2 y2] [x1 y2] [x1 y1]]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Affine Transforms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn translation
  "Returns an affine transformation corresponding to translation by b"
  [b]
  (atx idm b))

(defn rotation
  "Returns a counterclockwise rotation about the origin by angle (linear).
  Note: angle in degrees."
  [angle]
  (let [r (geometry/deg->rad angle)
        c (cos r)
        s (sin r)]
    (atx [c (- s) s c])))

(defn scaling
  "Returns a linear map which scales by [x y] in the x and y directions"
  [[x y]]
  (atx [x 0 0 y]))

(defn reflection
  "Returns a reflection about vector v (linear)"
  [[x y]]
  (if (= 0 x)
    (atx [-1 0 0 1])
    (let [m    (/ y x)
          m2   (* m m)
          m2+1 (inc m2)
          diag (/ (- 1 m2) m2+1)
          off  (/ (* 2 m) m2+1)]
       (atx [diag off off (- diag)]))))

(defn recentre
  "Given a linear transformation and a point, return an affine transformation
  corresponding to the transformation about the point."
  [origin atx]
  (geometry/comp-atx
   (translation origin)
   atx
   (translation (map - origin))))

;;;;; Applied affine txs

(defn transform
  "Returns a new shape which is the given affine map applies to the base shape.
  N.B.: If the atx is degenerate (det = 0) then an empty shape is returned."
  ;; REVIEW: This is technically incorrect since shapes can be collapsed to 1
  ;; dimension, and would thus still have a (degenerate but drawable)
  ;; boundary. Is there any valid use case here?
  [shape atx]
  (if (zero? (apply geometry/det (:matrix atx)))
    []
    {:type ::atx
     :base-shape shape
     :atx atx}))

(defn translate
  "Returns a copy of shape translated by [x y],"
  [shape b]
  (transform shape (translation b)))

(defn rotate
  "Returns a copy of shape rotated by angle around the given centre of
  rotation."
  ([shape angle] (rotate shape [0 0] angle))
  ([shape centre angle]
   (transform shape (recentre centre (rotation angle)))))

(defn scale
  "Returns a copy of shape scaled horizontally by a and verticaly by b. Centre
  is the origin (fixed point) of the transform."
  ([shape a]
   (scale shape [0 0] a))
  ([shape centre a]
   (let [extent (if (vector? a) a [a a])]
     (transform shape (recentre centre (scaling extent))))))

(defn reflect
  "Returns a copy of shaped reflected around the line with direction dir through
  centre."
  ([shape dir] (reflect shape [0 0] dir))
  ([shape centre dir]
   (transform shape (recentre centre (reflection dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Text
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def raw-text
  "Single line of text. No wrapping or truncation."
  {:type   ::raw-text
   :style  {:font "sans serif 10px"}
   :corner [0 0]
   :text   ""})

;; Renders text fit for the global coord transform (origin in the bottom left)
(deftemplate ::text
  {:style {:font "sans serif 10px"}
   :corner [0 0]
   :text ""}
  (-> raw-text
      (assoc :text text :style style :corner corner)
      (reflect corner [1 0])))
