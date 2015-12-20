(ns sqlingvo.core
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [sqlingvo.compiler :as compiler]
            [sqlingvo.db :as db]
            [sqlingvo.expr :refer :all]
            [sqlingvo.util :refer :all])
  (:import (sqlingvo.expr Stmt))
  (:refer-clojure :exclude [distinct group-by replace update]))

(defn sql-name [db x]
  (compiler/sql-name db x))

(defn sql-keyword [db x]
  (compiler/sql-keyword db x))

(defn sql-quote [db x]
  (compiler/sql-quote db x))

(defn chain-state [body]
  (m-seq (remove nil? body)))

(defn compose
  "Compose multiple SQL statements."
  [stmt & body]
  (Stmt. (chain-state (cons stmt body))))

(defn ast
  "Returns the abstract syntax tree of `stmt`."
  [stmt]
  (cond
    (map? stmt)
    stmt
    (instance? Stmt stmt)
    (second ((.f stmt) nil))
    :else (second (stmt nil))))

(defn as
  "Parse `expr` and return an expr with and AS clause using `alias`."
  [expr alias]
  (if (sequential? alias)
    (for [alias alias]
      (let [column (parse-column (str expr "." (name alias)))]
        (assoc column
               :as (->> (map column [:schema :table :name])
                        (remove nil?)
                        (map name)
                        (str/join "-")
                        (keyword)))))
    (assoc (parse-expr expr) :as alias)))

(defn asc
  "Parse `expr` and return an ORDER BY expr using ascending order."
  [expr] (assoc (parse-expr expr) :direction :asc))

(defn cascade
  "Returns a fn that adds a CASCADE clause to an SQL statement."
  [condition]
  (conditional-clause :cascade condition))

