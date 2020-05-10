(ns leadbot.util
  (:require [clojure.string :as str]))

(defn todecimal [text & [default]]
  (cond
    (nil? text)
    (or default 0.0)

    (string? text)
    (try
      (Double/parseDouble (str/replace text #"," "."))
      (catch Exception _ (or default 0)))

    (char? text)
    (recur (str text) default)

    (number? text)
    (double text)

    :else (or default 0)))

(defn tonumber [text & [default]]
  (cond
    (nil? text)
    (or default 0)

    (string? text)
    (try
      (Long/parseLong text)
      (catch NumberFormatException e
        (todecimal text default)))

    (char? text)
    (recur (str text) default)

    (instance? Number text)
    text

    :else (or default 0)))