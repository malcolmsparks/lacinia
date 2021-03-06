(ns com.walmartlabs.lacinia-test
  (:require [clojure.spec.test :as stest]
            [clojure.spec :as s]
            [clojure.test :refer [deftest is use-fixtures testing]]
            [clojure.data.json :as json]
            [com.walmartlabs.lacinia :refer [execute]]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.test-schema :refer [test-schema]]
            [com.walmartlabs.test-utils :refer [is-thrown instrument-schema-namespace]]))

(instrument-schema-namespace)

(def ^:dynamic *schema* nil)

(use-fixtures :once
              (fn [f]
                (binding [*schema* (schema/compile test-schema)]
                  (f))))

;; —————————————————————————————————————————————————————————————————————————————
;; ## Tests

(deftest simple-query
  ;; Standard query with explicit `query'
  (let [q "query heroNameQuery { hero { id name } }"]
    (is (= {:data {:hero {:id "2001" :name "R2-D2"}}}
           (execute *schema* q {} nil))))
  (let [q "query { hero { id name } }"]
    (is (= {:data {:hero {:id "2001" :name "R2-D2"}}}
           (execute *schema* q {} nil))))
  ;; We can omit the `query' piece if it's the only selection
  (let [q "{ hero { id name appears_in } }"]
    (is (= {:data {:hero {:id "2001"
                          :name "R2-D2"
                          :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]}}}
           (execute *schema* q {} nil)))
    (is (= (json/write-str (execute *schema* q {} nil))
           "{\"data\":{\"hero\":{\"id\":\"2001\",\"name\":\"R2-D2\",\"appears_in\":[\"NEWHOPE\",\"EMPIRE\",\"JEDI\"]}}}")))
  ;; Reordering fields should change response ordering
  (let [q "{ hero { name appears_in id }}"]
    (is (= (json/write-str (execute *schema* q {} nil))
           "{\"data\":{\"hero\":{\"name\":\"R2-D2\",\"appears_in\":[\"NEWHOPE\",\"EMPIRE\",\"JEDI\"],\"id\":\"2001\"}}}")))
  (let [q "{ hero { appears_in name id }}"]
    (is (= (json/write-str (execute *schema* q {} nil))
           "{\"data\":{\"hero\":{\"appears_in\":[\"NEWHOPE\",\"EMPIRE\",\"JEDI\"],\"name\":\"R2-D2\",\"id\":\"2001\"}}}"))))

(deftest tagging-checks
  (let [q "{ human(id: \"1003\") { id, name }}"
        result (execute *schema* q nil nil)]
    (is (= "Leia Organa"
           (-> result :data :human :name)))
    (is (= :human
           (-> result :data :human schema/type-tag)))))

(deftest mutation-query
  (let [q "mutation ($from : String, $to: String) { changeHeroName(from: $from, to: $to) { name } }"]
    (is (= {:data {:changeHeroName {:name "Solo"}}}
           (execute *schema* q {:from "Han Solo"
                                       :to "Solo"}
                    nil)))))

(deftest null-value-mutation
  (letfn [(reset-value []
            (execute *schema*
                     "mutation { changeHeroHomePlanet (id: \"1003\", newHomePlanet: \"Alderaan\") { homePlanet } }"
                     {}
                     nil))]
    (testing "null literal"
      (let [q "mutation { changeHeroHomePlanet (id: \"1003\", newHomePlanet: null) { name homePlanet } }"]
        (is (= {:data {:changeHeroHomePlanet {:name "Leia Organa" :homePlanet nil}}}
               (execute *schema* q {} nil)))))
    (reset-value)
    (testing "null variable"
      (let [q "mutation ($id : String!, $newHomePlanet : String) { changeHeroHomePlanet (id: $id, newHomePlanet: $newHomePlanet) { name homePlanet } }"]
        (is (= {:data {:changeHeroHomePlanet {:name "Leia Organa" :homePlanet nil}}}
               (execute *schema* q {:id "1003" :newHomePlanet nil} nil)))))
    (reset-value)
    (testing "missing argument (as opposed to null argument value)"
      (let [q "mutation ($id: String!) { changeHeroHomePlanet (id: $id) { name homePlanet } }"]
        (is (= {:data {:changeHeroHomePlanet {:name "Leia Organa" :homePlanet "Alderaan"}}}
               (execute *schema* q {:id "1003"} nil)))))
    (testing "null list/object element values"
      (let [q "query { echoArgs (integerArray: [1 null 3], inputObject: {string: null}) { integerArray inputObject { string } } }"]
        (is (= {:data {:echoArgs {:integerArray [1 nil 3] :inputObject {:string nil}}}}
               (execute *schema* q {} nil)))))
    (testing "null list/object values become null-ish"
      (let [q "query { echoArgs (integerArray: null, inputObject: null) { integerArray inputObject { string } } }"]
        (is (= {:data {:echoArgs {:integerArray []
                                  :inputObject nil}}}
               (execute *schema* q {} nil)))))))

