(ns ubik.interactive.subs
  (:require [net.cgrand.macrovich :as macros :include-macros true]
            [ubik.interactive.db :as db]
            [ubik.interactive.impl]
            [clojure.walk :as walk]))

;; REVIEW: Is this really any better than two repetitive definitions? More
;; concise but way less readable...
(deftype SimpleSubscription
    #?(:clj [dependencies reaction
             ^:volatile-mutable _last-db
             ^:volatile-mutable _last-args
             ^:volatile-mutable _last-val]
       :cljs [dependencies reaction
              ^:mutable _last-db
              ^:mutable _last-args
              ^:mutable _last-val])
  ubik.interactive.impl/Subscription
  (deps [_] dependencies)
  (debug [_] [_last-db _last-args _last-val])
  #?(:clj clojure.lang.IDeref :cljs IDeref)
  (#?(:clj deref :cljs -deref) [_]
    (let [app-db (db/get-current-value)]
      (if (= _last-db app-db)
        _last-val
        (let [inputs (map deref dependencies)]
          (if (= inputs _last-args)
            _last-val
            (let [next (apply reaction inputs)]
              (set! _last-db app-db)
              (set! _last-args inputs)
              (set! _last-val next)
              next)))))))

(defn subscription? [sig]
  (satisfies? ubik.interactive.impl/Subscription sig))

(defn subscription
  {:style/indent [1]}
  [deps reaction]
  (SimpleSubscription. deps reaction (gensym "NOMATCH") (gensym "NOMATCH") nil))

(macros/deftime

;; Macros

(defn intern-subscription [form table]
  (let [k  (second form)
        tv @table]
    (if (contains? tv k)
      (get tv k)
      (let [sym (gensym)]
        (if (compare-and-set! table tv (assoc tv k sym))
          sym
          (recur form table))))))

(defn sub-checker [form]
  (when (and (or (list? form) (instance? clojure.lang.Cons form))
           (= (count form) 2)
           (every? symbol? form)
           (= (resolve (first form)) #'clojure.core/deref)
           (subscription? @(resolve (second form))))
    (second form)))

(defmacro build-subscription
  "Given a form --- which presumably derefs other subscriptions --- return a new
  subscription that reacts to its dependencies."
  [form]
  (let [symbols (atom {})

        body (walk/prewalk
              (fn [f]
                (if-let [sub (sub-checker f)]
                  (if (contains? @symbols sub)
                    (get @symbols sub)
                    (let [sym (gensym)]
                      (swap! symbols assoc sub sym)
                      sym))
                  f))
              form)

        sym-seq (seq @symbols)]
    (if (empty? sym-seq)
      `(atom ~form)
      `(subscription  [~@(map key sym-seq)]
        (fn [~@(map val sym-seq)]
           ~body)))))

(defmacro defsub
  "Creates a subscription for form and binds it to a var with name. Sets the
  docstring approriately if provided."
  {:style/indent [1]}
  [name form]
  `(def ~name (build-subscription ~form))))
