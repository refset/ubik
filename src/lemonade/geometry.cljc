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
   [(apply max (map first points)) (apply max (map second points))]])

(defn pi-mults
  "Returns multiples of π/2 in interval [r s]"
  [r s]
  (filter #(<= r % s) (map #(* (/ math/pi 2) %) (range 0 8))))

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
  [{[x y] :centre from :from to :to r :outer-radius ir :inner-radius}]
  (let [cf (math/cos from)
        ct (math/cos to)
        sf (math/sin from)
        st (math/sin to)]
    (min-box (conj (map (fn [x]
                          [(* r (math/cos x)) (* r (math/sin x))])
                        (pi-mults from to))
                   [(* r cf) (* r sf)]
                   [(* r ct) (* r st)]
                   [(* ir cf) (* ir sf)]
                   [(* ir ct) (* ir st)]))))

(defmethod extent ::core/polyline
  [{:keys [points]}]
  (min-box points))

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
  subtree (potentially a subforest) of the original."
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: Index objects (not until speed becomes an issue)
;;
;; Tetrafurcation algo:
;; Divide screen into quadrants, bucket images into quadrants, recur if any
;; quadrant has more than N objects (N to be tuned).
;;
;; To retrieve, pick the quadrant the point is in until we come to a bucket (no
;; more quadrants. Iterate through each VO in bucket and collect those that the
;; point is inside.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Winding number algo for second pass
;; http://geomalgorithms.com/a03-_inclusion.html
;; https://www.ams.org/journals/mcom/1973-27-122/S0025-5718-1973-0329216-1/S0025-5718-1973-0329216-1.pdf
;; Need to generalise these to beziers