(deftest nested-query
  (let [q "query HeroNameAndFriendsQuery {
               hero {
                 id
                 name
                 friends {
                   name
                 }
               }
             }"]
    (is (= {:data {:hero {:id "2001"
                          :name "R2-D2"
                          :friends [{:name "Luke Skywalker"}
                                    {:name "Han Solo"}
                                    {:name "Leia Organa"}]}}}
           (execute *schema* q {} nil))))
  (let [q "query HeroNameAndFriendsQuery {
               hero {
                 id
                 name
                 friends {
                   name
                   id
                 }
               }
             }"]
    (is (= {:data {:hero {:id "2001"
                          :name "R2-D2"
                          :friends [{:name "Luke Skywalker" :id "1000"}
                                    {:name "Han Solo" :id "1002"}
                                    {:name "Leia Organa" :id "1003"}]}}}
           (execute *schema* q {} nil)))))

(deftest recursive-query
  (let [q "query NestedQuery {
             hero {
               name
               friends {
                 name
                 appears_in
                 friends {
                   name
                 }
               }
             }
            }"]
    (is (= {:data {:hero {:name "R2-D2"
                          :friends [{:name "Luke Skywalker"
                                     :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]
                                     :friends [{:name "Han Solo"}
                                               {:name "Leia Organa"}
                                               {:name "C-3PO"}
                                               {:name "R2-D2"}]}
                                    {:name "Han Solo"
                                     :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]
                                     :friends [{:name "Luke Skywalker"}
                                               {:name "Leia Organa"}
                                               {:name "R2-D2"}]}
                                    {:name "Leia Organa"
                                     :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]
                                     :friends [{:name "Luke Skywalker"}
                                               {:name "Han Solo"}
                                               {:name "C-3PO"}
                                               {:name "R2-D2"}]}]}}}
           (execute *schema* q {} nil)))))

(deftest arguments-query
  (let [q "query FetchLukeQuery {
             human(id: \"1000\") {
               name
             }
           }"]
    (is (= {:data {:human {:name "Luke Skywalker"}}}
           (execute *schema* q nil nil))))
  (let [q "query FetchDarkSideQuery {
             human(id: \"1004\") {
               name
               friends {
                 name
               }
             }
           }"]
    (is (= {:data {:human {:name "Wilhuff Tarkin"
                           :friends [{:name "Darth Vader"}]}}}
           (execute *schema* q nil nil))))
  (let [q "mutation { addHeroEpisodes(id: \"1004\", episodes: []) { name appears_in } }"]
    (is (= {:data {:addHeroEpisodes {:name "Wilhuff Tarkin" :appears_in ["NEWHOPE"]}}}
           (execute *schema* q nil nil)))))

(deftest enum-query
  (let [q "mutation { addHeroEpisodes(id: \"1004\", episodes: [JEDI]) { name appears_in } }"]
    (is (= {:data {:addHeroEpisodes {:name "Wilhuff Tarkin" :appears_in ["NEWHOPE" "JEDI"]}}}
           (execute *schema* q nil nil)))))

