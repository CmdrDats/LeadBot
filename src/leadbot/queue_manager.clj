(ns leadbot.queue-manager
  (:require
    [leadbot.text-utils :as tu]
    [leadbot.audio-utils :as au]
    [clojure.string :as str])
  (:import
    (net.dv8tion.jda.internal.entities ReceivedMessage)
    (com.sedmelluq.discord.lavaplayer.track AudioTrack AudioPlaylist)
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)))

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
      (tu/build-track-list ctx event "Current Queue\n*!pause | !resume | !next | !shuffle*" (map #(au/get-track-info-schema ^AudioTrack %) queue)))))

(defn shuffle-queue [ctx event & [menu]]
  (let [^ReceivedMessage message (.getMessage event)
        textchannel (.getTextChannel message)

        player-atom (get-in ctx [:player])
        queue
        (:queue
          (swap! player-atom update :queue shuffle))]

    (tu/send-message textchannel "Queue Shuffled")
    (tu/send-message textchannel
      (tu/build-track-list ctx event "Current Queue\n*!pause | !resume | !next | !shuffle*" (map #(au/get-track-info-schema ^AudioTrack %) queue)))))

(defn load-playable-item-for-queue
  [{:keys [^DefaultAudioPlayerManager playermanager] :as ctx} event {:keys [track-fn playlist-fn]} url]

  (.loadItem
    playermanager url
    (proxy [AudioLoadResultHandler] []
      (trackLoaded [track]
        (track-fn track))

      (playlistLoaded [^AudioPlaylist playlist]
        (playlist-fn playlist))

      (noMatches []
        #_(tu/send-message textchannel "No matches"))

      (loadFailed [ex]
        #_(tu/send-message textchannel "Load failed")))))