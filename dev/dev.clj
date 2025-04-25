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
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]

            [topham.core :as t]
            ))

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

(defn make-datasource [{:keys [connection pool]}]
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


(defstate config
  :start (c/load-config)
  :stop :stopped)

(defstate datasource
  :start (make-datasource (-> config :db))
  :stop (close datasource))

(defn restart []
  (mount/stop)
  (mount/start))
