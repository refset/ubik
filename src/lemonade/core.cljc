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
              ;; REVIEW: I'm using lexical capture to prevent null styles. There's
              ;; probably a better way.
              (let [~'style (or ~'style {})]
                ~expansion)))))))

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

;; REVIEW: The line and bezier here are not very reusable. They're a royal pain
;; to manipulate via affine transformations. Path segments are manipulated by
;; their boundaries, not as shapes in the plane.
;;
;; Maybe there's a fundamental distinction to be made between path segments and
;; everything else?

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Higher Order Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; REVIEW: It would be nice if these were types that one could conj onto. As is
;; I'm implementing custom conj for each anyway...
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
  {:type   ::arc
   :centre c
   :radius r
   :from   0
   :to     (* 2 pi)
   :clockwise? cw?})

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
  "Returns a new shape which is the given affine map applies to the base shape."
  ;; REVIEW: If an affine transformation is degenerate, then whatever it's
  ;; applied to will disappear from view, and fuck up the math
  ;; simultaneously. It would seem on the surface that just returning a default
  ;; shape that renders to nothing in this case would be far superior to
  ;; actually inflicting degenerate math on the system. But null pointers...
  [shape atx]
  {:type ::atx
   :base-shape shape
   :atx atx})

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
;;;;; Drawing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private idem (atom nil))

(defn draw-loop
  "Starts an event loop which calls draw-fn on (app-fn @state-ref) each
  animation frame if @state-ref has changed."
  ;; TODO: Deal with the possibility of static animations (animations that don't
  ;; depend on state).
  ;; IDEA: If hander returns a sequential with metadata ^:animation then treat
  ;; it as a sequence of frames and animate.
  [state-ref app-fn draw-fn profile?]
  (when-let [stop @idem]
    (stop))
  (let [last-state (atom nil)
        continue?  (atom true)]
    (letfn [(recurrent [counter last-run]
              #?(:clj
                 (draw-fn (app-fn @state-ref ))
                 :cljs
                 (js/window.requestAnimationFrame
                  (fn [now]
                    (let [state @state-ref]
                      (when-not (= state @last-state)
                        (let [world (app-fn state)]
                          (swap! state-ref assoc :lemonade.core/world world)
                          (draw-fn world))
                        (reset! last-state @state-ref)))
                    (when @continue?
                      (if (and profile? (< 10000 (- now last-run)))
                        (do
                          (println (* 1000 (/ counter (- now last-run))))
                          (recurrent 0 now))
                        (recurrent (inc counter) last-run)))))))]
      (recurrent 0 0)

      (reset! idem
              (fn []
                (reset! continue? false))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Plugins (middleware?)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn initialise! [{:keys [render app-db handler profile? event-system]}]
  ;; start event system (teardown previous)
  ;; setup draw loop
  ;; pass state ref into upper event system.
  (when-let [f (:teardown event-system)]
    (f))
  (when-let [f (:setup event-system)]
    (f))

  ;; TODO: Set global ref pointing at app-db, or pass two args through
  ;; everything?
  ;; Passing just the ref is no good since this has to work in a threaded
  ;; context on the jvm and consistent render state is essential.
  ;; Dynamic binding might be an option, but is that really any better than an
  ;; atom? It would require one less deref.

  ;; Should we manage the app db ourselves a la re-frame, and just have the user
  ;; pass in the initial state? That's another option.
  (draw-loop app-db handler render profile?))
