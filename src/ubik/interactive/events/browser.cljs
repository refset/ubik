(ns ubik.interactive.events.browser
  (:require [clojure.string :as string]
            [ubik.interactive.db :refer [the-world]]
            [ubik.core :as u]))

(defn canvas-elem []
  (js/document.getElementById "canvas"))

(defn- kw->js
  "Returns js property name corresponding to idiomatic keyword."
  [kw]
  (string/replace (name kw) #"-" ""))

(defn- oget
  "Unsafe and fast version of goog.object/get."
  [o k]
  (unchecked-get o k))

(defn- pixel-point
  "Returns pixel clicked on relative to canvas element. Assumes origin in the
  bottom left."
  [elem e]
  [(- (oget e "clientX") (oget elem "offsetLeft"))
   (- (oget elem "height")
      (- (oget e "clientY") (oget elem "offsetTop")))])

(defn- now []
  (js/Date.now))

(defn ^:private event-map
  "Returns a map of event handlers for elem."
  [elem]
  {:context-menu (fn [e]
                   (.preventDefault e)
                   (.stopPropagation e))

   ;; This is to make sure keyboard events happen at all.
   :mouse-over (fn [e]
                 (.preventDefault e)
                 (.focus elem))

   :mouse-out (fn [e]
                (.preventDefault e)
                {:type :mouse-out})

   :mouse-down (fn [e]
                 (.preventDefault e)
                 (let [b (.-button e)
                       p (pixel-point elem e)]
                   (case b
                     ;; Only handling left click for now.
                     0 {:type     :left-mouse-down
                        :location p
                        :time     (now)}
                     nil)))

   :touch-move (fn [e]
                 (.preventDefault e))

   :mouse-move (fn [e]
                 (.preventDefault e)
                 {:type     :mouse-move
                  :location (pixel-point elem e)
                  :time     (now)})

   :mouse-up (fn [e]
               (.preventDefault e)
               ;; REVIEW: Is this really to right place to decide what
               ;; buttons a mouse has? We do need some kind of layer between
               ;; "button 0" and "left click", but here might not be the
               ;; place...
               (let [b (.-button e)
                     p (pixel-point elem e)]
                 (case b
                   ;; Only handling left click for now.
                   0 {:type     :left-mouse-up
                      :location p
                      :time     (now)}
                   nil)))

   :wheel (fn [e]
            (.preventDefault e)
            (let [mag (if (= 1 (oget e "deltaMode")) 15 1)
                  dx  (* mag (js/parseInt (oget e "deltaX")))
                  dy  (* mag (js/parseInt (oget e "deltaY")))]
              {:type     :wheel
               :location (pixel-point elem e)
               :time     (now)
               :dx       dx
               :dy       dy}))

   :key-down (fn [e]
               (.preventDefault e)
               {:type     :key-down
                :time     (now)
                :key      (.-key e)
                :key-code (.-keyCode e)})

   :key-up (fn [e]
             (.preventDefault e)
             {:type     :key-up
              :time     (now)
              :key      (.-key e)
              :key-code (.-keyCode e)})})

(defonce ^:private registered-listeners (atom {}))

(defn wrap-dispatch [ev handler dispatch-fn]
  (fn [e]
    (when-let [res (handler e)]
      (dispatch-fn
       ev
       (assoc res :ubik.interactive.events/world @the-world)))))

(defn connect-events [host events dispatch-fn]
  (let [elem     (u/base host)
        evh      (event-map elem)
        handlers (into {} (map (fn [[k v]]
                                 [k (if (contains? events k)
                                      (comp #(dispatch-fn k) v)
                                      v)])
                               evh))]
    (doseq [[event cb] handlers]
        (.addEventListener elem (kw->js event) cb))
    (swap! registered-listeners assoc host handlers)))

(defn disconnect-events [host]
  (let [handlers (get @registered-listeners host)
        elem (u/base host)]
    (doseq [[event cb] handlers]
      (.removeEventListener elem (kw->js event) cb))
    (swap! registered-listeners dissoc host)))
