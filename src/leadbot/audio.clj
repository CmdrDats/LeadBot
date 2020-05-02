(ns leadbot.audio
  (:require
    [leadbot.audio-utils :as au]
    [leadbot.text-utils :as tu]
    [leadbot.queue-manager :as qm])
  (:import
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler AudioPlayer AudioPlayerManager)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (com.sedmelluq.discord.lavaplayer.track AudioPlaylist AudioTrack)
    (com.sedmelluq.discord.lavaplayer.player.event AudioEventListener TrackEndEvent TrackStartEvent AudioEvent)
    (net.dv8tion.jda.api.audio AudioSendHandler)
    (java.nio ByteBuffer)
    (com.sedmelluq.discord.lavaplayer.track.playback AudioFrame)
    (net.dv8tion.jda.api.entities Guild)
    (net.dv8tion.jda.internal.entities ReceivedMessage)))


(defn join-voice-channel [^Guild guild voicechannel]
  (when-not (.inVoiceChannel (.getVoiceState (.getSelfMember guild)))
    (.openAudioConnection (.getAudioManager guild) voicechannel)))



(def player-atom (atom {}))

(declare audio-event)

(defn create-audioplayer [{:keys [^AudioPlayerManager playermanager] :as ctx} event]
  (let [local-audioplayer
        (doto (.createPlayer playermanager)
          (.addListener
            (proxy [AudioEventListener] []
              (onEvent [event]
                #(audio-event ctx event)))))

        guild (.getGuild (.getMember event))
        lastframe (atom nil)]


    (.setSendingHandler
      (.getAudioManager guild)
      (proxy [AudioSendHandler] []
        (canProvide []
          (swap! lastframe (fn [f] (if f f (.provide local-audioplayer))))
          (not (nil? @lastframe)))

        (provide20MsAudio []
          (swap! lastframe (fn [f] (if f f (.provide local-audioplayer))))
          (let [^AudioFrame frame @lastframe]
            (reset! lastframe nil)
            (ByteBuffer/wrap
              (.getData frame))))

        (isOpus [] true)))

    local-audioplayer))

(defn get-audioplayer [{:keys [^AudioPlayerManager playermanager] :as ctx} event]
  (let [player-atom (get-in ctx [:player])
        audioplayer (get-in @player-atom [:audioplayer])]

    (if audioplayer
      audioplayer
      (:audioplayer
        (swap! player-atom assoc :audioplayer
          (create-audioplayer ctx event))))))



(defn now-playing [ctx event & [menu]]
  (condp = (class event)

    ;; Send Now Playing from wherever the message came from (!play / !nowplaying)
    GuildMessageReceivedEvent
    (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
          message (.getMessage event)
          textchannel (.getTextChannel message)

          player-atom (get-in ctx [:player])]

      ;; Update this so we know where to send the next update
      (swap! player-atom assoc :nowplaying-event event)

      ;; TODO: Instead of sending a new message, if the old one isn't too many messages ago, update it
      (if (.getPlayingTrack local-audioplayer)
        (au/send-playing textchannel
          (au/get-track-info local-audioplayer))
        (tu/send-message textchannel "No song playing, to play type !play")))

    ;; Send Now Playing to where it last came from as we're an audio event
    TrackEndEvent
    (let [^AudioPlayer local-audioplayer (.player event)

          player-atom (get-in ctx [:player])
          ^GuildMessageReceivedEvent last-nowplaying-event (:nowplaying-event @player-atom)
          ^ReceivedMessage message (.getMessage last-nowplaying-event)
          textchannel (.getTextChannel message)]

      (if (.getPlayingTrack local-audioplayer)
        (au/send-playing textchannel
          (au/get-track-info local-audioplayer))
        (tu/send-message textchannel "No song playing, to play type !play")))))


(defn play-track
  ([ctx ^GuildMessageReceivedEvent event track]
   (play-track ctx event track
     (not (boolean (.getPlayingTrack (get-audioplayer ctx event))))))

  ([ctx ^GuildMessageReceivedEvent event track force-play?]
   (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)]
     (.startTrack local-audioplayer track (not force-play?))

     (now-playing ctx event))))

(defn next-track [{:keys [^AudioPlayerManager playermanager] :as ctx} event & [menu]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
        track (qm/pop-queue ctx)]


    ;; TODO: Fix Hack
    ;; Bit of a hack, I've had to remove and add the AudioEventListener
    ;; because when we stop the track to change to the next song,
    ;; it fires off a TrackEnd event which then we handle as a "Oh let's play the next one"
    (doto local-audioplayer
      (.removeListener
        (proxy [AudioEventListener] []
          (onEvent [event]
            #(audio-event ctx event)))))

    (play-track ctx event track true)

    (doto local-audioplayer
      (.addListener
        (proxy [AudioEventListener] []
          (onEvent [event]
            #(audio-event ctx event)))))))

(defn pause-track [ctx event & [menu]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
        message (.getMessage event)
        textchannel (.getTextChannel message)]

    (.setPaused local-audioplayer true)
    (tu/send-message textchannel "Paused. To resume, !resume")))

(defn resume-track [ctx event & [menu]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
        message (.getMessage event)]

    (.setPaused local-audioplayer false)
    (now-playing ctx event)))

(defn load-playable-item
  [{:keys [^DefaultAudioPlayerManager playermanager] :as ctx}
   event textchannel url]

  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)]
    (.loadItem
      playermanager url
      (proxy [AudioLoadResultHandler] []
        (trackLoaded [track]
          (qm/update-queue ctx [track])
          (when-not (.getPlayingTrack local-audioplayer)
            (next-track ctx event))

          (tu/send-message textchannel "Track Loaded"))

        (playlistLoaded [^AudioPlaylist playlist]
          (qm/update-queue ctx (.getTracks playlist))
          (when-not (.getPlayingTrack local-audioplayer)
            (next-track ctx event))

          (tu/send-message textchannel "Playlist loaded"))

        (noMatches []
          (tu/send-message textchannel "No matches"))

        (loadFailed [ex]
          (tu/send-message textchannel "Load failed"))))))

(defn play-url
  [ctx ^GuildMessageReceivedEvent event
   {:keys [last-match] :as menu}]

  (let [message (.getMessage event)
        member (.getMember event)
        textchannel (.getTextChannel message)
        voicechannel (.getChannel (.getVoiceState member))

        url last-match]

    (if (not voicechannel)
      (tu/send-message textchannel "You're not in a voice channel?")
      (do
        (join-voice-channel (.getGuild event) voicechannel)
        (tu/send-message textchannel (str "Let's do something with that"))
        (load-playable-item ctx event textchannel url)))))

(defn play-short-song [ctx event menu]
  (play-url ctx event (assoc menu :last-match "https://www.youtube.com/watch?v=8SPtkjMUkGk")))


;; Handle Events from the audioplayer
(defmulti handle-audio-event (fn [req] (class (:event req))))

(defmethod handle-audio-event :default [{:keys [event]}]
  (println "Unhandled -Audio- event class:" (class event)))

(defmethod handle-audio-event TrackStartEvent
  [{:keys [event ctx]}]
  (println "Track has started")
  )

(defmethod handle-audio-event TrackEndEvent
  [{:keys [event ctx]}]
  (println "Track has stopped")
  (next-track ctx event))

(defn audio-event [ctx event]
  (handle-audio-event {:event event :ctx ctx}))