(ns leadbot.text-utils
  (:require [clojure.string :as str])
  (:import
    (net.dv8tion.jda.internal.entities TextChannelImpl)
    (net.dv8tion.jda.api EmbedBuilder)))

;; Bot Name
(def myname "LeadBoat")

(defn isitme? [botname]
  (= botname myname))

(defn send-message [^TextChannelImpl textchannel message]
  (println message)
  (.queue
    (.sendMessage textchannel message)))

(defn send-playing [textchannel {:keys [title author seek duration]}]
  (let [embed
        (doto (EmbedBuilder.)
          (.setTitle "Now Playing")
          (.setDescription (str title " - " author))
          ;(.addBlankField true)
          (.addField
            (str "Progress - " (quot (* seek 100) duration) "%")
            (str seek "s / " duration "s")
            false))]

    (send-message textchannel (.build embed))))


(defn build-playlist-message [playlist]
  (str "Your Playlist:\n"
    (str/join "\n"
      (map #(str (get % :track/title) " - " (get % :track/author) " [ " (get % :added/name) " ]") playlist))))