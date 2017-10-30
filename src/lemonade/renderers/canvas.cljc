(ns lemonade.renderers.canvas
  "Render shapes to HTML Canvas."
  (:require [clojure.spec.alpha :as s]
            [lemonade.core :as core]
            [lemonade.geometry :as geometry]))

(comment "Uses a half baked CPS transform so that some of the work can be done
  at compile time if the shape is statically known. Not sure how much inlining
  the CLJS compiler (Closure?) does, but there's potential. Doesn't actually do
  anything at compile time.")
;; REVIEW: Diabled

(def noop (constantly nil))

(defmulti render-fn :type)

;; REVIEW: This weird special dispatch on default feels pretty kludgy,
(defmethod render-fn :default
  [x]
  (cond
    (sequential? x)
      (apply juxt (map render-fn x))

    (contains? (set (keys (methods core/template-expand))) (:type x))
      (render-fn (core/template-expand x))

    :else
      (do
        (println (str "I don't know how to render a " (:type x)))
        noop)))

(defn renderer
  "Returns a render function which when passed a context, renders the given
  shape."
  [shape]
  (let [cont (render-fn shape)]
    ;; Just wrap the render fn in some state guarding. Ideally we want to be
    ;; able to insert our code into an existing canvas app without messing it up
    ;; or being messed up by it. Let's see how that goes...
    (fn [ctx]
      (doto ctx
        .save
        (.setTransform 1 0 0 1 0 0)
        cont
        .restore
        .beginPath))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Styling
;;
;; Ubiquitous use of dynamic environments. Is this clever or too clever?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *style*
  "Current style at this point in the render process. Initialised to default
  style."
  {:stroke  {:width   0
             :colour  :black
             :dashed  []
             :corners :mitre}
   :fill    :none
   :opacity 1
   :font    "sans serif 10px"})

(defn set-style! [s])
(defn unset-style! [s])
(defn push-style [s])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Internal render logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *in-path?* false)

(defn apply-atx [{[a b c d] :matrix [e f] :translation}]
  (fn [ctx]
    (.transform ctx a c b d e f)))

(defmethod render-fn ::core/atx
  [{:keys [base-shape atx]}]
  (let [tx   (apply-atx atx)
        itx  (apply-atx (geometry/invert-atx atx))
        cont (render-fn base-shape)]
    (fn [ctx]
      (doto ctx
        tx
        cont
        itx))))

(defmethod render-fn ::core/path
  [{:keys [closed? contents style]}]
  (if (empty? contents)
    noop
    (let [cont (apply juxt (map render-fn contents))]
      (fn [ctx]
        (set-style! style)
        (.beginPath ctx)
        (binding [*in-path?* true
                  *style* (push-style style)]
          (cont ctx))
        (when closed?
          (.closePath ctx)
          (when (:fill style)
            (.fill ctx)))
        (.stroke ctx)
        (unset-style! style)))))

(defmethod render-fn ::core/composite
  [{:keys [style contents]}]
  (let [cont (apply juxt (map render-fn contents))]
    (fn [ctx]
      (set-style! style)
      (binding [*style* (push-style style)]
        (cont ctx))
      (unset-style! style))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Leaf renderers
;;
;; At some point we have to render something, Less and less though, it would
;; appear.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod render-fn ::core/arc
  [{[x y] :centre r :radius :keys [from to style clockwise?]}]
  (fn [ctx]
    (.moveTo ctx (+ x r) y)
    (.arc ctx x y r from to (boolean clockwise?))
    (when-not *in-path?*
      (.stroke ctx))))

(defmethod render-fn ::core/line
  [{:keys [from to style] :as o}]
  (fn [ctx]
    (when-not *in-path?*
      (.beginPath ctx))
    (.moveTo ctx (first from) (second from))
    (.lineTo ctx (first to) (second to))
    (when-not *in-path?*
      (.stroke ctx))))

(defmethod render-fn ::core/bezier
  [{[x1 y1] :from [x2 y2] :to [cx1 cy1] :c1 [cx2 cy2] :c2}]
  (fn [ctx]
    (.moveTo ctx x1 y1)
    (.bezierCurveTo ctx cx1 cy1 cx2 cy2 x2 y2)
    (when-not *in-path?*
      (.stroke ctx))))