(deftest not-found-query
  (let [q "{droid(id: \"non-existent\") {name friends{name}}}"]
    (is (= {:data {:droid nil}}
           (execute *schema* q nil nil)))))

(deftest variable-query
  (let [q "query FetchSomeIDQuery($someId: String!) {
             human(id: $someId) {
               name
             }
           }"
        luke-id "1000"
        han-id "1002"]
    (is (= {:data {:human {:name "Luke Skywalker"}}}
           (execute *schema* q {:someId luke-id} nil)))
    (is (= {:data {:human {:name "Han Solo"}}}
           (execute *schema* q {:someId han-id} nil)))
    (is (= {:errors [{:message "No value was provided for variable `someId', which is non-nullable."
                      :variable-name :someId}]}
           (execute *schema* q {} nil)))))

(deftest aliased-query
  (let [q "query FetchLukeAliased {
             luke: human(id: \"1000\") {
               name
             }
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"}}}
           (execute *schema* q nil nil)))))

(deftest double-aliased-query
  (let [q "query FetchLukeAndLeiaAliased {
             luke: human(id: \"1000\") {
               name
             }
             leia: human(id: \"1003\") {
               name
             }
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"}
                   :leia {:name "Leia Organa"}}}
           (execute *schema* q nil nil)))))

(deftest aliases-on-nested-fields
  (let [q "query { human (id: \"1000\") { buddies: friends { handle: name }}}"
        query-result (execute *schema* q nil nil)]
    (is (= {:data {:human {:buddies [{:handle "Han Solo"}
                                     {:handle "Leia Organa"}
                                     {:handle "C-3PO"}
                                     {:handle "R2-D2"}]}}}
           query-result))))

(deftest duplicated-query
  (let [q "query DuplicateFields {
             luke: human(id: \"1000\") {
               name
               homePlanet
             }
             leia: human(id: \"1003\") {
               name
               homePlanet
             }
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"
                          :homePlanet "Tatooine"}
                   :leia {:name "Leia Organa"
                          :homePlanet "Alderaan"}}}
           (execute *schema* q nil nil)))))

(deftest fragmented-query
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               ...HumanFragment
             }
             leia: human(id: \"1003\") {
               ...HumanFragment
             }
           }
           fragment HumanFragment on human {
             name
             homePlanet
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"
                          :homePlanet "Tatooine"}
                   :leia {:name "Leia Organa"
                          :homePlanet "Alderaan"}}}
           (execute *schema* q nil nil))))
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               ... on human {
                 name
                 homePlanet
               }
             }
             leia: human(id: \"1003\") {
               ... on human {
                 name
                 homePlanet
               }
             }
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"
                          :homePlanet "Tatooine"}
                   :leia {:name "Leia Organa"
                          :homePlanet "Alderaan"}}}
           (execute *schema* q nil nil))))
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               id
               ... on human {
                 name
                 homePlanet
               }
             }
             leia: human(id: \"1003\") {
               ... on human {
                 name
                 homePlanet
                 ...appearsInFragment
               }
             }
           }
           fragment appearsInFragment on human {
             appears_in
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"
                          :id "1000"
                          :homePlanet "Tatooine"}
                   :leia {:name "Leia Organa"
                          :homePlanet "Alderaan"
                          :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]}}}
           (execute *schema* q nil nil))))
  (let [q "query UseFragment {
             luke: human(id: \"1000\") {
               ...appearsInFragment
               ... on human {
                 name
                 homePlanet
               }
             }
             leia: human(id: \"1003\") {
               ...appearsInFragment
             }
           }
           fragment appearsInFragment on human {
             appears_in
           }"]
    (is (= {:data {:luke {:name "Luke Skywalker"
                          :homePlanet "Tatooine"
                          :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]}
                   :leia {:appears_in ["NEWHOPE" "EMPIRE" "JEDI"]}}}
           (execute *schema* q nil nil)))))

(deftest invalid-query
  (let [q "{ human(id: \"1000\") { name "
        {:keys [errors data]} (execute *schema* q nil nil)]
    (is (empty? data))
    (is (= 1 (count errors)))
    (let [err (-> errors first :message)]
      (is (.contains err "Failed to parse GraphQL query")))))

