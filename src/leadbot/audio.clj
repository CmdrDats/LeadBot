(ns leadbot.audio
  (:require
    [leadbot.audio-utils :as au]
    [leadbot.text-utils :as tu]
    [leadbot.queue :as qm])
  (:import
    (com.sedmelluq.discord.lavaplayer.player AudioPlayer AudioPlayerManager)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (com.sedmelluq.discord.lavaplayer.player.event AudioEventListener TrackEndEvent TrackStartEvent AudioEvent PlayerResumeEvent)
    (net.dv8tion.jda.api.audio AudioSendHandler)
    (java.nio ByteBuffer)
    (com.sedmelluq.discord.lavaplayer.track.playback AudioFrame)
    (net.dv8tion.jda.api.entities Guild Activity)           ;; This is used for .setSendingHandler
    (net.dv8tion.jda.internal.entities ReceivedMessage)
    (net.dv8tion.jda.api JDA)))


(def player-atom (atom {}))

;; Handle Events from the audioplayer
(defmulti handle-audio-event (fn [req] (class (:event req))))
(defn audio-event [ctx event]
  (handle-audio-event {:event event :ctx ctx}))

(defn write-file [filename b]
  (with-open [w (clojure.java.io/output-stream filename)]
    (.write w b)))

(defn create-audioplayer [{:keys [^AudioPlayerManager playermanager] :as ctx} event]
  (let [local-audioplayer
        (doto (.createPlayer playermanager)
          (.addListener
            (proxy [AudioEventListener] []
              (onEvent [e]
                (audio-event ctx e)))))

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
          (let [^AudioFrame frame @lastframe
                ba
                (ByteBuffer/wrap
                  (.getData frame))]
            (reset! lastframe nil)
            (write-file "song.mp3" (.getData frame))
            ba))

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


(defn now-playing-from-saved [{:keys [^JDA jda] :as ctx} event & [menu]]
  (let [^AudioPlayer local-audioplayer (.player event)
        player-atom (get-in ctx [:player])

        ^GuildMessageReceivedEvent current-comms-event
        (:nowplaying-event @player-atom)

        ^GuildMessageReceivedEvent new-comms-event
        (:new-comms-event @player-atom)]

    (cond
      (and current-comms-event new-comms-event)
      (do
        (tu/delete-message (.getMessage current-comms-event))
        (tu/set-current-chat-event player-atom new-comms-event)
        (tu/send-playing (.getTextChannel (.getMessage new-comms-event))
          (au/get-track-info
            (au/get-playing-track local-audioplayer))))


      (and current-comms-event (nil? new-comms-event))
      (tu/update-playing current-comms-event
        (au/get-track-info
          (au/get-playing-track local-audioplayer)))

      (and new-comms-event (nil? current-comms-event))
      (do
        (tu/set-new-chat-event player-atom new-comms-event)
        (tu/send-playing (.getTextChannel (.getMessage new-comms-event))
          (au/get-track-info
            (au/get-playing-track local-audioplayer)))))))

(defn now-playing [{:keys [^JDA jda] :as ctx} event & [menu]]
  (condp = (class event)

    ;; Send Now Playing from wherever the message came from (!play / !nowplaying)
    GuildMessageReceivedEvent
    (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
          message (.getMessage event)
          textchannel (.getTextChannel message)

          player-atom (get-in ctx [:player])]

      ;; Update this so we know where to send the next update
      (tu/set-current-chat-event player-atom event)

      ;; TODO: Instead of sending a new message, if the old one isn't too many messages ago, update it
      (if (.getPlayingTrack local-audioplayer)
        (let [t-info (au/get-track-info (au/get-playing-track local-audioplayer))]
          (.setPresence (.getPresence jda) (Activity/playing (str (:title t-info) " (" (:author t-info) ")")) false)
          (tu/send-playing textchannel t-info))
        (tu/send-message textchannel "No song playing, to play type !play")))

    ;; Send Now Playing to where it last came from as we're an audio event
    TrackStartEvent
    (now-playing-from-saved ctx event menu)

    PlayerResumeEvent
    (now-playing-from-saved ctx event menu)

    :default
    (println "No Matching Now Playing Event")))


(defn play-track
  ([ctx ^GuildMessageReceivedEvent event]
   (tu/send-message (.getTextChannel (.getMessage event)) "No song to play, try, *!load | !pla")
   (.startTrack (get-audioplayer ctx event) nil))

  ([ctx ^GuildMessageReceivedEvent event track]
   (play-track ctx event track
     (not (boolean (.getPlayingTrack (get-audioplayer ctx event))))))

  ([ctx ^GuildMessageReceivedEvent event track force-play?]
   (when force-play?
     (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
           voicechannel (.getChannel (.getVoiceState (.getMember event)))
           textchannel (.getTextChannel (.getMessage event))

           ;; If there's nothing playing and you force a play, you get two Start Events
           really-force-play?
           (if (boolean (.getPlayingTrack (get-audioplayer ctx event)))
             force-play?
             false)]

       (if-not voicechannel
         (do
           (qm/update-queue ctx [track])
           (tu/send-message textchannel "Please join a voice channel"))
         (do
           (au/join-voice-channel (.getGuild (.getMember event)) voicechannel)
           (.startTrack local-audioplayer track (not really-force-play?))))))))

