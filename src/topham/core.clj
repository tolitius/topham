(ns topham.core
  (:require [clojure.string :as s]
            [inquery.core :as q]))

(defn make-topham [dimensions intel]
  (let [dims (map #(if (nil? (intel %))
                     0
                     1)
                  dimensions)
        bins (apply str dims)]
    (Integer/parseInt bins 2)))

(defn dim->column [dim]
  (let [column (name dim)]
    ;; only alphanumerics and underscores
    (if (re-matches #"^[a-zA-Z0-9_]+$" column)
      column
      (throw (ex-info "this dimension name has sql'wise/column unsafe characters"
                     {:dimension dim
                      :column column})))))
(defn table->sql
  [table]
  (let [t (name table)]
    (when-not (re-matches #"^[A-Za-z0-9_]+$" t)
      (throw (ex-info "unsafe table name" {:table table})))
    t))

(defn =clause [dim]
  "create a sql condition for a required dimension
   for a dimension like 'mars', generates: AND mars = :mars"
  [dim]
  (str "AND " dim " = :" dim " "))

(defn ?clause [dim]
  "create a sql condition for an optional dimension (that can be null)
   for a dimension like 'mars', generates: AND (mars = :mars OR mars IS NULL)"
  [dim]
  (str "AND (" dim " = :" dim
       " OR "  dim " IS NULL) "))

(defn dims->predicates
  "return an inquery 'with-preds' flavored map of predicates
   * key   : a no arg fun that decides if this predicate is used
   * value : an actual sql clause
  both required and optional dimensions included in `query` are accounted for"
  [dims
   required-dims
   query]
  (let [req-set   (set required-dims)]
    (into {}
          (for [dim dims
                :when (contains? query dim)         ;; only add if query has this dim
                :let [column  (dim->column dim)
                      clause  (if (req-set dim)
                                (=clause column)    ;; required  => exact match
                                (?clause column))]] ;; optional  => match it or NULL
            [#(contains? query dim) clause]))))

(defn find-best-match
  "builds/returns SQL to find a best matching row

  arguments
  ---------
  * `table`         : keyword or string   e.g. :dimensions
  * `dims`          : vector of all dimension keywords, in priority order
  * `required-dims` : subset of `dims` that are mandatory / non-null
  * `need`          : a vector of what columns query is searching for
                    : (one or many, e.g. [:x] or [:price :quantity])
  * `query`         : map from dimension keyword to lookup value

  returns
  -------
  ready to roll SQL to find the best match"

  [{:keys [table dims required-dims need]}
   query]

  ;; make sure required dims are in the query
  (doseq [d required-dims
          :when (nil? (query d))]
    (throw (ex-info "query is missing required dimension" {:dim d})))

  (let [all-cols (concat dims
                         required-dims
                         need)
        fields   (->> all-cols
                      (map dim->column) ;; safe column names
                      (s/join ", "))

        base     "select :fields, topham from :table"

        sql-w-preds (q/with-preds
                      base
                      (dims->predicates dims required-dims query)
                      {:prefix "where"})

        sql-w-params (q/with-params sql-w-preds
                                    (merge {:fields {:as fields}
                                            :table  {:as (table->sql table)}}
                                           query))

        find-it (str sql-w-params " order by topham desc limit 1")]
    find-it))