(deftest error-query
  (let [q "{ human(id: \"1000\") { error_field }}"
        executed (execute *schema* q nil nil)]
    (is (= {:errors [{:query-path [:human :error_field]
                      :message "Exception in error_field resolver."
                      :locations [{:line 1
                                   :column 20}]}]
            :data {:human {:error_field nil}}}
           executed)))
  (let [q "{ human(id: \"1000\") { multiple_errors_field }}"
        executed (execute *schema* q nil nil)]
    (is (= {:human {:multiple_errors_field "Value"}}
           (:data executed)))

    ;; There aren't guarantees on the order of the errors.
    (is (= [{:query-path [:human :multiple_errors_field]
             :message "1"
             :locations [{:line 1
                          :column 20}]
             :other-key 100}
            {:query-path [:human :multiple_errors_field]
             :message "2"
             :locations [{:line 1
                          :column 20}]}
            {:query-path [:human :multiple_errors_field]
             :message "3"
             :locations [{:line 1
                          :column 20}]}
            {:query-path [:human :multiple_errors_field]
             :message "4"
             :locations [{:line 1
                          :column 20}]}]
           (->> executed :errors (sort-by :message)))))

  (let [q "{ human(id: \"1000\") { error_field multiple_errors_field }}"
        executed (execute *schema* q nil nil)]
    (is (= {:human {:error_field nil
                    :multiple_errors_field "Value"}}
           (:data executed)))
    (is (= [
            {:query-path [:human :multiple_errors_field]
             :message "1"
             :locations [{:line 1
                          :column 20}]
             :other-key 100}
            {:query-path [:human :multiple_errors_field]
             :message "2"
             :locations [{:line 1
                          :column 20}]}
            {:query-path [:human :multiple_errors_field]
             :message "3"
             :locations [{:line 1
                          :column 20}]}
            {:query-path [:human :multiple_errors_field]
             :message "4"
             :locations [{:line 1
                          :column 20}]}
            {:query-path [:human :error_field]
             :message "Exception in error_field resolver."
             :locations [{:line 1, :column 20}]}]

           (->> executed :errors (sort-by :message)))))

  (let [q "{ hero { id grumble }}"
        executed (execute *schema* q nil nil)]
    (is (= {:errors [{:message "Cannot query field `grumble' on type `character'."
                      :query-path [:hero]
                      :field :grumble
                      :locations [{:column 7
                                   :line 1}]
                      :type :character}]}
           executed)))
  (let [q "{ grumble { id name }}"
        executed (execute *schema* q nil nil)]
    (is (= {:errors [{:field :grumble
                      :locations [{:column 0
                                   :line 1}]
                      :message "Cannot query field `grumble' on type `QueryRoot'."
                      :query-path []
                      :type :QueryRoot}]}
           executed)))
  (let [q "mutation { grumble { id name }}"
        executed (execute *schema* q nil nil)]
    (is (= {:errors [{:field :grumble
                      :locations [{:column 9
                                   :line 1}]
                      :message "Cannot query field `grumble' on type `MutationRoot'."
                      :query-path []
                      :type :MutationRoot}]}
           executed))))

(deftest resolve-callback-failures
  (let [q "{ droid(id: \"2001\") { accessories }}"
        executed (execute *schema* q nil nil)]
    (is (= {:query-path [:droid :accessories]
            :message "Field resolver returned a single value, expected a collection of values."
            :locations [{:line 1
                         :column 20}]}
           (-> executed :errors first))))
  (let [q "{droid(id: \"2000\") { incept_date }}"
        executed (execute *schema* q nil nil)]
    (is (= {:query-path [:droid :incept_date]
            :message "Field resolver returned a collection of values, expected only a single value."
            :locations [{:line 1
                         :column 19}]}
           (-> executed :errors first)))))

