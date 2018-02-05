(ns lemonade.core
  #?(:cljs (:require-macros [lemonade.core :refer [deftemplate]]))
  (:require [clojure.string :as string]
            [lemonade.math :as math]))

(defprotocol ITemplate
  (expand-template [this] "Returns the expansion of this template"))

(defn type-case
  "Returns a symbol in idiomatic TypeCase given one in clojure standard
  symbol-case."
  [sym]
  (symbol (apply str (map string/capitalize (string/split (name sym) #"-")))))

#?(:clj
   (defmacro deftemplate
     "Defines a new shape template. Creates a new record whose name is
  instance-name converted to UpperCamelCase as per record naming conventions.

  The canonical instance of the new template will be bound to instance-name.

  expansion will be executed in an environment when all keys of the template
  name have been bound to symbols. Expansions must return a valid shape
  (template or otherwise).

  Optionally impls are protocol implementations as per defrecord."
     [instance-name template expansion & impls]
     (let [template-name (type-case instance-name)
           fields (map (comp symbol name) (keys template))]
       `(do
          ;; TODO: I can generate a spec from the field list and then check it's
          ;; valid at expansion time. I think that would be a good place to find
          ;; errors.
          ;;
          ;; The problem is that adding a spec/def into this macro expansion
          ;; causes the whole thing to go haywire even though the relevant parts
          ;; of the expansion don't change at all...
          (defrecord ~template-name [~@fields]
            lemonade.core/ITemplate
            (lemonade.core/expand-template [this#]
              ~expansion)
            ~@impls)
          (def ~instance-name
            (~(symbol (str "map->" template-name)) ~template))))))

(defn template? [shape]
  (satisfies? ITemplate shape))

(defn template-expand-all [shape]
  (if (template? shape)
    (recur (expand-template shape))
    shape))

(defn classify
  "Shape classifier. Returns a keyword type for any valid shape. Returns nil for
  invalid shapes. Intended for use in multimethods."
  [shape]
  (cond
    (template? shape)   ::template
    (sequential? shape) ::sequential
    :else               (:type shape)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; 1D Geometry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ISegment
  (endpoints [this]
    "Returns the endpoints of this segment in order (segments are directed
    paths.")
  (contiguous [this] "Returns true iff this segment is connected."))

;;;;;; Path topology

(defn connected?
  "Returns true if the sequential of paths passed in are pairwise connected."
  [paths]
  (loop [[path & more] paths]
    (if (seq more)
      (when (= (:to path) (:from (first more)))
        (recur more))
      true)))

(defn closed-segment?
  [path]
  (and (contiguous path)
       (let [[start end] (endpoints path)]
         (= start end))))

(defn closed-path?
  "Returns true if paths form the boundary of a connected surface.
  Technically I'm requiring a connecting bridge of non-zero measure. Not sure if
  that's a good call...
  Simply connected not necessary, just no point connections."
  [paths]
  (if (= 1 (count paths))
    (closed-segment? (first paths))
    (let [points (map (fn [[f t]] {:from f :to t}) (map endpoints paths))]
      (when (connected? paths)
        (= (:from (first paths)) (:to (last paths)))))))

;;;;; Path Segments

(defrecord Line [style from to]
  ISegment
  (endpoints [_] [from to])
  (contiguous [_] true))

(def line
  (map->Line
   {:type ::line
    :from [0 0]
    :to [1 1]}))

(defrecord Bezier [style from to c1 c2]
  ISegment
  (endpoints [_] [from to])
  (contiguous [_] true))

(def bezier
  "Bezier cubic to be precise."
  (map->Bezier
   {:type ::bezier
    :from [0 0]
    :c1 [0 0]
    :c2 [1 1]
    :to [1 1]}))

(defrecord Arc [style centre radius from to clockwise?]
  ISegment
  (endpoints [_]
    ;; FIXME: assumes |from - to| <= 2π
    (->> [from to]
         (map (juxt math/cos math/sin))
         (map (partial map (partial * radius)))
         (mapv (partial mapv + centre))))
  (contiguous [_] true))

(def arc
  {:type   ::arc
   :centre [0 0]
   :radius 1
   :from   0
   :to     (* 2 math/pi)
   :clockwise? false})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Higher Order Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Path [style segments])

(defn path
  ([segments] (path {} segments))
  ([style segments]
   (map->Path {:type ::path
               :closed? false
               :style style
               ;; FIXME: Duplicate keys for compatibility while refactoring
               :contents segments
               :segments segments})))

(defrecord Region [style boundary])

(defn region
  ([boundary] (region {} boundary))
  ([style boundary]
   (map->Region
    {:type ::path
     :style style
     :boundary boundary
     :closed? true
     :contents boundary})))

(defrecord Composite [style contents])

(defn composite
  ([contents] (composite {} contents))
  ([style contents]
   ;; TODO: I'm leaving the :type keys in for now so that I don't break
   ;; multimethods that depend on them while refactoring.
   (map->Composite {:type ::composite
                    :style style
                    :contents contents})))

(defrecord Frame [corner width height style base-shape])

(def frame
  "A frame is a visual which restricts image to fall within extent. Extent is a
  map with keys as per :lemonade.core/rectangle"
  (map->Frame
   {:type ::frame
    :corner [0 0]
    :width 1
    :height 1
    :style {}
    ;; FIXME: Duplicate
    :contents []
    :base-shape []}))

(defn with-style [style & shapes]
  (composite style shapes))

;; REVIEW: Are both of these used?
(defn style [shape style]
  (with-style style shape))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Core Template Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn full-arc [c r & [cw?]]
  (assoc arc
         :centre c
         :radius r
         :from   0
         :to     (* 2 math/pi)
         :clockwise? cw?))

(deftemplate circle
  {:style {} :radius 1 :centre [0 0]}
  (region style [(full-arc centre radius)]))

(deftemplate annulus
  {:style {} :inner-radius 1 :outer-radius 2 :centre [0 0]}
  (region style
          [(full-arc centre inner-radius)
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

(deftemplate polyline
  {:style {} :points []}
  (let [segs (map (fn [[x y]]
                      {:type ::line
                       :from x
                       :to   y})
                   (partition 2 (interleave points (rest points))))
        closed? (= (first points) (last points))]
    ((if closed? region path) style segs)))

(deftemplate rectangle
  {:style  {}
   :corner [0 0]
   :height 1
   :width  1}
  (let [[x1 y1] corner
        x2      (+ x1 width)
        y2      (+ y1 height)]
    (assoc polyline
           :style style
           :points [[x1 y1] [x2 y1] [x2 y2] [x1 y2] [x1 y1]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Affine Transforms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;; Affine Maps

(defn translation
  "Returns an affine transformation corresponding to translation by b"
  [b]
  (math/atx math/idm b))

(defn rotation
  "Returns a counterclockwise rotation about the origin by angle (linear).
  Note: angle in degrees."
  [angle]
  (let [r (math/deg->rad angle)
        c (math/cos r)
        s (math/sin r)]
    (math/atx [c (- s) s c])))

(defn scaling
  "Returns a linear map which scales by [x y] in the x and y directions"
  [[x y]]
  (math/atx [x 0 0 y]))

(defn reflection
  "Returns a reflection about vector v (linear)"
  [[x y]]
  (if (zero? x)
    (math/atx [-1 0 0 1])
    (let [m    (/ y x)
          m2   (* m m)
          m2+1 (inc m2)
          diag (/ (- 1 m2) m2+1)
          off  (/ (* 2 m) m2+1)]
       (math/atx [diag off off (- diag)]))))

(defn recentre
  "Given a linear transformation and a point, return an affine transformation
  corresponding to the transformation about the point."
  [origin atx]
  (math/comp-atx
   (translation origin)
   atx
   (translation (map - origin))))

;;;;; Applied Affine Transformations

(defrecord AffineTransformation [atx base-shape])

(defn transform
  "Returns a new shape which is the given affine map applies to the base shape.
  N.B.: If the atx is degenerate (det = 0) then an empty shape is returned."
  ;; REVIEW: This is technically incorrect since shapes can be collapsed to 1
  ;; dimension, and would thus still have a (degenerate but drawable)
  ;; boundary. Is there any valid use case here?
  [shape atx]
  (if (zero? (apply math/det (:matrix atx)))
    []
    (map->AffineTransformation
     {:type ::atx
      :base-shape shape
      :atx atx})))

(defn translate
  "Returns a copy of shape translated by [x y],"
  [shape b]
  (with-meta
    (transform shape (translation b))
    {:atx-type :translation :point b}))

(defn rotate
  "Returns a copy of shape rotated by angle around the given centre of
  rotation."
  ([shape angle] (rotate shape [0 0] angle))
  ([shape centre angle]
   (with-meta
     (transform shape (recentre centre (rotation angle)))
     {:atx-type :rotation :point centre :angle angle})))

(defn scale
  "Returns a copy of shape scaled horizontally by a and verticaly by b. Centre
  is the origin (fixed point) of the transform."
  ([shape a]
   (scale shape [0 0] a))
  ([shape centre a]
   (let [extent (if (vector? a) a [a a])]
     (with-meta
       (transform shape (recentre centre (scaling extent)))
       {:atx-type :scale :point centre :extent extent}))))

(defn reflect
  "Returns a copy of shaped reflected around the line with direction dir through
  centre."
  ([shape dir] (reflect shape [0 0] dir))
  ([shape centre dir]
   (with-meta
     (transform shape (recentre centre (reflection dir)))
     {:atx-type :reflection :point centre :line dir})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Text
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord RawText [style corner text])

(def raw-text
  "Single line of text. No wrapping or truncation."
  (map->RawText
   {:type   ::raw-text
    :style  {:font "sans serif 10px"}
    :corner [0 0]
    :text   ""}))

;; Renders text fit for the global coord transform (origin in the bottom left)
(deftemplate text
  {:style {:font "sans serif 10px"}
   :corner [0 0]
   :text ""}
  (-> raw-text
      (assoc :text text :style style :corner corner)
      (reflect corner [1 0])))
