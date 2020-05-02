(ns leadbot.xkcd
  (:require
    [leadbot.text-utils :as tu]))

(defn random-xkcd [ctx event menu]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)]

    (tu/send-message textchannel "https://c.xkcd.com/random/comic/")))

(defn specific-xkcd [ctx event {:keys [last-match] :as menu}]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)]

    (tu/send-message textchannel (str "https://xkcd.com/" last-match "/"))))