(deftest int-coercion
  (let [overflow (reduce * (repeat 20 (bigint 1000)))
        schema {:objects
                {:tester
                 {:fields {:int {:type 'Int}
                           :string_int {:type 'Int}
                           :float_int {:type 'Int}
                           :string {:type 'String}
                           :bool {:type 'Boolean}
                           :float {:type 'Float}
                           :string_float {:type 'Float}
                           :id {:type 'ID}}}}

                :queries
                {:test {:type :tester
                        :resolve (fn [& _]
                                   {:int overflow
                                    :string-int "20"
                                    :float-int 3.0
                                    :string 400
                                    :bool "false"
                                    :float 4
                                    :string-float "3.0"
                                    :id 500})}}}
        compiled-schema (schema/compile schema)
        q "{ test { int string bool float id string_int float_int string_float}}"
        query-result (execute compiled-schema q nil nil)]
    (is (= clojure.lang.BigInt
           (class overflow)))
    (is (= java.lang.Integer
           (class (get-in query-result [:data :test :float_int]))))
    (is (= java.lang.Integer
           (class (get-in query-result [:data :test :string_int]))))
    (is (= java.lang.Integer
           (class (get-in query-result [:data :test :int]))))
    (is (= java.lang.Double
           (class (get-in query-result [:data :test :float]))))
    (is (= java.lang.Double
           (class (get-in query-result [:data :test :string_float]))))
    (is (= java.lang.String
           (class (get-in query-result [:data :test :string]))))
    (is (= java.lang.String
           (class (get-in query-result [:data :test :id]))))
    (is (= java.lang.Boolean
           (class (get-in query-result [:data :test :bool]))))))

(deftest non-nullable
  ;; human's type is (non-null :character), but is null because the id does not exist.
  ;; This triggers the non-nullable field error.
  (let [q "{ human(id: \"12345678\") { name }}"
        executed (execute *schema* q nil nil)]
    (is (= {:data {:human nil}
            :errors [{:arguments {:id "12345678"}
                      :locations [{:column 0
                                   :line 1}]
                      :message "Non-nullable field was null."
                      :query-path [:human]}]}
           executed)))
  (let [q "{ human(id: \"1000\") { name foo }}"
        executed (execute *schema* q nil nil)]
    (is (= {:data {:human {:name "Luke Skywalker" :foo nil}}
            :errors [{:message "Non-nullable field was null."
                      :query-path [:human :foo]
                      :locations [{:line 1
                                   :column 20}]}]}
           executed)))
  (testing "field declared as non-nullable resolved to null"
    (let [q "{ hero { foo }}"
          executed (execute *schema* q nil nil)]
      (is (= {:data {:hero nil}
              :errors [{:message "Non-nullable field was null."
                        :query-path [:hero :foo]
                        :locations [{:line 1
                                     :column 7}]}]}
             executed)
          "should null the top level when non-nullable field returns null")))
  (testing "field declared as non-nullable resolved to null"
    (let [q "{ hero { arch_enemy { foo } }}"
          executed (execute *schema* q nil nil)]
      (is (= {:data {:hero nil}
              :errors [{:message "Non-nullable field was null."
                        :query-path [:hero :arch_enemy]
                        :locations [{:line 1
                                     :column 7}]}]}
             executed)
          "nulls the first nullable object after a field returns null in a chain of fields that are non-null")))
  (testing "nullable list of nullable objects (friends) with non-nullable selections"
    (let [q "{ hero { friends { arch_enemy { foo } } }}"
          executed (execute *schema* q nil nil)]
      (is (= {:data {:hero {:friends [nil nil nil]}}
              :errors [{:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 17}],
                        :query-path [:hero :friends :arch_enemy]}
                       {:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 17}],
                        :query-path [:hero :friends :arch_enemy]}
                       {:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 17}],
                        :query-path [:hero :friends :arch_enemy]}]}
             executed)
          "nulls the first nullable object after a non-nullable field returns null")))
  (testing "nullable list of nullable objects (friends) with nullable selections containing non-nullable field"
    (let [q "{ hero { friends { best_friend { foo } } }}"
          executed (execute *schema* q nil nil)]
      (is (= {:data {:hero {:friends [{:best_friend nil} {:best_friend nil} {:best_friend nil}]}}
              :errors [{:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 31}],
                        :query-path [:hero :friends :best_friend :foo]}
                       {:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 31}],
                        :query-path [:hero :friends :best_friend :foo]}
                       {:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 31}],
                        :query-path [:hero :friends :best_friend :foo]}]}
             executed)
          "nulls the first nullable object after a non-nullable field returns null")))
  (testing "non-nullable list of nullable objects (family) with non-nullable selections"
    (let [q "{ hero { family { arch_enemy { foo } } } }"
          executed (execute *schema* q nil nil)]
      (is (= {:data {:hero {:family [nil nil nil]}}
              :errors [{:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 16}],
                        :query-path [:hero :family :arch_enemy]}
                       {:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 16}],
                        :query-path [:hero :family :arch_enemy]}
                       {:message "Non-nullable field was null.",
                        :locations [{:line 1, :column 16}],
                        :query-path [:hero :family :arch_enemy]}]}
             executed)
          "nulls the first nullable object after a non-nullable field returns null")))

  ;; TODO tests below fail because of types not being expanded correctly
  ;; look: schema/expand-types-in-field
  ;; they're not marked as multiple? and/or non-nullable? but they should be

  #_(testing "nullable list of non-nullable objects (enemies) with non-nullable selection"
      (let [q "{ hero { enemies { arch_enemy { foo } }}}"
            executed (execute *schema* q nil nil)]
        (is (= {:data {:hero {:enemies []}}
                :errors [{:message "Non-nullable field was null.",
                          :locations [{:line 1, :column 20}],
                          :query-path [:hero :enemies :arch_enemy :foo]}
                         {:message "Non-nullable field was null.",
                          :locations [{:line 1, :column 20}],
                          :query-path [:hero :enemies :arch_enemy :foo]}
                         {:message "Non-nullable field was null.",
                          :locations [{:line 1, :column 20}],
                          :query-path [:hero :enemies :arch_enemy :foo]}]}
               executed)
            "nulls the first nullable object after a non-nullable field returns null")))
  #_(testing "non-nullable list of non-nullable objects (droids) with non-nullable selections"
      (let [q "{ hero { droids { arch_enemy { foo } } } }"
            executed (execute *schema* q nil nil)]
        (is (= {:data {:hero nil}
                :errors [{:message "Non-nullable field was null.",
                          :locations [{:line 1, :column 20}],
                          :query-path [:hero :enemies :bar]}
                         {:message "Non-nullable field was null.",
                          :locations [{:line 1, :column 20}],
                          :query-path [:hero :enemies :bar]}
                         {:message "Non-nullable field was null.",
                          :locations [{:line 1, :column 20}],
                          :query-path [:hero :enemies :bar]}]}
               executed)
            "nulls everything"))))

