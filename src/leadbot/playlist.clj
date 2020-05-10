(ns leadbot.playlist
  (:require
    [leadbot.audio :as audio]
    [leadbot.audio-utils :as au]
    [leadbot.db :as db]
    [leadbot.queue :as qm]
    [leadbot.text-utils :as tu]
    [leadbot.util :as u]
    [clojure.string :as str])
  (:import (com.sedmelluq.discord.lavaplayer.track AudioPlaylist)
           (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
           (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)))

(defn remove-track-by-id [db id user]
  (db/write-single db [:db/retract id :playlist/name user]))

(defn remove-http [{:keys [db]} textchannel user last-match]
  (let [track-id (db/get-track-by-link db last-match)
        track (db/get-ent db track-id)]

    (remove-track-by-id db track-id user)
    (tu/send-message textchannel (str "Removed: " (:track/title track)))))

(defn remove-num [{:keys [db]} textchannel user last-match]
  ;; TODO: This isn't the correct way but it's something to test with

  (let [playlist
        (map #(db/make-pretty-map %)
          (db/list-playlist db user))

        to-delete (get playlist (int last-match))
        track-id (u/tonumber (db/get-track-by-link db (:track/source to-delete)))
        track (db/get-ent db track-id)]

    (if (and to-delete track-id)
      (do
        (remove-track-by-id db track-id user)
        (tu/send-message textchannel (str "Removed: " (:track/title track))))
      (tu/send-message textchannel "Soon you'll have a menu that can move!"))))

(defn remove-song
  [ctx
   ^GuildMessageReceivedEvent event
   {:keys [last-match] :as menu}]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)
        user (.getName (.getAuthor event))]

    (tu/send-message textchannel (str "Let me do something with that"))
    (cond
      (str/includes? last-match "http")
      (remove-http ctx textchannel user last-match)

      :else
      (remove-num ctx textchannel user last-match))))

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
          (doseq [part
                  (->>
                    (.getTracks playlist)
                    (map #(merge (au/get-track-info-schema %) add-info))
                    (partition-all 10))]
            (db/write-data db (vec part)))
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
        playlist
        (map #(db/make-pretty-map %)
          (db/list-playlist db playlist-name))]

    (tu/send-message textchannel
      (tu/build-track-list
        (str "Playlist: " playlist-name " (" (count playlist) ")" "\n*!playlist remove <number> | !playlist scroll*")
        playlist))))

(defn list-my-songs [{:keys [^DefaultAudioPlayerManager playermanager db] :as ctx} ^GuildMessageReceivedEvent event menu]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name (.getName (.getAuthor event))
        playlist
        (map #(db/make-pretty-map %)
          (db/list-playlist db playlist-name))]

    (tu/send-message textchannel
      (tu/build-track-list
        (str "Playlist: " playlist-name " (" (count playlist) ")" "\n*!playlist remove <number> | !playlist scroll*")
        playlist))))


(defn load-playlist
  [{:keys [^DefaultAudioPlayerManager db] :as ctx}
   ^GuildMessageReceivedEvent event
   {:keys [last-match]}]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name last-match
        playlist (db/list-playlist db playlist-name)]

    (doseq [t playlist]
      (qm/load-playable-item-for-queue
        ctx event
        {:track-fn #(audio/play-track ctx event %)
         :playlist-fn
         #(do
            (qm/update-queue ctx (rest (.getTracks %)))
            (audio/play-track ctx event (first (.getTracks %))))}
        (get t :track/url)))

    (tu/send-message textchannel
      (str "The playlist '" playlist-name "' has been added to the queue"))))

(defn load-my-playlist
  [{:keys [^DefaultAudioPlayerManager db] :as ctx}
   ^GuildMessageReceivedEvent event menu]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        playlist-name (.getName (.getAuthor event))
        playlist (db/list-playlist db playlist-name)

        player-atom (get-in ctx [:player])
        _ (swap! player-atom assoc :last-chat-event event)]

    (doseq [t playlist]
      (qm/load-playable-item-for-queue
        ctx event
        {:track-fn
         #(do
            (qm/update-queue ctx [%])
            (audio/play ctx event))
         :playlist-fn
         #(do
            (qm/update-queue ctx (rest (.getTracks %)))
            (audio/play-track ctx event (first (.getTracks %))))}
        (get t :track/link)))

    (tu/send-message textchannel
      (str "The playlist '" playlist-name "' has been added to the queue"))))

(defn scroll-list [{:keys [^DefaultAudioPlayerManager playermanager db] :as ctx} ^GuildMessageReceivedEvent event menu]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)]
    (tu/send-message textchannel "This feature has not been implemented yet")))

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
  (do
    (def all-songs
      (->> (db/select-all db)
           (map make-pretty-map)))
    all-songs)

  ;; List by playlist
  (do
    (def pla (map make-pretty-map (db/list-playlist db "theonlysinjin")))
    pla)


  )