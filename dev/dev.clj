(ns dev
  (:require [clojure.edn :as edn]
            [jsonista.core :as json]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [yang.lang :as y]
            [mount.core :as mount :refer [defstate]]
            [cprop.core :as c]
            [hikari-cp.core :as hikari]
            [inquery.core :as q]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]
            [topham.core :as t])
  (:import  [org.postgresql.util PGobject]))

(defn ->jsonb [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string x))))

(extend-protocol q/SqlParam
  org.postgresql.util.PGobject
  (to-sql-string [o]
    (str "'" (.getValue o) "'::" (.getType o))))

(def pool-config
  {:minimum-idle 2
   :maximum-pool-size 10
   :auto-commit true
   :connection-timeout 30000
   :idle-timeout 600000
   :max-lifetime 1800000
   :validation-timeout 5000
   :pool-name "topham-pool"})

(defn query
  [{:keys [datasource]} query]
  (try
    (->> (jdbc/execute! datasource [query]
                        {:builder-fn rs/as-unqualified-modified-maps
                         :label-fn csk/->kebab-case-keyword}))
    (catch Exception e
      (log/error e "could not run query:" query)
      (throw (ex-info "query execution failed"
                      {:query query
                       :error (.getMessage e)})))))

(defn close [{:keys [datasource]}]
  (when datasource
    (hikari/close-datasource datasource)))

(defn connect-to-postgres [{:keys [connection pool]}]
  (let [{:keys [host port database user password]} connection
        hikari-config (merge pool-config
                             (select-keys pool [:minimum-idle
                                                :maximum-pool-size
                                                :idle-timeout])
                             {:adapter "postgresql"
                              :server-name host
                              :port-number port
                              :database-name database
                              :username user
                              :password password})]
    (hikari/make-datasource hikari-config)))

(defn connect-to-h2 []
  (jdbc/get-datasource {:dbtype "h2:mem"
                        :dbname "topham_test"
                        :shutdown true}))



(def dims {:dims       [:galaxy :star :planet :moon :asteroid]
           :required   [:mission-type]
           :non-dims   [:ship :payload :mission-type]})

(defn snake->kebab [k]
  (when k
    (clojure.string/replace (-> k name s/lower-case) "_" "-")))

