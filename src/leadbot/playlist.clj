(ns leadbot.playlist
  (:require
    [leadbot.audio :as audio]
    [leadbot.audio-utils :as au]
    [leadbot.db :as db]
    [leadbot.queue-manager :as qm]
    [leadbot.text-utils :as tu])
  (:import (com.sedmelluq.discord.lavaplayer.track AudioPlaylist)
           (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
           (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)))

(defn load-playable-item
  [{:keys [^DefaultAudioPlayerManager playermanager db] :as ctx}
   ^GuildMessageReceivedEvent event
   {:keys [last-match] :as menu}]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)
        url last-match

        add-info
        { ;:db/id (d/tempid db/part)
         :track/source url
         :added/name (.getName (.getAuthor event))
         :playlist/name (.getName (.getAuthor event))}]

    (.loadItem
      playermanager url
      (proxy [AudioLoadResultHandler] []
        (trackLoaded [track]
          (->>
            (au/get-track-info-schema track)
            (merge add-info)
            (db/write-single db))
          (tu/send-message textchannel "Track Loaded"))

        (playlistLoaded [^AudioPlaylist playlist]
          (->>
            (.getTracks playlist)
            (map #(merge (au/get-track-info-schema %) add-info))
            (db/write-data db))
          (tu/send-message textchannel "Playlist loaded"))

        (noMatches []
          (tu/send-message textchannel "No matches"))

        (loadFailed [ex]
          (tu/send-message textchannel "Load failed"))))))

(defn add-song [ctx ^GuildMessageReceivedEvent event menu]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)]
    (tu/send-message textchannel (str "Let me do something with that"))
    (load-playable-item ctx event menu)))

(defn list-songs
  [{:keys [^DefaultAudioPlayerManager playermanager db] :as ctx}
   ^GuildMessageReceivedEvent event
   {:keys [last-match] :as menu}]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name last-match
        playlist (db/list-playlist db playlist-name)]

    (tu/send-message textchannel
      (tu/build-playlist-message playlist))))

(defn list-my-songs [{:keys [^DefaultAudioPlayerManager playermanager db] :as ctx} ^GuildMessageReceivedEvent event menu]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name (.getName (.getAuthor event))
        playlist (db/list-playlist db playlist-name)]

    (tu/send-message textchannel
      (tu/build-playlist-message playlist))))


(defn load-playlist
  [{:keys [^DefaultAudioPlayerManager db] :as ctx}
   ^GuildMessageReceivedEvent event
   {:keys [last-match]}]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name last-match
        playlist (db/list-playlist db playlist-name)]

    (doseq [t playlist]
      (qm/load-playable-item-for-queue ctx (get t :track/url)))

    (tu/send-message textchannel
      (str "The playlist '" playlist-name "' has been added to the queue"))

    (audio/play ctx event)))

(defn load-my-playlist
  [{:keys [^DefaultAudioPlayerManager db] :as ctx}
   ^GuildMessageReceivedEvent event menu]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name (.getName (.getAuthor event))
        playlist (db/list-playlist db playlist-name)]

    (doseq [t playlist]
      (qm/load-playable-item-for-queue ctx (get t :track/link)))

    (tu/send-message textchannel
      (str "The playlist '" playlist-name "' has been added to the queue"))

    (audio/play ctx event)))


;; ;;

(comment
  (db/create-db)
  ; (AudioTrackInfoBuilder.)

  (def db
    (if (:db @leadbot.core/context-state)
      (:db @leadbot.core/context-state)
      (db/setup-db)))

  (def song-list
    (vec
      (map
        (fn [idx]
          {:track/title (str "Song Name: " idx)
           :track/author (str "I am the author:" idx)
           :track/duration (+ 1000 idx)
           :track/link (str "https://soundcloud.com/user-912969653/alan-walker-my-heart?1=" idx)
           :track/source (str "https://soundcloud.com/user-912969653/alan-walker-my-heart?1=" idx)
           :added/name (str "I am Added by: " idx)
           :playlist/name "theonlysinjin"})
        (range 5))))

  (db/write-data db song-list)

  ;; Select All and Display Nicely
  (defn make-map [entity] (into {} (map (fn [k] [k (get entity k)]) (keys entity))))
  (do
    (def all-songs
      (->> (db/select-all db)
           (map make-map)))
    all-songs)

  ;; List by playlist
  (do
    (def pla (map make-map (db/list-playlist db "theonlysinjin")))
    pla)


  )