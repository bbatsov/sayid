(ns com.billpiel.sayid.string-output2
  (:require [com.billpiel.sayid.workspace :as ws]
            [com.billpiel.sayid.view :as v]
            [com.billpiel.sayid.util.other :as util]
            [tamarin.core :as tam]
            clojure.string))

(def ^:dynamic *view* (fn [x] {:args true
                               :return true
                               :throw true
                               :selects false}))

(def ^:dynamic *color-palette* [1 3 2 6 5])

(def colors-kw [:black :red :green :yellow :blue :magenta :cyan :white])

(defn apply-color-palette
  [n]
  (when n
    (nth *color-palette*
         (mod n (count *color-palette*)))))

(def line-break-token {:string "\n" :length 1 :line-break true})

(defn tkn
  [s & {:keys [fg fg* bg bg* bold] :as props}]
  (if (= s "\n")
    line-break-token
    (let [s' (cond (string? s) s
                   (sequential? s) (apply str s)
                   :else (pr-str s))]
      (-> props
          (dissoc :fg :fg* :bg :bg* :bold)
          (assoc 
           :string s'
           :length (count s')
           :fg-color (get colors-kw (or fg (apply-color-palette fg*)))
           :bg-color (get colors-kw (or bg (apply-color-palette bg*)))
           :bold bold)))))

(defn mk-lazy-color-fg*-str
  ([s] (mk-lazy-color-fg*-str s 0))
  ([s i] (lazy-cat [(tkn s :fg* i)]
                   (mk-lazy-color-fg*-str s (inc i)))))

(def lazy-color-fg*-pipes (mk-lazy-color-fg*-str "|"))

(defn slinky-pipes
  [len & {:keys [end]}]
  (concat
   (take (- len (count end)) lazy-color-fg*-pipes)
   (if end
     [(tkn end :fg* (dec len))]
     [])
   [(tkn " ")]))

(def slinky-pipes-MZ (memoize slinky-pipes))

(defn indent
  [depth & {:keys [end]}]
  (slinky-pipes-MZ depth
                   :end end))

(defn breaker
  [f coll]
  (let [[head [delim & tail]] (split-with (complement f)
                                          coll)]
    (lazy-cat [head] (if (not-empty tail)
                        (breaker f tail)
                        []))))

