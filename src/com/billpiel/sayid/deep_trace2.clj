(ns com.billpiel.sayid.deep-trace2
  (require [com.billpiel.sayid.util.other :as util]
           [com.billpiel.sayid.trace :as trace]))

;; TODO exceptions

(def trace-fn-set #{`tr-if-ret `tr-if-clause `tr-macro})

(defn form->xform-map*
  [form]
  (if (seq? form)
    (let [x (macroexpand form)
          xx (clojure.walk/macroexpand-all form)] ;; TODO better way?
      (conj (mapcat form->xform-map* x)
            {form xx}))
    [[form form]]))

(defn form->xform-map
  [form]
  (apply merge (form->xform-map* form)))

(defn xform->form-map
  [form]
  (-> form
      form->xform-map
      clojure.set/map-invert))

(defn update-last
  [vctr f & args]
  (apply update-in
         vctr
         [(-> vctr
              count
              dec)]
         f
         args))

(defn path->sym
  [path]
  (->> path
       (clojure.string/join "_")
       (str "$")
       symbol))

(defn sym->path
  [sym]
  (util/$- -> sym
           name
           (subs 1)
           (clojure.string/split #"_")
           (remove #(= % "") $)
           (mapv #(Integer/parseInt %) $)))

(defn sym-seq->parent
  [syms]
  (-> syms
      first
      sym->path
      drop-last
      path->sym))

(defn swap-in-path-syms*
  [form func parent path skip-macro?]
  (cond
    (and skip-macro?
         (util/macro? form)) form
    (coll? form)  (util/back-into form
                                  (doall (map-indexed #(swap-in-path-syms* %2
                                                                           func
                                                                           form
                                                                           (conj path %)
                                                                           skip-macro?)
                                                      form)))
    :else (func (->> path
                     (clojure.string/join "_")
                     (str "$")
                     symbol)
                path
                form
                parent)))

(defn swap-in-path-syms
  ([form func]
   (swap-in-path-syms* form
                       func
                       nil
                       []
                       false))
  ([form]
   (swap-in-path-syms form
                       #(first %&))))

(defn swap-in-path-syms-skip-macro
  ([form]
   (swap-in-path-syms* form
                       #(first %&)
                       nil
                       []
                       true)))

(defn deep-replace-symbols
  [smap coll]
  (clojure.walk/postwalk #(if (symbol? %)
                            (or (get smap %)
                                %)
                            %)
                         coll))

(defn get-path->form-maps
  [src]
  (let [sx-seq (->> src
                    (tree-seq coll? seq)
                    (filter coll?))
        pair-fn (fn [form]
                  (interleave (seq  form)
                              (repeat form)))]
    (apply hash-map
           (mapcat pair-fn
                   sx-seq))))

;;  xl     (-> src clojure.walk/macroexpand-all swap-in-path-syms)
;;  src   src
;;  oloc (-> src swap-in-path-syms clojure.walk/macroexpand-all)
;;  x-form (clojure.walk/macroexpand-all src)

;;  xloc->oloc (deep-zipmap (-> src clojure.walk/macroexpand-all swap-in-path-syms) (-> src swap-in-path-syms clojure.walk/macroexpand-all))
;;  xl->src  (deep-zipmap (-> src clojure.walk/macroexpand-all swap-in-path-syms) (clojure.walk/macroexpand-all src))
;;  ol->olop (-> src swap-in-path-syms get-path->form-maps)
;;  xl->xlxp (-> src clojure.walk/macroexpand-all swap-in-path-syms get-path->form-maps)
;;  ol->olxp (-> src swap-in-path-syms clojure.walk/macroexpand-all get-path->form-maps)
;;  xl->xp (deep-zipmap (-> src clojure.walk/macroexpand-all swap-in-path-syms) (clojure.walk/macroexpand-all src))
;;  olop->op (deep-zipmap (swap-in-path-syms src) src)

(defn mk-expr-mapping
  [form]
  (let [xls (->> form
                 clojure.walk/macroexpand-all
                 swap-in-path-syms
                 (tree-seq coll? seq))
        xloc->oloc (util/deep-zipmap (-> form clojure.walk/macroexpand-all swap-in-path-syms)
                                     (-> form swap-in-path-syms-skip-macro clojure.walk/macroexpand-all))
        oloc->xloc (clojure.set/map-invert xloc->oloc)
        xl->xform  (util/deep-zipmap (-> form clojure.walk/macroexpand-all swap-in-path-syms)
                                     (clojure.walk/macroexpand-all form))
        xform->form (xform->form-map form)
        ol->olop (-> form
                     swap-in-path-syms
                     get-path->form-maps)
        xl->xlxp (-> form
                     clojure.walk/macroexpand-all
                     swap-in-path-syms
                     get-path->form-maps)
        ol->olxp (-> form
                     swap-in-path-syms
                     clojure.walk/macroexpand-all
                     get-path->form-maps)
        xlxp->xp (util/deep-zipmap (-> form clojure.walk/macroexpand-all swap-in-path-syms)
                                   (clojure.walk/macroexpand-all form))
        olop->op (util/deep-zipmap (swap-in-path-syms form) form)
        f (fn [xl]
            {(if (coll? xl)
               (sym-seq->parent xl)
               xl)
             {:xl xl              ;; expanded location
              :orig (-> xl
                       xl->xform
                       xform->form)  ;; original symbol or value
              :x (-> xl
                     xl->xform)
              :xlxp (xl->xlxp xl) ;; expanded locations expanded parent
              :ol (xloc->oloc xl)
              :olop (-> xl
                        xloc->oloc
                        ol->olop)
              :xp  (-> xl
                       xl->xlxp
                       xlxp->xp)
              :op (-> xl
                      xloc->oloc
                      ol->olop
                      olop->op)
              :olxp (-> xl
                        xloc->oloc
                        ol->olxp)
              :xlop (-> xl
                        xloc->oloc
                        ol->olop
                        ((partial deep-replace-symbols oloc->xloc)))}})]
    (util/$- ->> xls
             (map f)
             (apply merge))))

(defn capture-fn
  [path [log src-map] f fn-meta]
  (fn [& args]
    (let [sub-src-map (-> path path->sym src-map)
          form (:orig sub-src-map)
          parent trace/*trace-log-parent*
          this (->  (trace/mk-tree :parent parent)
                    (assoc :name (if (seq? form) (first form) form)
                           :form form
                           :path' path
                           :parent-name "outer-func" ;; TODO outer func name
                           :ns nil                   ;;TODO
                           :args (vec args)          ;;TODO
                           :arg-map  (delay (zipmap (-> src-map
                                                        :xp
                                                        rest)
                                                    args)))
                    (update-in [:depth] #(+ % (count path))))]
      (let [value (binding [trace/*trace-log-parent* this]
                    (try
                      (apply f args)
                      (catch Throwable t
                        ;; TODO what's the best we can do here?
                        (throw t))))
            this' (assoc this
                         :return value)]
        (swap! log conj [path value :fn this'])
        value))))

(defn tr-macro
  [path [log] mcro v]
  (swap! log conj [path v :macro mcro])
  v)

(defn tr-if-ret
  [path [log] v]
  (swap! log conj [path v])
  v)

(defn tr-if-clause
  [path [log] test v]
  (swap! log conj [path v :if test])
  (let [test-path (-> path
                      drop-last
                      vec
                      (conj 1))]
    (swap! log conj [test-path test :if]))
  v)

(declare xpand*)

(defn xpand**
  [form path]
  (when-not (nil? form)
    (util/back-into form
                    (doall (map-indexed #(xpand* %2
                                                (conj path %))
                                        form)))))

(defn xpand-fn
  [head form path]
  (cons
   (list 'capture-fn path '$$ head {}) ;; TODO fn-meta
   (rest form)))

(defn xpand-macro
  [head form path]
  (list `tr-macro
        path
        '$$
        (keyword head)
        form))

(defn xpand-if
  [[_ test then else] path]
  (list `tr-if-ret
        path
        '$$
        (concat ['if test
                 (list `tr-if-clause
                       (conj path 2)
                       '$$
                       true
                       then)]
                (if-not (nil? else)
                  [(list `tr-if-clause
                         (conj path 3)
                         '$$
                         false
                         else)]
                  []))))

(defn xpand*
  [form & [path]]
  (if (seq? form)
    (let [[head] form
          path' (or path
                    [])]
      (cond (util/macro? head) (xpand-macro head
                                            (xpand* (macroexpand form) path)
                                            path')
            (= 'if head) (xpand-if (xpand** form path')
                                   path')
            (special-symbol? head) (xpand** form path')
            :else (xpand-fn head
                            (xpand** form path')
                            path')))
    form))

(defn xpand
  [form]
  (let [expr-map (mk-expr-mapping form)]
    `(let [~'$$ [(atom []) '~expr-map]
           ~'$return ~(xpand* form)]
       (record-trace-tree ~'$$)
       ~'$return)))


(def trace (atom nil))

(defn log->tree1
  [log src-map]
  (loop [[[path val & tags] & tail] log
         agg {}]
    (if path
      (let [path' (interleave (repeat :children) path) ;; TODO the map won't preserve order
            sub-map (-> path
                        path->sym
                        src-map)
            agg' (update-in agg path'
                            #(merge %
                                    {:form (:orig sub-map)
                                     :return val
                                     :path path
                                     :tags tags
                                     :src-map sub-map}))]
        (recur tail
               agg'))
      (-> agg
          (get nil)
          (assoc :children
                 (dissoc (:children agg)
                         nil))))))

(defn tree1->trace-tree
  [tree parent src-map]
  (let [{:keys [form return children tags path]} tree
        trace-tree (if (-> tags
                           first
                           #{:fn})
                     (second tags)
                     (assoc (trace/mk-tree :parent parent)
                            :name (if (seq? form) (first form) form)
                            :form form
                            :path' path
                            :parent-name "outer-func" ;; TODO outer func name
                            :ns nil                   ;;TODO
                            :args []                  ;;TODO
                            :arg-map {}               ;;TODO
                                        ; :src-map src-map
                            :started-at nil
                            :ended-at nil
                            :return return))]
    (assoc trace-tree
           :children
           (mapv #(-> %
                      second
                      (tree1->trace-tree trace-tree
                                         src-map))
                 children))))

(defn record-trace-tree
  [[log src-map]]
  (swap! (:children trace/*trace-log-parent*)
         conj
         (-> @log
             (log->tree1 src-map)
             (tree1->trace-tree trace/*trace-log-parent*
                                src-map))))

 #_ (-> (xpand '(let [a 1
                         b 2]
                     (if false
                       a
                       b)))
           eval)

#_ (binding [trace/*trace-log-parent* (trace/mk-tree :id-prefix "root" :parent {})]
     (def tree trace/*trace-log-parent*)
     (-> #spy/d (xpand '(let [a 1]
                   (-> a
                       inc
                       dec
                       nil?)))
         eval))

#_ (binding [trace/*trace-log-parent* (trace/mk-tree :id-prefix "root" :parent {})]
     (def tree trace/*trace-log-parent*)
     (-> #spy/d (xpand
                 '(if (= 1 2) 3 4))
         eval))


#_  (-> (xpand )
           eval)


#_ (do

     #spy/d (-> (xpand '(let [a 1
                         b 2]
                     (if false
                       a
                       b)))
           eval)

     (comment))
