 (ns topham.test.core
  (:require [inquery.core :as q]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.test :refer :all]
            [clojure.string :as s]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [topham.core :as t])
  (:import java.time.Instant
           java.time.LocalDate
           java.util.Date
           java.util.UUID))

(def dims
  {:dims       [:galaxy :star :planet :moon :asteroid]
   :required   [:mission-type]
   :non-dims   [:ship :payload :mission-type]})

(defn snake->kebab [k]
  (when k
    (clojure.string/replace (-> k name s/lower-case) "_" "-")))

(def ds (jdbc/get-datasource {:dbtype "h2:mem"
                              :dbname "topham_test"
                              :shutdown true}))


(defn make-universe! []
  (jdbc/execute! ds ["
    create table starship_missions (
      id identity primary key,
      galaxy       varchar,
      star         varchar,
      planet       varchar,
      moon         varchar,
      asteroid     varchar,
      mission_type varchar not null,
      ship         varchar not null,
      payload      clob,
      topham       int not null
    );"])

  (let [insert! (fn [row]
                  (let [sql (t/make-insert-row dims :starship_missions row)]
                    (jdbc/execute! ds [sql])))

        missions
        ;; === canonical full matches for base tests ===
        [{:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine" :moon "Jedha" :asteroid "Polis Massa" :ship "Millennium Falcon"} ; 11111
         {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine" :moon "Jedha" :ship "Slave I"}             ; 11110

         ;; === partial matches for fallback resolution ===
         {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine" :ship "X-Wing"}         ; 11100
         {:mission-type "Patrol" :galaxy "Outer Rim" :planet "Tatooine" :ship "Starfighter"}                    ; 10100
         {:mission-type "Recon"  :galaxy "Core"      :star "Alderaan" :moon "Endor" :ship "Ghost"}             ; 11010
         {:mission-type "Patrol" :ship "Y-Wing"}                                                                ; 00000

         ;; === tie-breaker group: exact same topham ===
         {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Scarif" :ship "Interceptor"}      ; 11100
         {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Scarif" :ship "TIE Fighter"}      ; 11100

         ;; === specificity test: hoth rows ===
         {:mission-type "Patrol" :planet "Hoth" :ship "Probe Droid"}                             ; 00100 = 4
         {:mission-type "Patrol" :planet "Hoth" :moon "Echo Base" :ship "Snowspeeder"}           ; 00110 = 6
         {:mission-type "Patrol" :planet "Hoth" :moon "Echo Base" :star "Ilum" :ship "Tauntaun"} ; 01110 = 14

         ;; === fallback test rows ===
         {:mission-type "Patrol" :galaxy "Unknown" :ship "Mysterious Shuttle"}            ; 10000
         {:mission-type "Patrol" :ship "Truly Generic"}]]                                 ; 00000

    (doseq [row missions]
      (insert! row))))


(defn unmake-universe! []
  (jdbc/execute! ds ["drop table starship_missions"]))

(use-fixtures :each (fn [f]
                      (make-universe!)
                      (f)
                      (unmake-universe!)))

(deftest test-universe-state
  "ensures the universe was seeded correctly: ship count, uniqueness, and correct topham values"
  (let [rows (jdbc/execute! ds
                            ["select ship, topham from starship_missions order by id"]
                            {:builder-fn rs/as-unqualified-modified-maps
                             :label-fn snake->kebab})
        ships (map :ship rows)
        expected-ships #{"Millennium Falcon" "Slave I" "X-Wing" "Starfighter"
                         "Ghost" "Y-Wing" "Interceptor" "TIE Fighter"
                         "Probe Droid" "Snowspeeder" "Tauntaun"
                         "Mysterious Shuttle" "Truly Generic"}
        expected-tophams {"Millennium Falcon" 31   ;; 11111
                          "Slave I"           30   ;; 11110
                          "X-Wing"            28   ;; 11100
                          "Starfighter"       20   ;; 10100
                          "Ghost"             26   ;; 11010
                          "Y-Wing"             0   ;; 00000
                          "Interceptor"       28
                          "TIE Fighter"       28
                          "Probe Droid"        4   ;; 00100
                          "Snowspeeder"        6   ;; 00110
                          "Tauntaun"          14   ;; 01110
                          "Mysterious Shuttle"16   ;; 10000
                          "Truly Generic"      0}]

    (is (= (count expected-ships) (count rows)))
    (is (= expected-ships (set ships)))
    (doseq [{:keys [ship topham]} rows]
      (is (= topham (expected-tophams ship))
          (str "incorrect topham for: " ship)))))

(deftest test-insert-sparse-row
  "inserts a sparse row and verifies correct topham"
  (let [sparse-row {:mission-type "Patrol"
                    :planet "Dagobah"
                    :ship "Swamp Crawler"} ; only 1 dim
        insert-sql (t/make-insert-row dims :starship_missions sparse-row)]
    (jdbc/execute! ds [insert-sql])

    (let [{:keys [ship topham]} (jdbc/execute-one! ds
                                                   ["select ship, topham from starship_missions where ship = 'Swamp Crawler'"]
                                                   {:builder-fn rs/as-unqualified-modified-maps
                                                    :label-fn snake->kebab})]
      (is (= "Swamp Crawler" ship))
      (is (= 4 topham))))) ;; 00100 = 4 (only :planet set)

(deftest test-update-recomputes-topham
  "updates a seeded ship's dimensions and verifies correct topham recomputation"
  (let [{:keys [id]} (jdbc/execute-one! ds
                                        ["select id from starship_missions where ship = 'Millennium Falcon'"]
                                        {:builder-fn rs/as-unqualified-modified-maps
                                         :label-fn snake->kebab})

        to {:id id
            :galaxy "core"
            :star "alderaan"
            :planet nil
            :moon nil
            :asteroid nil
            :mission-type "recon"
            :ship "ffalcon"}        ;; changing both dims and a non-dim

        update-sql (t/make-single-update dims
                                         :starship_missions
                                         to)]

    (jdbc/execute! ds [update-sql])

    (let [{:keys [ship mission-type galaxy star topham]} (jdbc/execute-one! ds
                                                                            [(str "select * from starship_missions where id = " id)]
                                                                            {:builder-fn rs/as-unqualified-modified-maps
                                                                             :label-fn snake->kebab})]

      (is (= "ffalcon" ship))
      (is (= "recon" mission-type))
      (is (= "core" galaxy))
      (is (= "alderaan" star))
      (is (= 24 topham)))))          ;; 11000

(deftest test-update-fails-on-incomplete-dim-update
  "should fail when trying to update some but not all dimensions"
  (let [{:keys [id]} (jdbc/execute-one! ds
                                        ["select id from starship_missions where ship = 'Slave I'"]
                                        {:builder-fn rs/as-unqualified-modified-maps
                                         :label-fn snake->kebab})
        bad-update {:id id
                    :galaxy "unknown"
                    :ship "won't make it"}] ;; touches 1 dim, but not all

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"to update any dimension.*all.*so topham can be recomputed"
          (t/make-single-update dims :starship_missions bad-update)))))

(deftest test-best-match-exact
  "returns fully matching row when all dims are present"
  (let [query {:galaxy "Outer Rim"
               :star "Tatoo I"
               :planet "Tatooine"
               :moon "Jedha"
               :asteroid "Polis Massa"
               :mission-type "Patrol"}
        sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :topham]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]

    (is (= "Millennium Falcon" (:ship result)))
    (is (= 31 (:topham result))))) ;; 11111

(deftest test-best-match-prefers-higher-topham
  "returns more specific row among multiple matches"
  (let [query {:planet "Scarif"
               :mission-type "Patrol"}
        sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :topham]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]

    (is (= "Y-Wing" (:ship result)))
    (is (= 0        (:topham result)))))

(deftest test-best-match-ignores-nonmatching-required
  "ensures required dimension mismatch excludes row"
  (let [query {:galaxy "Outer Rim"
               :planet "Tatooine"
               :mission-type "Recon"} ;; no matching rows for Recon
        sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :topham]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]

    (is (nil? result)))) ;; no "Recon" mission for those dims

(deftest test-best-match-fallback-to-wildcard
  "falls back to lowest specificity match when no other rows qualify"
  (let [query {:galaxy "Completely Unknown"
               :star "Ghost Sun"
               :planet "Lost World"
               :moon "Crater 9"
               :asteroid "ZZ-Alpha"
               :mission-type "Patrol"} ;; required
        sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :topham]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]

    (is (= "Y-Wing" (:ship result)))
    (is (= 0 (:topham result))))) ;; 00000


(deftest test-best-match-breaks-tie-deterministically
  "when two rows have identical topham, returns the one inserted first (or consistent order)"
  (let [query {:galaxy "Outer Rim"
               :star "Tatoo I"
               :planet "Scarif"
               :mission-type "Patrol"}
        sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :topham]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]

    ;; assuming insertion order gives interceptor before tie fighter
    (is (= "Interceptor" (:ship result)))
    (is (= 28 (:topham result)))))

(deftest test-best-match-selects-most-specific-hoth
  "among several Hoth matches, returns the row with most specificity (highest topham)"
  (let [query {:planet "Hoth"
               :moon "Echo Base"
               :mission-type "Patrol"}
        sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :topham]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]

    (is (= "Snowspeeder" (:ship result)))
    (is (= 6 (:topham result))))) ;; 00110
