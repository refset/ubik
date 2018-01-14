(defproject macroexpanse/lemonade "0.1.0"
  :description "High level language for graphical and UI programming. No markup."
  :url "https://github.com/tgetgood/lemonade"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :scm {:name "git"
        :url "https://github.com/tgetgood/lemonade"}

  :pom-addition [:developers [:developer {:id "tgetgood"}
                              [:name "Thomas Getgood"]]]

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.946"]
                 [quil "2.6.0" :exclusions [[org.clojure/clojure]]]]

  :plugins [[lein-figwheel "0.5.14"]
            [lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]]

  :source-paths ["src"]

  :cljsbuild
  {:builds
   [{:id           "canvas"
     :source-paths ["src"]

     :figwheel     {:on-jsload "lemonade.demos.canvas/on-js-reload"}

     :compiler     {:main                 lemonade.demos.canvas
                    :asset-path           "js/compiled/out"
                    :output-to            "resources/public/js/compiled/canvas.js"
                    :output-dir           "resources/public/js/compiled/out"
                    :parallel-build       true
                    :source-map-timestamp true
                    :checked-arrays       :warn
                    :preloads             [devtools.preload]}}
    {:id           "min"
     :source-paths ["src"]
     :compiler     {:main            lemonade.demos.canvas
                    :output-to       "resources/public/js/compiled/app.js"
                    :optimizations   :advanced
                    :parallel-build  true
                    :closure-defines {goog.DEBUG false}
                    :pretty-print    false}}]}

  :figwheel {:css-dirs ["resources/public/css"]}

  :profiles
  {:dev {:dependencies  [[binaryage/devtools "0.9.4"]
                         [org.clojure/spec.alpha "0.1.134"]
                         [org.clojure/tools.namespace "0.2.11"]
                         [org.clojure/core.async "0.3.465"]
                         [figwheel-sidecar "0.5.14"
                          :exclusions [org.clojure/core.async]]
                         [com.cemerick/piggieback "0.2.2"]
                         [org.clojure/test.check "0.9.0"]]
         ;; need to add dev source path here to get user.clj loaded
         :source-paths  ["src" "dev"]

         :repl-options  {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
         ;; need to add the compliled assets to the :clean-targets
         :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                           :target-path]}})
