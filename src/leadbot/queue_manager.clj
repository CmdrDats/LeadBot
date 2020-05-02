(ns leadbot.queue-manager
  (:require
    [leadbot.text-utils :as tu]
    [leadbot.audio-utils :as au]
    [clojure.string :as str])
  (:import
    (net.dv8tion.jda.internal.entities ReceivedMessage)
    (com.sedmelluq.discord.lavaplayer.track AudioTrack)))

(defn get-queue [ctx]
  (let [player-atom (get-in ctx [:player])
        queue (get-in @player-atom [:queue])]

    (if queue
      queue
      (:queue
        (swap! player-atom assoc :queue
          (list))))))


(defn update-queue [ctx audiotracks]
  (let [player-atom (get-in ctx [:player])
        queue (get-in @player-atom [:queue])]
    (:queue
      (swap! player-atom assoc :queue (concat queue audiotracks)))))

(defn pop-queue [ctx]
  (let [player-atom (get-in ctx [:player])
        queue (get-in @player-atom [:queue])

        next-song (first queue)
        new-queue (rest queue)]

    (swap! player-atom assoc :queue new-queue)
    next-song))


(defn print-queue [ctx event & [menu]]
  (let [queue (get-queue ctx)

        ^ReceivedMessage message (.getMessage event)
        textchannel (.getTextChannel message)]

    (tu/send-message textchannel
      (str "Current Queue:\n"
        (str/join "\n"
          (map #(.title (.getInfo ^AudioTrack %))
            queue))))))
