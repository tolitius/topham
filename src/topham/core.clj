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

(defn kebab->snake [k]
  (clojure.string/replace (name k) "-" "_"))

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
       " OR "  dim " IS NULL)"))

(defn dims->predicates
  "return an inquery 'with-preds' flavored map of predicates
   * key   : a no arg fun that decides if this predicate is used
   * value : an actual sql clause
  both required and optional dimensions included in `query` are accounted for"
  [dims
   required-dims
   query]
  (let [all-dims (distinct (concat dims required-dims))
        req-set   (set required-dims)]
    (into {}
          (for [dim all-dims
                :when (contains? query dim)         ;; only add if query has this dim
                :let [column  (dim->column dim)
                      clause  (if (req-set dim)
                                (=clause column)    ;; required  => exact match
                                (?clause column))]] ;; optional  => match it or NULL
            [#(contains? query dim) clause]))))

(defn find-best-match
  "builds/returns SQL to find a best matching row

  * `table`         : keyword or string   e.g. :dimensions
  * `dims`          : vector of all dimension keywords, in priority order
  * `required-dims` : subset of `dims` that are mandatory / non-null
  * `need`          : a vector of what columns query is searching for
                    : (one or many, e.g. [:x] or [:price :quantity])
  * `query`         : map from dimension keyword to lookup value

  returns: ready to roll SQL to find the best match"

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

  * {:keys [required optional non-dims]}

    • required  : vector of **mandatory** dimension keywords
    • optional  : vector of **optional** dimension keywords
    • non-dims  : vector of columns that are *not* dimensions (price, quantity, created-at, …)

    the concatenation `required ⧺ optional` defines the priority order for the `topham` bitmask

  * table : keyword or string naming the db table (e.g. `:events`)
  * row   : map whose keys are some / all of `required ∪ optional ∪ non-dims`, holding the values to insert

  returns: a fully-populated SQL string ready to be run agains the database"

  [{:keys [required optional non-dims]}
   table
   row]

  (doseq [d required
          :when (nil? (row d))]
    (throw (ex-info "missing required dimension" {:dim d})))

  (let [required   (or required  [])
        optional   (or optional  [])
        non-dims   (or non-dims [])
        dims       (vec (concat required optional)) ; this is where the priority order is defined

        all-cols   (concat dims non-dims [:topham])

        col->sql   (fn [k] (if (= k :topham)
                             "topham"
                             (dim->column k)))

        cols-sql   (->> all-cols
                        (map col->sql)
                        (clojure.string/join ", "))
        vals-sql   (->> all-cols
                        (map #(str ":" (name %)))
                        (clojure.string/join ", "))

                   ;; safe because of dim->column
        query      (str "insert into :table (" cols-sql ") "
                        "values (" vals-sql ")")

        params     (into {:table  {:as (table->sql table)}
                          :topham (make-topham dims row)}
                         (for [k (concat dims non-dims)]  ;; add nils for missing dimensions
                           [k (row k)]))]

    (q/with-params query params)))


(defn make-single-update
  "return an `update … set … where id = ?` SQL for a single row

    • dims  : {:required [:galaxy …]
               :optional […]
               :non-dims […]}
    • table : keyword/string table name
    • row   : map that **must** contain :id plus any columns that are being updated

  how:

    • throws if :id is missing / nil
    • throws if any `required` dimension is nil
    • recomputes `topham` only when *all* dimensions are supplied (otherwise leaves it untouched)
    • only updates columns that are in `required ∪ optional ∪ non-dims`
    • WHERE clause is always `id = :id` (to avoid accidental partial / full-table updates)

  returns: a fully-populated SQL string ready to be run agains the database"

  [{:keys [required optional non-dims]}
   table
   row]

  (when (nil? (row :id))
    (throw (ex-info "make-single-update requires non-nil PK (:id)" {:row row})))

  (doseq [d required
          :when (nil? (row d))]
    (throw (ex-info "missing required dimension" {:dim d})))

  (let [required   (or required  [])
        optional   (or optional  [])
        non-dims   (or non-dims [])
        dims       (vec (concat required optional))

        touched-dims (filter #(contains? row %) dims)

        ;; if some but not all dims: no go (can’t recompute topham safely)
        _ (when (and (seq touched-dims)
                     (not= (set touched-dims) (set dims)))
            (throw (ex-info "to update any dimension you must supply *all* of them. so topham can be recomputed safely"
                            {:provided touched-dims :all-dims dims})))

        ;; columns to SET (exclude :id)
        update-cols (cond-> (filter #(and (not= % :id) (contains? row %))
                                    (concat dims non-dims))
                      (seq touched-dims) (conj :topham))

        to-col-sql  (fn [k] (if (= k :topham)
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