(deftest custom-scalar-query
  (let [q "{ now { date }}"]
    (is (= {:data {:now {:date "A long time ago"}}}
           (execute *schema* q nil nil)))))

(deftest default-value-test
  (testing "Should use the default-value"
    (let [q "{ droid {
                 name
               }
             }"]
      (is (= {:data
              {:droid
               {:name "R2-D2"}}}
             (execute *schema* q nil nil)))
      (let [q "query UseFragment {
                 threecpo: droid(id: \"2000\") {
                   ...DroidFragment
                 }
                 r2d2: droid {
                   ...DroidFragment
                 }
               }
               fragment DroidFragment on droid {
                 name
                 friends {
                   name
                 }
                 appears_in
               }"]
        (is (= {:data {:threecpo {:name "C-3PO"
                                  :friends [{:name "Luke Skywalker"}
                                            {:name "Han Solo"}
                                            {:name "Leia Organa"}
                                            {:name "R2-D2"}]
                                  :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]}
                       :r2d2 {:name "R2-D2"
                              :friends [{:name "Luke Skywalker"}
                                        {:name "Han Solo"}
                                        {:name "Leia Organa"}]
                              :appears_in ["NEWHOPE" "EMPIRE" "JEDI"]}}}
               (execute *schema* q nil nil))))
      (testing "Should use the value provided by user"
        (let [q "{ droid(id: \"2000\") {
                     name
                   }
                 }"]
          (is (= {:data
                  {:droid
                   {:name "C-3PO"}}}
                 (execute *schema* q nil nil)))))
      (testing "Mutation should use default-value"
        (let [q "mutation ($from : String!) { changeHeroName(from: $from) { name } }"]
          (is (= {:data {:changeHeroName {:name "Rey"}}}
                 (execute *schema* q {:from "Han Solo"}
                          nil)))))
      (testing "Should use the default-value for non-nullable fields"
        (let [q "query UseFragment {
                   vader: human {
                     name
                   }
                 }"]
          (is (= {:data {:vader {:name "Darth Vader"}}}
                 (execute *schema* q nil nil))))))))

