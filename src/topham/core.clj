(ns topham.core
  (:require [clojure.string :as s]
            [clojure.set :as sets]
            [inquery.core :as q]))

(defn make-topham [dimensions intel]
  (let [dims (map #(if (nil? (intel %))
                     0
                     1)
                  dimensions)
        bins (apply str dims)]
    (Integer/parseInt bins 2)))

(defn kebab->snake [k]
  (when k
    (clojure.string/replace (name k) "-" "_")))

(defn snake->kebab [k]
  (when k
    (clojure.string/replace (name k) "_" "-")))

(defn dim->column [dim]
  (let [column (kebab->snake dim)]
    ;; only alphanumerics and underscores
    (if (re-matches #"^[a-zA-Z0-9_]+$" column)
      column
      (throw (ex-info "this dimension name has sql'wise/column unsafe characters"
                     {:dimension dim
                      :column column})))))

(defn table->sql
  [table]
  (let [t (name table)]
    (when-not (re-matches #"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$" t)
      (throw (ex-info "unsafe table name" {:table table})))
    t))


(defn =clause [dim]
  "create a sql condition for a required dimension
   for a dimension like 'mars', generates: AND mars = :mars"
  [dim]
  (str "AND " dim " = :" (snake->kebab dim) " "))

(defn ?clause [dim]
  "create a sql condition for an optional dimension (that can be null)
   for a dimension like 'mars', generates: AND (mars = :mars OR mars IS NULL)"
  [dim]
  (str "AND (" dim " = :" (snake->kebab dim)
       " OR "  dim " IS NULL)"))

(defn dims->predicates
  "return an inquery 'with-preds' flavored map of predicates.
   all dims must be accounted for, if not in query, enforce NULL"
  [dims required-dims query]
  (let [all-dims (distinct (concat dims required-dims))
        req-set (set required-dims)]
    (into {}
          (for [dim all-dims
                :let [column (dim->column dim)
                      clause (cond
                               (contains? query dim) (if (req-set dim)
                                                       (=clause column)
                                                       (?clause column))

                               :else (str "AND " column " IS NULL"))]]   ;; NOT in query => must be NULL
            [(fn [] true) clause]))))


(defn- valid-param-type? [v]
  (or (nil? v)
      (string? v)
      (number? v)
      (boolean? v)
      (and (map? v) (contains? v :as))       ;; ":as:" is an inqeury tag
      (satisfies? inquery.core/SqlParam v))) ;; optional: allow e.g. PGobject etc. if protocol is implemented

(defn- validate-query-types
  "ensures all query values are either scalar,
   or tagged with {:as ...} for explicit substitution"
  [query]
  (doseq [[k v] query
          :when (not (valid-param-type? v))]
    (throw (ex-info "invalid query param type"
                    {:key k
                     :value v
                     :hint "must be scalar, or wrapped with {:as ...} as per: https://github.com/tolitius/inquery?tab=readme-ov-file#escaping"}))))

(defn- validate-query
  "ensures query contains all required dimensions, and only expected keys
   throws otherwise"
  [{:keys [dims required-dims]}
   query]
  (let [allowed-keys (set (concat dims required-dims))
        query-keys   (set (keys query))

        missing      (filterv #(nil? (query %))
                              required-dims)
        extras       (into [] (sets/difference query-keys
                                               allowed-keys))]

    (when (seq missing)
      (throw (ex-info "query is missing required dimensions"
                      {:missing missing})))

    (when (seq extras)
      (throw (ex-info "query contains unexpected keys"
                      {:unexpected extras})))

    (validate-query-types query)))

(defn find-best-match
  "builds/returns SQL to find a best matching row

  * `table`         : keyword or string   e.g. :dimensions
  * `dims`          : vector of all dimension keywords, in priority order
  * `required-dims` : subset of `dims` that are mandatory / non-null
  * `need`          : a vector of what columns query is searching for
                    : (one or many, e.g. [:x] or [:ship :payload])
  * `query`         : map from dimension keyword to lookup value

  returns: ready to roll SQL to find the best match"

  [{:keys [table
           dims
           required-dims
           need] :as schema}
   query]

  (validate-query schema query)

  (let [all-cols (concat dims
                         required-dims
                         need)
        fields   (->> all-cols
                      (map dim->column) ;; safe column names
                      (s/join ", "))

        base     "select :fields, topham from :table"

        sql-w-preds (q/with-preds
                      base
                      (dims->predicates dims
                                        required-dims
                                        query)
                      {:prefix "where"})

        sql-w-params (q/with-params sql-w-preds
                                    (merge {:fields {:as fields}
                                            :table  {:as (table->sql table)}}
                                           query))

        find-it (str sql-w-params " order by topham desc limit 1")]
    find-it))


(defn make-insert-row
  "builds and returns an `insert … values …` SQL statement for one row

  * {:keys [dims non-dims]}

    • dims      : vector of optional dimension keywords (used for topham)
    • non-dims  : vector of all other fields (including required ones like :mission-type)

  * table : keyword or string naming the db table (e.g. `:events`)
  * row   : map of column values

  returns: fully-populated SQL string with inlined values (ready to run)"
  [{:keys [dims non-dims]}
   table
   row]

  (let [dims     (or dims [])
        non-dims (or non-dims [])
        all-cols (concat dims non-dims [:topham])

        col->sql (fn [k] (if (= k :topham)
                           "topham"
                           (dim->column k)))

        cols-sql (->> all-cols
                      (map col->sql)
                      (clojure.string/join ", "))
        vals-sql (->> all-cols
                      (map #(str ":" (name %)))
                      (clojure.string/join ", "))

        query    (str "insert into :table (" cols-sql ") "
                      "values (" vals-sql ")")

        params   (into {:table  {:as (table->sql table)}
                        :topham (make-topham dims row)}
                       (for [k (concat dims non-dims)]
                         [k (row k)]))]

    (q/with-params query params)))



(defn make-single-update
  "returns an `update … set … where id = :id` SQL statement for a single row.

  * {:keys [dims non-dims]}

    • dims      : vector of optional dimension keywords (used for topham)
    • non-dims  : vector of all other fields (including required ones e.g. :mission-type)

  * table : keyword/string table name
  * row   : map with :id plus any fields being updated

  how:

    • throws if :id is missing
    • recomputes `topham` if *any* of the dims are touched — requires all of them
    • does not recompute if dims are untouched
    • only updates fields in dims ∪ non-dims
    • always has `WHERE id = :id`

  returns: SQL string with parameter substitution"
  [{:keys [dims non-dims]}
   table
   row]

  (when (nil? (row :id))
    (throw (ex-info "make-single-update requires non-nil PK (:id)" {:row row})))

  (let [dims       (or dims [])
        non-dims   (or non-dims [])
        touched-dims (filter #(contains? row %) dims)

        ;; if updating any dimension, must supply all dims to recompute topham
        _ (when (and (seq touched-dims)
                     (not= (set touched-dims) (set dims)))
            (throw (ex-info "to update any dimension, you must supply *all* of them. so topham can be recomputed safely"
                            {:provided touched-dims :all-dims dims})))

        ;; columns to SET (exclude :id)
        update-cols (cond-> (filter #(and (not= % :id) (contains? row %))
                                    (concat dims non-dims))
                      (seq touched-dims) (conj :topham))

        to-col-sql (fn [k] (if (= k :topham)
                             "topham"
                             (dim->column k)))

        set-sql (->> update-cols
                     (map #(str (to-col-sql %) " = :" (name %)))
                     (clojure.string/join ", "))

        query (str "update :table set " set-sql " where id = :id")

        params (-> {:table {:as (table->sql table)}
                    :id    (row :id)}
                   (merge (select-keys row update-cols))
                   (#(if (some #{:topham} update-cols)
                       (assoc % :topham (make-topham dims row))
                       %)))]

    (q/with-params query params)))
