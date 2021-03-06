(ns com.walmartlabs.lacinia.util
  "Useful utility functions."
  (:require clojure.walk))

(defn attach-resolvers
  "Given a GraphQL schema and a map of keywords to resolver fns, replace
  each placeholder keyword in the schema with the actual resolver fn."
  [schema resolver-m]
  (clojure.walk/postwalk
   (fn [node]
     (if (and (sequential? node) (= :resolve (first node)))
       (let [resolver-k (second node)
             resolver (if (keyword? resolver-k)
                        (get resolver-m resolver-k)
                        (get resolver-m (first resolver-k)))]
         (cond
           (nil? resolver)
           (throw (ex-info "Resolver specified in schema not provided."
                           {:requested-resolver resolver-k
                            :provided-resolvers (keys resolver-m)}))

           (keyword? resolver-k)
           [:resolve resolver]

           :else
           ;; If resolver-k is not a keyword, it must be a sequence,
           ;; in which first element is a key that points to a resolver
           ;; factory in resolver-m and subsequent elements are arguments
           ;; for the given factory.
           [:resolve (apply resolver (rest resolver-k))]))
       node))
   schema))


(defn attach-scalar-transformers
  "Given a GraphQL schema and a map of keywords to scalar transform
  maps containing :parse and/or :serialize keys pointing to functions,
  replace each placeholder keyword in the schema with the actual
  function."
  [schema transform-m]
  (assoc schema :scalars
         (reduce-kv (fn [schema' k v]
                      (assoc schema'
                             k
                             (-> v
                                 (update :parse #(get transform-m % %))
                                 (update :serialize #(get transform-m % %)))))
                    {}
                    (:scalars schema))))