(defn column
  "Add a column to `stmt`."
  [name type & {:as options}]
  (let [column (assoc options :op :column :name name :type type)
        column (update-in column [:default] #(if %1 (parse-expr %1)))]
    (fn [stmt]
      [nil (-> (update-in stmt [:columns] #(concat %1 [(:name column)]))
               (assoc-in [:column (:name column)]
                         (assoc column
                                :schema (:schema stmt)
                                :table (:name stmt))))])))

(defn continue-identity
  "Returns a fn that adds a CONTINUE IDENTITY clause to an SQL statement."
  [condition]
  (conditional-clause :continue-identity condition))

(defn concurrently
  "Add a CONCURRENTLY clause to a SQL statement."
  [condition]
  (conditional-clause :concurrently condition))

(defn do-constraint
  "Add a DO CONSTRAINT clause to a SQL statement."
  [constraint]
  (set-val :do-constraint constraint))

(defn do-nothing
  "Add a DO NOTHING clause to a SQL statement."
  []
  (assoc-op :do-nothing))

(defn do-update
  "Add a DO UPDATE clause to a SQL statement."
  [expr]
  (assoc-op :do-update :expr (parse-map-expr expr)))

(defn with-data
  "Add a WITH [NO] DATA clause to a SQL statement."
  [data?]
  (assoc-op :with-data :data data?))

(defn desc
  "Parse `expr` and return an ORDER BY expr using descending order."
  [expr] (assoc (parse-expr expr) :direction :desc))

(defn distinct
  "Parse `exprs` and return a DISTINCT clause."
  [exprs & {:keys [on]}]
  (make-node
   :op :distinct
   :children [:exprs :on]
   :exprs (parse-exprs exprs)
   :on (parse-exprs on)))

(defn delimiter
  "Returns a fn that adds a DELIMITER clause to an SQL statement."
  [delimiter]
  (set-val :delimiter delimiter))

(defn encoding
  "Returns a fn that adds a ENCODING clause to an SQL statement."
  [encoding]
  (set-val :encoding encoding))

(defn copy
  "Returns a fn that builds a COPY statement.

  Examples:

  (copy db :country []
  (from :stdin))
  ;=> [\"COPY \\\"country\\\" FROM STDIN\"]

  (copy db :country []
  (from \"/usr1/proj/bray/sql/country_data\"))
  ;=> [\"COPY \\\"country\\\" FROM ?\" \"/usr1/proj/bray/sql/country_data\"]"
  {:style/indent 3}
  [db table columns & body]
  (let [table (parse-table table)
        columns (map parse-column columns)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :copy
               :db db
               :children [:table :columns]
               :table table
               :columns columns))))))

(defn create-table
  "Returns a fn that builds a CREATE TABLE statement."
  {:style/indent 2}
  [db table & body]
  (let [table (parse-table table)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :create-table
               :db db
               :children [:table]
               :table table))))))

(defn delete
  "Returns a fn that builds a DELETE statement.

  Examples:

  (delete db :continents)
  ;=> [\"DELETE FROM \\\"continents\\\"\"]

  (delete db :continents
    (where '(= :id 1)))
  ;=> [\"DELETE FROM \\\"continents\\\" WHERE (\\\"id\\\" = 1)\"]"
  {:style/indent 2}
  [db table & body]
  (let [table (parse-table table)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :delete
               :db db
               :children [:table]
               :table table))))))

(defn drop-table
  "Returns a fn that builds a DROP TABLE statement.

  Examples:

  (drop-table db [:continents])
  ;=> [\"DROP TABLE TABLE \\\"continents\\\"\"]

  (drop-table db [:continents :countries])
  ;=> [\"DROP TABLE TABLE \\\"continents\\\", \\\"countries\\\"\"]"
  {:style/indent 2}
  [db tables & body]
  (let [tables (map parse-table tables)]
    (Stmt. (fn [stmt]
             ((chain-state body)
              (make-node
               :op :drop-table
               :db db
               :children [:tables]
               :tables tables))))))

(defn- make-set-op
  [op args]
  (let [[[opts] stmts] (split-with map? args)]
    (Stmt. (fn [_]
             [nil (merge
                   (make-node
                    :op op
                    :db (-> stmts first ast :db)
                    :children [:stmts]
                    :stmts (map ast stmts))
                   opts)]))))

(defn except
  "Returns a SQL EXCEPT statement.

   Examples:

   (except
    (select db [1])
    (select db [2]))
   ;=> [\"SELECT 1 EXCEPT SELECT 2\"]

   (except
    {:all true}
    (select db [1])
    (select db [2]))
   ;=> [\"SELECT 1 EXCEPT ALL SELECT 2\"]"
  [& args]
  (make-set-op :except args))

(defn from
  "Returns a fn that adds a FROM clause to an SQL statement. The
  `from` forms can be one or more tables, :stdin, a filename or an
  other sub query.

  Examples:

  (select [:*]
    (from :continents))
  ;=> [\"SELECT * FROM \\\"continents\\\"\"]

  (select [:*]
    (from :continents :countries)
    (where '(= :continents.id :continent-id)))
  ;=> [\"SELECT * FROM \\\"continents\\\", \\\"countries\\\"
  ;=>  WHERE (\\\"continents\\\".\\\"id\\\" = \\\"continent_id\\\")\"]

  (select [*]
    (from (as (select [1 2 3]) :x)))
  ;=> [\"SELECT * FROM (SELECT 1, 2, 3) AS \\\"x\\\"\"]

  (copy :country []
    (from :stdin))
  ;=> [\"COPY \\\"country\\\" FROM STDIN\"]

  (copy :country []
    (from \"/usr1/proj/bray/sql/country_data\"))
  ;=> [\"COPY \\\"country\\\" FROM ?\" \"/usr1/proj/bray/sql/country_data\"]
  "
  [& from]
  (fn [stmt]
    (let [from (case (:op stmt)
                 :copy [(first from)]
                 (map parse-from from))]
      [from (update-in stmt [:from] #(concat %1 from))])))

(defn group-by
  "Returns a fn that adds a GROUP BY clause to an SQL statement."
  [& exprs]
  (concat-in [:group-by] (parse-exprs exprs)))

(defn if-exists
  "Returns a fn that adds a IF EXISTS clause to an SQL statement."
  [condition]
  (conditional-clause :if-exists condition))

(defn if-not-exists
  "Returns a fn that adds a IF EXISTS clause to an SQL statement."
  [condition]
  (conditional-clause :if-not-exists condition))

(defn inherits
  "Returns a fn that adds an INHERITS clause to an SQL statement."
  [& tables]
  (let [tables (map parse-table tables)]
    (fn [stmt]
      [tables (assoc stmt :inherits tables)])))

(defn insert
  "Returns a fn that builds a INSERT statement."
  {:style/indent 3}
  [db table columns & body]
  (let [table (parse-table table)
        columns (map parse-column columns)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :insert
               :db db
               :children [:table :columns]
               :table table
               :columns columns))))))

(defn intersect
  "Returns a SQL INTERSECT statement.

   Examples:

   (intersect
    (select db [1])
    (select db [2]))
   ;=> [\"SELECT 1 INTERSECT SELECT 2\"]

   (intersect
    {:all true}
    (select db [1])
    (select db [2]))
   ;=> [\"SELECT 1 INTERSECT ALL SELECT 2\"]"
  [& args]
  (make-set-op :intersect args))

(defn join
  "Returns a fn that adds a JOIN clause to an SQL statement."
  [from condition & {:keys [type outer pk]}]
  (concat-in
   [:joins]
   [(let [join (make-node
                :op :join
                :children [:outer :type :from]
                :outer outer
                :type type
                :from (parse-from from))]
      (cond
        (and (sequential? condition)
             (= :on (keyword (name (first condition)))))
        (assoc join
               :on (parse-expr (first (rest condition))))
        (and (sequential? condition)
             (= :using (keyword (name (first condition)))))
        (assoc join
               :using (parse-exprs (rest condition)))
        (and (keyword? from)
             (keyword? condition))
        (assoc join
               :from (parse-table (str/join "." (butlast (str/split (name from) #"\."))))
               :on (parse-expr `(= ~from ~condition)))
        :else (throw (ex-info "Invalid JOIN condition." {:condition condition}))))]))

(defn like
  "Returns a fn that adds a LIKE clause to an SQL statement."
  [table & {:as opts}]
  (let [table (parse-table table)
        like (assoc opts :op :like :table table)]
    (set-val :like like)))

(defn limit
  "Returns a fn that adds a LIMIT clause to an SQL statement."
  [count]
  (assoc-op :limit :count count))

(defn nulls
  "Parse `expr` and return an NULLS FIRST/LAST expr."
  [expr where] (assoc (parse-expr expr) :nulls where))

(defn on-conflict
  "Add a ON CONFLICT clause to a SQL statement."
  {:style/indent 1}
  [target & body]
  (let [target (map parse-column target)]
    (let [[_ node]
          ((chain-state body)
           (make-node
            :op :on-conflict
            :target target
            :children [:target]))]
      (Stmt. (fn [stmt]
               [_ (assoc stmt :on-conflict node)])))))

(defn on-conflict-on-constraint
  "Add a ON CONFLICT ON CONSTRAINT clause to a SQL statement."
  {:style/indent 1}
  [target & body]
  (let [[_ node]
        ((chain-state body)
         (make-node
          :op :on-conflict-on-constraint
          :target target
          :children [:target]))]
    (Stmt. (fn [stmt]
             [_ (assoc stmt :on-conflict-on-constraint node)]))))

(defn offset
  "Returns a fn that adds a OFFSET clause to an SQL statement."
  [start]
  (assoc-op :offset :start start))

(defn order-by
  "Returns a fn that adds a ORDER BY clause to an SQL statement."
  [& exprs]
  (concat-in [:order-by] (parse-exprs exprs)))

(defn window
  "Returns a fn that adds a WINDOW clause to an SQL statement."
  [& exprs]
  (assoc-op :window :definitions (parse-exprs exprs)))

(defn primary-key
  "Returns a fn that adds the primary key to a table."
  [& keys]
  (fn [stmt]
    [nil (assoc stmt :primary-key keys)]))

(defn drop-materialized-view
  "Drop a materialized view."
  {:style/indent 2}
  [db view & body]
  (let [view (parse-table view)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :drop-materialized-view
               :db db
               :children [:view]
               :view view))))))

(defn refresh-materialized-view
  "Refresh a materialized view."
  [db view & body]
  (let [view (parse-table view)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :refresh-materialized-view
               :db db
               :children [:view]
               :view view))))))

(defn restart-identity
  "Returns a fn that adds a RESTART IDENTITY clause to an SQL statement."
  [condition]
  (conditional-clause :restart-identity condition))

(defn restrict
  "Returns a fn that adds a RESTRICT clause to an SQL statement."
  [condition]
  (conditional-clause :restrict condition))

(defn returning
  "Returns a fn that adds a RETURNING clause to an SQL statement."
  [& exprs]
  (concat-in [:returning] (parse-exprs exprs)))

(defn select
  "Returns a fn that builds a SELECT statement.

  Examples:

  (select db [1])
  ;=> [\"SELECT 1\"]

  (select db [:*]
  (from :continents))
  ;=> [\"SELECT * FROM \\\"continents\\\"\"]

  (select db [:id :name]
  (from :continents))
  ;=> [\"SELECT \\\"id\\\", \\\"name\\\" FROM \\\"continents\\\"\"]"
  {:style/indent 2}
  [db exprs & body]
  (let [[_ select]
        ((chain-state body)
         (make-node
          :op :select
          :db db
          :children [:distinct :exprs]
          :distinct (if (= :distinct (:op exprs))
                      exprs)
          :exprs (if (sequential? exprs)
                   (parse-exprs exprs))))]
    (Stmt. (fn [stmt]
             (->> (case (:op stmt)
                    :insert (assoc stmt :select select)
                    :select (assoc stmt :exprs (:exprs select))
                    select)
                  (repeat 2))))))

(defn temporary
  "Returns a fn that adds a TEMPORARY clause to an SQL statement."
  [condition]
  (conditional-clause :temporary condition))

(defn truncate
  "Returns a fn that builds a TRUNCATE statement.

  Examples:

  (truncate db [:continents])
  ;=> [\"TRUNCATE TABLE \\\"continents\\\"\"]

  (truncate db [:continents :countries])
  ;=> [\"TRUNCATE TABLE \\\"continents\\\", \\\"countries\\\"\"]"
  {:style/indent 1}
  [db tables & body]
  (let [tables (map parse-table tables)]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :truncate
               :db db
               :children [:tables]
               :tables tables))))))

(defn union
  "Returns a SQL UNION statement.

   Examples:

   (union
    (select db [1])
    (select db [2]))
   ;=> [\"SELECT 1 UNION SELECT 2\"]

   (union
    {:all true}
    (select db [1])
    (select db [2]))
   ;=> [\"SELECT 1 UNION ALL SELECT 2\"]"
  [& args]
  (make-set-op :union args))

(defn update
  "Returns a fn that builds a UPDATE statement.

  Examples:

  (update db :films {:kind \"Dramatic\"}
    (where '(= :kind \"Drama\")))
  ;=> [\"UPDATE \\\"films\\\" SET \\\"kind\\\" = ? WHERE (\\\"kind\\\" = ?)\"
  ;=>  \"Dramatic\" \"Drama\"]"
  {:style/indent 2}
  [db table row & body]
  (let [table (parse-table table)
        exprs (if (sequential? row) (parse-exprs row))
        row (if (map? row) (parse-map-expr row))]
    (Stmt. (fn [_]
             ((chain-state body)
              (make-node
               :op :update
               :db db
               :children [:table :exprs :row]
               :table table
               :exprs exprs
               :row row))))))

(defn values
  "Returns a fn that adds a VALUES clause to an SQL statement."
  [values]
  (if (= :default values)
    (set-val :default-values true)
    (concat-in [:values] (map parse-map-expr (sequential values)))))

(defn where
  "Returns a fn that adds a WHERE clause to an SQL statement."
  [condition & [combine]]
  (let [condition (parse-condition condition)]
    (fn [stmt]
      (cond
        (or (nil? combine)
            (nil? (:condition (:where stmt))))
        [nil (assoc stmt :where condition)]
        :else
        [nil (assoc-in
              stmt [:where :condition]
              (make-node
               :op :condition
               :children [:condition]
               :condition (make-node
                           :op :fn
                           :children [:name :args]
                           :name combine
                           :args [(:condition (:where stmt))
                                  (:condition condition)])))]))))

(defn with
  "Returns a fn that builds a WITH (common table expressions) query."
  {:style/indent 2}
  [db bindings query]
  (assert (even? (count bindings)) "The WITH bindings must be even.")
  (let [bindings (map (fn [[name stmt]]
                        (vector (keyword name)
                                (ast stmt)))
                      (partition 2 bindings))
        query (ast query)]
    (Stmt. (fn [stmt]
             [nil (assoc query
                         :with (make-node
                                :op :with
                                :db db
                                :children [:bindings]
                                :bindings bindings))]))))

(defn sql
  "Compile `stmt` into a clojure.java.jdbc compatible vector."
  [stmt]
  (let [ast (ast stmt)]
    (compiler/compile-stmt (:db ast) ast)))

(defmethod print-method Stmt
  [stmt writer]
  (print-method (sql stmt) writer))
