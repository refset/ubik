(ns lemonade.events
  (:require [lemonade.geometry :as geo]
            [lemonade.state :as state]))

(defprotocol IEventSystem
  (setup [this dispatch-fn]
    "Initialise the event system. System should call dispatch-fn with events
    received.")
  (teardown [this]
    "Clean up and shut down this event system."))

(defn dispatcher
  "Returns an event dispatch fn."
  ;; TODO: Will eventually need to use a queue and not block the main thread too
  ;; long. I can probably just lift the queue out of reframe
  [message-map]
  (fn dispatch! [state world event]
    (when-let [handlers (get message-map (:type event))]
      (doseq [handler handlers]
        (let [outcome (handler event state)]
          (when-let [next-event (:dispatch outcome)]
            (dispatch! state world next-event))
          (when-let [mutation (:mutation outcome)]
            (state/handle-mutation mutation)))))))