(deftest not-allow-not-nullable-with-default-value
  (let [schema-non-nullable-with-defaults
        {:objects
         {:person
          {:fields
           {:id {:type '(non-null String)
                 :default-value "0001"}}}}}]
    (is-thrown [e (schema/compile schema-non-nullable-with-defaults)]
      (is (= (.getMessage e) "Field `id' of type `person' is both non-nullable and has a default value.")))))

(deftest custom-scalars
  (testing "custom scalars defined as conformers"
    (let [parse-conformer (s/conformer
                           (fn [x]
                             (if (and
                                  (string? x)
                                  (< (count x) 3))
                               x
                               :clojure.spec/invalid)))
          serialize-conformer (s/conformer
                               (fn [x]
                                 (case x
                                   "200" "OK"
                                   "500" "ERROR"
                                   :clojure.spec/invalid)))]
      (testing "custom scalar's serializing option"
        (let [schema (schema/compile {:scalars
                                      {:Event {:parse parse-conformer
                                               :serialize serialize-conformer}}

                                      :objects
                                      {:galaxy-event
                                       {:fields {:lookup {:type :Event}}}}

                                      :queries
                                      {:events {:type :galaxy-event
                                                :resolve (fn [ctx args v]
                                                           {:lookup "200"})}}})
              q "{ events { lookup }}"]
          (is (= {:data {:events {:lookup "OK"}}} (execute schema q nil nil))
              "should return conformed value")))
      (testing "custom scalar's invalid value"
        (let [schema (schema/compile {:scalars
                                      {:Event {:parse parse-conformer
                                               :serialize serialize-conformer}
                                       :Id {:parse parse-conformer
                                            :serialize (s/conformer str)}}

                                      :objects
                                      {:galaxy-event
                                       {:fields {:lookup {:type :Event}}}
                                       :human
                                       {:fields {:id {:type :Id}
                                                 :name {:type 'String}}}}

                                      :queries
                                      {:events {:type :galaxy-event
                                                :resolve (fn [ctx args v]
                                                           ;; type of :lookup is :Event
                                                           ;; that is a custom scalar with
                                                           ;; a serialize function that
                                                           ;; deems anything other than
                                                           ;; "200" or "500" invalid.
                                                           ;; So value 1 should cause
                                                           ;; an error.
                                                           {:lookup 1})}
                                       :human {:type '(non-null :human)
                                               :args {:id {:type :Id}}
                                               :resolve (fn [ctx args v]
                                                          {:id "1000"
                                                           :name "Luke Skywalker"})}}})
              q1 "{ human(id: \"1003\") { id, name }}"
              q2 "{ events { lookup }}"]
          (is (= {:errors [{:argument :id
                            :field :human
                            :locations [{:column 0
                                         :line 1}]
                            :message "Exception applying arguments to field `human': For argument `id', scalar value is not parsable as type `Id'."
                            :query-path []
                            :type-name :Id
                            :value "1003"}]}
                 (execute schema q1 nil nil))
              "should return error message")
          (is (= {:data nil
                  :errors
                   [{:message
                     "Error resolving field: Invalid value for a scalar type."
                     :type :Event
                     :value 1
                     :locations [{:line 1
                                  :column 9}]
                     :query-path [:events :lookup]}]}
                 (execute schema q2 nil nil))
              "should return error message"))))))