(defn connect-to-universe [db-type]
  (case db-type
    :postgres   (let [ds (connect-to-postgres (-> (c/load-config) :db))]
                  (jdbc/execute! ds ["drop table if exists starship_missions"])
                  (jdbc/execute! ds ["create table starship_missions (id           bigserial PRIMARY KEY,

                                                                      -- dimensional columns -----------------------------
                                                                      galaxy       text,
                                                                      star         text,
                                                                      planet       text,
                                                                      moon         text,
                                                                      asteroid     text,
                                                                      mission_type text     not null,          -- required

                                                                      -- other data --------------------------------------
                                                                      ship         text     not null,
                                                                      payload      jsonb    not null,    -- mission intel
                                                                      topham       integer  not null,    -- computed bit-mask

                                                                      -- bookkeeping -------------------------------------
                                                                      created_at   timestamptz not null default now(),
                                                                      updated_at   timestamptz not null default now());"])
                  ds)

    :h2         (let [ds (connect-to-h2)]
                  (jdbc/execute! ds ["drop table if exists starship_missions"])
                  (jdbc/execute! ds ["create table starship_missions (
                                      id identity primary key,
                                      galaxy       varchar,
                                      star         varchar,
                                      planet       varchar,
                                      moon         varchar,
                                      asteroid     varchar,
                                      mission_type varchar not null,
                                      ship         varchar not null,
                                      payload      clob,
                                      topham       int not null);"])
                  ds)))

(defn make-universe! [& {:keys [in]
                         :or   {in :h2}}]
  (let [ds (connect-to-universe in)
        insert! (fn [row]
                  (let [sql (t/make-insert-row dims :starship_missions row)]
                    (jdbc/execute! ds [sql])))
        payload (cond-> {:id "P42"
                         :objective "Monitor imperial activity"
                         :crew 2
                         :duration 7
                         :priority "medium"}
                  (= in :postgres) ->jsonb
                  (= in :h2)       json/write-value-as-string)

        missions [
                  {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine" :moon "Jedha" :asteroid "Polis Massa" :ship "Millennium Falcon" :payload "{}"}
                  {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine" :moon "Jedha" :ship "Slave I" :payload "{}"}

                  {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine" :ship "X-Wing" :payload "{}"}
                  {:mission-type "Patrol" :galaxy "Outer Rim" :planet "Tatooine" :ship "Starfighter" :payload "{}"}
                  {:mission-type "Recon"  :galaxy "Core"      :star "Alderaan" :moon "Endor" :ship "Ghost" :payload "{}"}
                  {:mission-type "Patrol" :ship "Y-Wing" :payload "{}"}

                  {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Scarif" :ship "Interceptor" :payload "{}"}
                  {:mission-type "Patrol" :galaxy "Outer Rim" :star "Tatoo I" :planet "Scarif" :ship "TIE Fighter" :payload "{}"}

                  {:mission-type "Patrol" :planet "Hoth" :ship "Probe Droid" :payload "{}"}
                  {:mission-type "Patrol" :planet "Hoth" :moon "Echo Base" :ship "Snowspeeder" :payload payload}
                  {:mission-type "Patrol" :planet "Hoth" :moon "Echo Base" :star "Ilum" :ship "Tauntaun" :payload "{}"}

                  {:mission-type "Patrol" :galaxy "Unknown" :ship "Mysterious Shuttle" :payload "{}"}
                  {:mission-type "Patrol" :ship "Truly Generic" :payload "{}"}
                  ]]

    (doseq [row missions]
      (insert! row))

    (println "universe created. starships ready to rock.")
    ds))

(defn int->binary-string [n length]
  (let [bin-str (Integer/toBinaryString n)
        padding (apply str (repeat (- length (count bin-str)) "0"))]
    (str padding bin-str)))

(defn show-missions [ds]
  (let [rows (jdbc/execute! ds
                            ["select * from starship_missions order by id"]
                            {:builder-fn rs/as-unqualified-modified-maps
                             :label-fn snake->kebab})
        dim-count (count (:dims dims))
        format-str (str "| %-4s | %-12s | %-10s | %-10s | %-10s | %-10s | %-12s | %-14s | %-18s | %-50s |\n")
        header (format format-str
                       "ID" "Galaxy" "Star" "Planet" "Moon" "Asteroid" "Mission Type" "Topham" "Ship" "Payload")
        separator (apply str (repeat (count header) "-"))
        body (apply str
                    (for [{:keys [id galaxy star planet moon asteroid mission-type topham ship payload]} rows]
                      (format format-str
                              (or id "-")
                              (or galaxy "-")
                              (or star "-")
                              (or planet "-")
                              (or moon "-")
                              (or asteroid "-")
                              (or mission-type "-")
                              (str (int->binary-string topham dim-count) " (" topham ")")
                              (or ship "-")
                              (if payload
                                (let [payload-str (str payload)]
                                  (if (> (count payload-str) 47)
                                    (str (subs payload-str 0 47) "...")
                                    payload-str))
                                "-"))))]
    (println separator)
    (println header)
    (println separator)
    (print body)
    (println separator)))

(defn find-ship
  "find best matching ship for the given query dimensions
   query example: {:galaxy \"Outer Rim\" :planet \"Tatooine\" :mission-type \"Patrol\"}"
  [ds query]
  (println "looking for a ship closest to:" (pr-str query))
  (let [sql (t/find-best-match {:table :starship_missions
                                :dims (:dims dims)
                                :required-dims (:required dims)
                                :need [:ship :mission_type :payload]}
                               query)
        result (jdbc/execute-one! ds
                                  [sql]
                                  {:builder-fn rs/as-unqualified-modified-maps
                                   :label-fn snake->kebab})]
    (if result
      (let [dim-count (count (:dims dims))
            topham-binary (int->binary-string (:topham result) dim-count)
            format-str "| %-12s | %-10s | %-10s | %-10s | %-10s | %-12s | %-14s | %-18s | %-40s |"
            header (format format-str "Galaxy" "Star" "Planet" "Moon" "Asteroid" "Mission Type" "Topham" "Ship" "Payload")
            separator (apply str (repeat (count header) "-"))]
        (println "found closest ship:")
        (println separator)
        (println header)
        (println separator)
        (println (format format-str
                         (or (:galaxy result) "-")
                         (or (:star result) "-")
                         (or (:planet result) "-")
                         (or (:moon result) "-")
                         (or (:asteroid result) "-")
                         (or (:mission_type result) "-")
                         (str topham-binary " (" (:topham result) ")")
                         (:ship result)
                         (if (:payload result)
                           (let [payload-str (str (:payload result))]
                             (if (> (count payload-str) 37)
                               (str (subs payload-str 0 37) "...")
                               payload-str))
                           "-")))
        (println separator)
        (println "dimensions matched: " (apply str (for [i (range dim-count)]
                                                     (if (= (nth topham-binary i) \1)
                                                       (str (nth (:dims dims) i) " ")
                                                       "")))))
      (println "no matching ship found for the given dimensions"))))

;; (def universe (make-universe!))
;; OR
;; (def universe (make-universe! :in :postgres))

;; (show-missions universe)
;; (find-ship universe {:planet "Hoth" :moon "Echo Base" :mission-type "Patrol"})
