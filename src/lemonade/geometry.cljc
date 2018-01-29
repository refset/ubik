(ns lemonade.geometry
  (:require [lemonade.core :as core]
            [lemonade.math :as math]))

(defn normalise
  "Converts an extent into normal form [lower-left top-right]"
  [[[x1 y1] [x2 y2]]]
  (let [[x1 x2] (sort [x1 x2])
        [y1 y2] (sort [y1 y2])]
    [[x1 y1] [x2 y2]]))

(defn within?
  "Returns true is point is with the given bounding box."
  [[x y] [[x1 y1] [x2 y2]]]
  (and (<= x1 x x2) (<= y1 y y2)))

(defn min-box
  "Returns the smallest bounding box around the convex hull of points"
  [points]
  [[(apply min (map first points)) (apply min (map second points))]
   (apply max (map first points)) (apply max (map second points))])

(defmulti extent
  "Returns a bounding box for shape. Doesn't have to be optimal, but the
  narrower the better."
  :type)

(defmethod extent :default
  [s]
  [[0 0] [0 0]])

(defmethod extent :pixel.core/pixel
  [{[x y] :location}]
  [[x y] [(inc x) (inc y)]])

(defmethod extent ::core/circle
  [{[x y] :centre r :radius}]
  [[(- x r) (- y r)] [(+ x r) (+ y r)]])

(defmethod extent ::core/line
  [{:keys [from to]}]
  [from to])

(defmethod extent ::core/annulus
  [{r :outer-radius c :centre}]
  (extent (assoc core/circle :radius r :centre c)))

(defmethod extent ::core/rectangle
  [{[x y] :corner w :width h :height}]
  [[x y] [(+ x w) (+ y h)]])

(defmethod extent :elections-demo.core/annular-wedge
  [{[x y] :centre  }]
  [[0 0] [0 0]])

;; There are two problems to keep in mind.
;;
;; The Needle in a Haystack:
;;
;; If there's only one thing we're interested in, then knowing whether it was
;; clicked on or not should take constant time.
;;
;; The General Problem:
;;
;; If there are a great number of objects of interest on the screen, then
;; determining which one(s) of them contain a given point should be at most
;; logarithmic in the number of objects.
;;
;;
;; I'm convinced that the tetrafurcation algorithm with a fixed cutoff (to be
;; tuned) will do the trick.
;; A difficulty here is going to be precision. That is winding number and
;; interior/exterior distintion for complex shapes.

(defn branch-seq
  "Given a render tree, return a seq of all paths from the root to a leaf."
  [tree]
  (let [type  (core/classify tree)
        clean (fn [tree]
                (-> tree
                    (dissoc :lemonade.events/handlers)
                    (assoc :tag (gensym))))]
    (cond
      (= type ::core/sequential)
      (let [node (dissoc (clean (core/composite [])) :contents)]
        (->> tree
             (mapcat branch-seq)
             (map (partial cons node))))

      (= type ::core/composite)
      (let [node (dissoc (clean tree) :contents)]
        (->> tree
             :contents
             (mapcat branch-seq)
             (map (partial cons node))))

      (= type ::core/atx)
      (let [node (dissoc (clean tree) :base-shape)]
        (->> tree
             :base-shape
             branch-seq
             (map (partial cons node))))

      :else
      (list (list tree)))))

(defn bound-branch
  "Given a branch, calculate a (not necessarily optimal) bounding box."
  [[head & tail]]
  (if-not (seq tail)
    (normalise (extent head))
    (if (= ::core/atx (:type head))
      (mapv (partial math/apply-atx (:atx head)) (bound-branch tail))
      (recur tail))))

(defn effected-branches
  "Returns all branches of tree which contain point in their bounding boxes."
  [point tree]
  (->> tree
       branch-seq
       (filter #(within? point (bound-branch %)))))

(defn retree
  "Given a collection of branches that share the same root, reconstruct a
  subtree of the original."
  [branches]
  (let [sets  (group-by first branches)
        trees (map (fn [[root branches]]
                     (let [root (dissoc root :tag)
                           type (core/classify root)]
                       (cond
                         (= type ::core/composite)
                         (assoc root :contents (retree (map rest branches)))

                         (= type ::core/atx)
                         (let [children (retree (map rest branches))]
                           (assoc root :base-shape
                                  (if (sequential? children)
                                    (first children)
                                    children)))

                         :else
                         root)))
                   sets)]
    trees))
