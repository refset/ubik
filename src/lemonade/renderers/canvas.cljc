(ns lemonade.renderers.canvas
  (:require [#?(:cljs cljs.pprint :clj clojure.pprint) :refer [pprint]]
            [clojure.spec.alpha :as s]
            [lemonade.core :as core]
            [lemonade.geometry :as geometry]))

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
  (render-fn shape))

;; REVIEW: How much can we do at compile time?

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Internal render logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *in-path?* false)
(def ^:dynamic *style* {})

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
        (.save ctx)
        (.beginPath ctx)
        ;; FIXME: Adhoc temp single style.
        (when (:negative style)
          (aset ctx "globalCompositeOperation" "destination-out")
          (aset ctx "globalAlpha" 0.e))
        (binding [*in-path?* true]
          (cont ctx))
        (when closed?
          (.closePath ctx)
          (when (:fill style)
            (.fill ctx)))
        (.stroke ctx)
        (.restore ctx)))))

(defmethod render-fn ::core/composite
  [{:keys [style contents]}]
  (let [cont (apply juxt (map render-fn contents))]
    ;; TODO: styles
    cont))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Leaf renderers
;;
;; At some point we have to render something, Less and less though, it would
;; appear.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod render-fn ::core/arc
  [{[x y] :centre r :radius :keys [from to style]}]
  (fn [ctx]
    (.moveTo ctx (+ x r) y)
    (.arc ctx x y r from to false)))

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
    (.bezierCurveTo ctx cx1 cy1 cx2 cy2 x2 y2)))