(defn next-track [{:keys [^AudioPlayerManager playermanager] :as ctx} event & [menu]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
        track (qm/pop-queue ctx)]
    (play-track ctx event track true)))

(defn pause-track [ctx event & [menu]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
        message (.getMessage event)
        textchannel (.getTextChannel message)]

    (.setPaused local-audioplayer true)
    (tu/send-message textchannel "Paused. To resume, !resume")))

(defn resume-track [ctx event & [menu]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)]

    (.setPaused local-audioplayer false)
    #_(now-playing ctx event)))

(defn play [ctx event & [{:keys [args] :as menu}]]
  (let [^AudioPlayer local-audioplayer (get-audioplayer ctx event)
        player-atom (get-in ctx [:player])
        _ (swap! player-atom assoc :last-cmd-event event)]

    (cond
      (pos? (count args))
      (qm/load-playable-item-for-queue
        ctx event
        {:track-fn
         #(do
            (qm/update-queue ctx [%])
            (tu/send-message (.getTextChannel (.getMessage event)) "Added to queue")
            (play ctx event))
         :playlist-fn
         #(do
            (qm/update-queue ctx (.getTracks %))
            (tu/send-message (.getTextChannel (.getMessage event)) "Added to queue")
            (play ctx event))}
        (first args))

      (not (au/get-playing-track local-audioplayer))
      (play-track ctx event (qm/pop-queue ctx)))))


;; Handle Events from the audioplayer

(defmethod handle-audio-event :default [{:keys [event]}]
  (println "Unhandled -Audio- event class:" (class event)))

(defmethod handle-audio-event TrackStartEvent
  [{:keys [event ctx]}]
  (println "Track has started")
  ;; Problem is first time we start we don't have a saved now playing event to send to
  (now-playing ctx event))

(defn next-track-audio-event [ctx]
  (let [track (qm/pop-queue ctx)
        player-atom (get-in ctx [:player])
        ^GuildMessageReceivedEvent last-cmd-event (:last-cmd-event @player-atom)]
    (play-track ctx last-cmd-event track true)))

(defmethod handle-audio-event TrackEndEvent
  [{:keys [event ctx]}]
  (println "Track has stopped, Start next track if we need to: "
    (.mayStartNext (.endReason event)))
  (when (.mayStartNext (.endReason event))
    (next-track-audio-event ctx)))

(defmethod handle-audio-event PlayerResumeEvent
  [{:keys [event ctx]}]
  (now-playing ctx event))