(ns lemonade.system
  (:require [lemonade.core :as core]
            [lemonade.events.hlei :as hlei]
            clojure.walk

            [lemonade.hosts :as hosts]
            [lemonade.state :as state]
            [lemonade.window :as window]
            [lemonade.events :as events]
            [lemonade.spray :as spray]))

(defonce ^:private idem (atom nil))

(defonce ^:dynamic *profile* false)

(defn draw-loop
  "Starts an event loop which calls draw-fn on (app-fn @state-ref) each
  animation frame if @state-ref has changed."
  [state-ref shape]
  (when-let [stop @idem]
    (stop))
  (let [last-state (atom (gensym "NO-MATCH"))
        continue?  (atom true)]
    (letfn [(recurrent [counter last-run]
              #?(:clj
                 (core/draw! (spray/instantiate shape @state-ref))
                 :cljs
                 (js/window.requestAnimationFrame
                  (fn [now]
                    (when @continue?
                      (let [state @state-ref]
                        (when-not (= state @last-state)
                          (let [world (spray/instantiate shape state)]
                            (swap! state-ref assoc :lemonade.core/world world)
                            (core/draw! world))
                          (reset! last-state @state-ref)))
                      (if (and *profile* (< 1000 (- now last-run)))
                        (do
                          (println (* 1000 (/ counter (- now last-run))))
                          (recurrent 0 now))
                        (recurrent (inc counter) last-run)))))))]
      (recurrent 0 0)

      (reset! idem
              (fn []
                (reset! continue? false))))))

(defn with-defaults [opts]
  (merge
   {:app-db         (atom {})
    :host           hosts/default-host
    :size           :fullscreen
    :event-handlers {}}
   opts))

;; REVIEW: I've made this dynamic so that it can be swapped out by code
;; introspection programs which need to evaluate code and grab their handlers,
;; state atoms, etc.
;;
;; There's got to be a better way to get the desired dynamism
(defn ^:dynamic initialise!
  "Initialises the system, whatever that means right now."
  [opts]
  (let [{:keys [app-db render host event-handlers size]}
        (with-defaults opts)]

    ;; Set screen size
    ;; Initialise internal DB
    ;; Set up event handling
    ;; Start draw loop

    (when core/*host*
      (core/teardown core/*host*))

    (core/setup host)
    (set! core/*host* host)

    (reset! state/internal-db app-db)
    (events/add-handlers event-handlers)

    (cond
      (= size :fullscreen)                    (core/fullscreen host)
      (and (vector? size) (= 2 (count size))) (core/resize-frame host size))

    (when-not (:lemonade.core/window @@state/internal-db)
      (swap! @state/internal-db assoc :lemonade.core/window window/initial-window))

    (swap! @state/internal-db update :lemonade.core/window assoc
           :height (core/height host)
           :width  (core/width host))

    (draw-loop @state/internal-db
               render)))

(defn stop! []
  (when-let [sfn @idem]
    (sfn)))
