(ns leadbot.db
  (:require [datahike.api :as d]))

(def schema
  [{:db/ident :track/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :track/author
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :track/duration
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident :track/link
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :track/source
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :added/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :playlist/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(def db-uri "datahike:file:///tmp/leadbot")
(def part "app")

(defn create-db []
  (d/create-database db-uri))

(defn connect []
  (d/connect db-uri))

(defn setup-schema [db]
  (d/transact db schema))

(defn write-data [db data]
  (println "Writing to DB:
  " data)
  (d/transact db data))

(defn write-single [db data]
  (write-data db [data]))

(defn select-all [db]
  (let [d @db
        q
        (d/q
          '[:find ?e ?tt
            :where
            [?e :track/title ?tt]]
             @db)]

    (map #(d/entity d (first %)) q)))

(defn list-playlist [db playlist]
  (let [d @db
        q
        (d/q
          {:query
           '[:find ?e
             :in $ ?pn
             :where
             [?e :playlist/name ?pn]]
           :args
           [d playlist]}
          d)]

    (map #(d/entity d (first %)) q)))


(defn setup-db []
  (let [db (connect)]
    (setup-schema db)
    db))