(defn get-line-length
  [line]
  (def l' line)
  (->> line (map :length) (apply +)))

(defn buffer-lines-to-width
  [width column]
  (def w' width)
  (def c' column)
  (map (fn [line]
         (let [buf-length (->> line (map :length) (apply +) (- width))]
           (if (> buf-length 0)
             (conj (vec line) {:string (apply str (repeat buf-length " "))
                            :length buf-length})
             line)))
       column))

(defn mk-column-str
  [indent & cols]
  (def i' indent)
  (def c' cols)
  (let [lines (map (partial breaker :line-break) cols)
        max-height (->> lines
                        (map count)
                        (apply max))
        widths (map #(->> %
                          (map get-line-length)
                          (apply max)
                          inc)
                    lines)]
    (apply concat
           (apply interleave
                  indent
                  (conj (mapv buffer-lines-to-width
                              widths
                              lines)
                        (repeat [(tkn "\n")]))))))

(defn multi-line-indent2
  [& {:keys [cols indent-base]}]
  (->> cols
       (apply mk-column-str
              (repeat (indent indent-base)))))

(def multi-line-indent2-MZ  (memoize multi-line-indent2))

(defn get-line-meta
  [v & {:keys [path header?]}]
  (util/$- some-> (or (:src-pos v)
                      (:meta v))
           (select-keys [:line :column :file :end-line :end-column])
           (assoc :id (:id v)
                  :fn-name (or (:parent-name v)
                               (:name v))
                  :path path
                  :header header?)
           (update-in [:file] #(if (string? %)
                                 (util/get-src-file-path %)
                                 %))
           (assoc $
                  :line-meta? true)))

(defn indent-arg-map
  [tree m]
  (->> m
       (map (fn [[label value]]
              [(get-line-meta tree
                              :path [:arg-map label])
               (multi-line-indent2-MZ :cols [[(tkn [label " => "])] (tam/render-tokens value)]
                                      :indent-base (:depth tree))]))
       vec))

(defn selects-str [& args])

(defn throw-str [& args])

(defn return-str
  [tree & {pos :pos}]
  (when (contains? tree :return)
    (let [return (:return tree)]
      [(get-line-meta tree
                      :path [:return])
       (multi-line-indent2-MZ :cols [[(tkn [(condp = pos
                                               :before "returns"
                                               :after "returned")
                                            " => "])]
                                     (tam/render-tokens return)]
                              :indent-base (:depth tree))])))

(defn args-map-str
  [tree]
  (when-let [arg-map (:arg-map tree)]
    (indent-arg-map tree arg-map)))

(defn args-str
  [tree]
  (when-let [args (:args tree)]
    (->> args
         (map-indexed (fn [i value]
                        [(get-line-meta tree :path [:args i])
                         (multi-line-indent2-MZ :cols [(tam/render-tokens value)]
                                                :indent-base (:depth tree))]))
         vec)))

(defn let-binds-str
  [tree]
  (->> tree
       :let-binds
       (map-indexed (fn [i [val sym frm]]
                      [(get-line-meta tree :path [:let-binds i 0])
                       (multi-line-indent2-MZ :cols [[(tkn sym)
                                                      (tkn " <= ")]
                                                     (tam/render-tokens val)
                                                     [(tkn " <= ")]
                                                     (tam/render-tokens frm)]
                                              :indent-base (:depth tree))]))
       vec))

(defn args*-str
  [tree]
  (let [test #(-> tree % not-empty)]
    ((cond
       (test :arg-map) args-map-str
       (test :args) args-str
       (test :let-binds) let-binds-str
       :else (constantly (tkn "")))
     tree)))

(defn name->string
  [tree start?]
  (let [{:keys [depth name form ns parent-name macro? xpanded-frm]} tree]
    (if (nil? depth)
      []
      [(get-line-meta tree :header? true)
       (slinky-pipes-MZ depth :end (when start? "v"))
       (when (:throw tree)
         [(tkn "!" :fg 1 :bg 7) (tkn " ")])
       (if parent-name
         [(tkn (if-not (nil? form)
                      form
                      name)
                    :fg 0 :bg* (dec depth) :bold false)
          (when macro?
            (tkn [" => " (str xpanded-frm)]
                      :fg* (dec depth) :bg 0 :bold false))
          (tkn "  " (str parent-name)
                    :fg* (dec depth) :bg 0 :bold false)]
         (tkn name :fg* (dec depth) :bg 0 :bold false))
       (tkn "  ")
       (tkn (-> tree :id str)
                 :fg 7)])))

(defmacro when-sel
  [kw & body]
  `(when (~kw ~'view)
     [~@body]))

(defn tree->string*
  [tree]
  (if (nil? tree)
    []
    (let [view (*view* tree)
          trace-root? (-> tree meta :trace-root)
          visible? (and (not trace-root?)
                        view)
          has-children (some-> tree
                               :children
                               not-empty)]
      [(when visible?
         [(name->string tree true) (tkn "\n")
          (when-let [selects (:selects view)]
            (selects-str tree selects))
          (when-sel :args (args*-str tree))])
       (when has-children
         [(when-sel :return (return-str tree :pos :before))
          (when-sel :throw (throw-str tree))
          (mapv tree->string* (:children tree))
          (when (not trace-root?)
            [(name->string tree false) (tkn "\n")])
          (when-sel :args
            (args*-str tree))])
       (when visible?
         [(when-sel :return (return-str tree :pos :after))
          (when-sel :throw (throw-str tree))
          (get-line-meta tree) ;; clear meta
          (slinky-pipes-MZ (:depth tree)
                           :end "^")])
       (tkn "\n")])))

(defn increment-position
  [line-break? line column pos]
  (if line-break?
    [(inc line) 0 pos]
    [line column pos]))

(defn assoc-tokens-pos
  [tokens]
  (def t' tokens)
  (loop [[{:keys [length line-break line-meta?] :as head} & tail] tokens
         line-meta nil
         pos 0
         line 0
         col 0
         agg []]
    (cond (nil? head) agg
          line-meta? (recur tail head pos line col agg)
          :else (let [end-pos (+ pos length)
                      end-col (+ pos col)
                      [line' col' pos'] (increment-position line-break line end-col end-pos)]
                  (recur tail
                         (if line-break nil line-meta)
                         pos'
                         line'
                         col'
                         (util/$- -> head
                                  (merge line-meta)
                                  (assoc :line line
                                         :start-col col
                                         :end-col end-col
                                         :start pos
                                         :end end-pos)
                                  (conj agg $)))))))

(defn remove-nil-vals
  [m]
  (->> m
       (remove #(-> % second nil?))
       (into {})))

(defn mk-text-props
  [{:keys [start end] :as token}]
  [start end (remove-nil-vals (dissoc token
                                      :coll?
                                      :column
                                      :end
                                      :end-col
                                      :end-column
                                      :end-line
                                      :length
                                      :line
                                      :line-meta?
                                      :line-break
                                      :start
                                      :start-col
                                      :start-column
                                      :start-line
                                      :string
                                      :zipper))])

(defn split-text-tag-coll
  [tokens]
  [(->> tokens (map :string) (apply str))
   (->> tokens
        assoc-tokens-pos
        (mapv mk-text-props))])


(defn tree->text-prop-pair
  [tree]
  (->> tree
       tree->string*
       flatten
       (remove nil?)
       split-text-tag-coll))

(defn audit-ns->summary-view
  [audit-ns]
  (let [[ns-sym audit-fns] audit-ns
        fn-count (count audit-fns)
        traced-count (->> audit-fns
                          (map second)
                          (map :trace-type)
                          (filter #{:fn :inner-fn})
                          count)]
    (tkn (format "  %s / %s  %s\n" traced-count fn-count ns-sym)
         :ns ns-sym)))

(defn audit-fn->view
  [[ fn-sym {:keys [trace-type trace-selection] :as  fn-meta}]]
  (apply tkn (format "  %s %s %s\n"
                     (case trace-selection
                       :fn "O"
                       :inner-fn "I"
                       :ns " "
                       nil "x"
                       :else "?")
                     (case trace-type
                       :fn "E"
                       :inner-fn "E"
                       nil "D"
                       :else "?")
                     fn-sym)
         (apply concat fn-meta)))

(defn audit-fn-group->view
  [[ns-sym audit-fns]]
  (concat [(tkn (format "- in ns %s\n" ns-sym))]
          (map audit-fn->view audit-fns)))

(defn audit->top-view
  [audit]
  (concat [(tkn "Traced namespaces:\n")]
          (map audit-ns->summary-view (:ns audit))
          [(tkn "\n\nTraced functions:\n")]
          (map audit-fn-group->view (:fn audit))))

(defn audit->ns-view
  [audit & [ns-sym]]
  (concat [(tkn (format "Namespace %s\n" ns-sym) :ns ns-sym)]
          (map audit-fn->view
               (-> audit :ns (get ns-sym)))
          [(tkn "\n\nTraced functions:\n")]
          (map audit-fn->view
               (-> audit :fn (get ns-sym)))))


(defn pprint-str [& args])

(defn tree->string [& args])
(defn print-trees [& args])

;; TODO ansi colors
(defn print-tree [tree]
  (->> tree
       tree->text-prop-pair
       first
       (apply str)
       println))

(defn print-tree-unlimited [& args])


(defn annotated->text-prop-pair
  [a]
  (->> a
       flatten
       (remove nil?)
       split-text-tag-coll))