(ns ubik.events.canvas
  (:require [clojure.string :as string]
            [ubik.events :as events]))

(defn- kw->js [kw]
  (string/replace (name kw) #"-" ""))

(defn oget [o k]
  (unchecked-get o k))

(defn pixel-point
  "Returns pixel clicked on relative to canvas element."
  [elem e]
  [(- (oget e "clientX") (oget elem "offsetLeft"))
   (- (oget elem "height") (- (oget e "clientY") (oget elem "offsetTop")))])

(defn ^:private event-map
  [elem dispatch-fn]
  {:context-menu (fn [e]
                   (.preventDefault e)
                   (.stopPropagation e))

   ;; This is to make sure keyboard events happen at all.
   :mouse-over   (fn [e]
                   (.preventDefault e)
                   (.focus elem))

   :mouse-down   (fn [e]
                   (.preventDefault e)
                   (let [b (.-button e)
                         p (pixel-point elem e)]
                     (case b
                       ;; Only handling left click for now.
                       0 (dispatch-fn {:type     ::events/left-mouse-down
                                       :location p})
                       nil)))

   :touch-move   (fn [e]
                   (.preventDefault e))

   :mouse-move   (fn [e]
                   (.preventDefault e)
                   (dispatch-fn {:type     ::events/mouse-move
                                 :location (pixel-point elem e)}))

   :mouse-up     (fn [e]
                   (.preventDefault e)
                   ;; REVIEW: Is this really to right place to decide what
                   ;; buttons a mouse has? We do need some kind of layer between
                   ;; "button 0" and "left click", but here might not be the
                   ;; place...
                   (let [b (.-button e)
                         p (pixel-point elem e)]
                     (case b
                       ;; Only handling left click for now.
                       0 (dispatch-fn {:type     ::events/left-mouse-up
                                       :location p})
                       nil)))

   :wheel        (fn [e]
                   (.preventDefault e)
                   (let [mag (if (= 1 (oget e "deltaMode")) 15 1)
                         dx  (* mag (js/parseInt (oget e "deltaX")))
                         dy  (* mag (js/parseInt (oget e "deltaY")))]
                     (dispatch-fn {:type     ::events/wheel
                                   :location (pixel-point elem e)
                                   :dx       dx
                                   :dy       dy})))

   :key-down     (fn [e]
                   (.preventDefault e)
                   ;; FIXME: stub
                   (dispatch-fn {:type ::events/key-down
                                 :raw  e}))

   :key-up       (fn [e]
                   (dispatch-fn {:type ::events/key-up
                                 :raw  e}))})

(defonce ^:private registered-listeners (atom {}))

(defn teardown [elem]
          (doseq [[event cb] @registered-listeners]
            (.removeEventListener elem (kw->js event) cb)))

(defn setup [elem dispatch-fn]
       (let [handlers (event-map elem dispatch-fn)]
         (reset! registered-listeners handlers)
         (doseq [[event cb] handlers]
           (.addEventListener elem (kw->js event) cb))))