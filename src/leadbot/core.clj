(ns leadbot.core
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [nrepl.server]
    [clojure.string :as str])
  (:import
    (java.util.concurrent Executors ExecutorService)
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (com.sedmelluq.discord.lavaplayer.source AudioSourceManagers)
    (com.sedmelluq.discord.lavaplayer.source.youtube YoutubeAudioSourceManager)
    (com.sedmelluq.discord.lavaplayer.jdaudp NativeAudioSendFactory)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (net.dv8tion.jda.api.audio AudioSendHandler)
    (net.dv8tion.jda.api JDA JDA$Status OnlineStatus JDABuilder Permission)
    (net.dv8tion.jda.api.entities Guild Activity)
    (net.dv8tion.jda.api.hooks EventListener)
    (java.nio ByteBuffer)
    (com.sedmelluq.discord.lavaplayer.track.playback AudioFrame)))


(defmulti handle-event (fn [req] (class (:event req))))

(defmethod handle-event :default [{:keys [event]}]
  (println "Unhandled event class:"  (class event)))



(defmethod handle-event GuildMessageReceivedEvent
  [{:keys [^GuildMessageReceivedEvent event]
    {:keys [^DefaultAudioPlayerManager playermanager]} :ctx
    :as req}]
  (println "Got message")
  (let [message (.getMessage event)
        author (.getAuthor event)
        member (.getMember event)
        textchannel (.getTextChannel message)
        voicechannel (.getChannel (.getVoiceState member))]

    (cond
      (.isBot author)
      (do
        (println "Bot")
        nil)

      (not voicechannel)
      (do
        (println "No voice")
        (doto (.sendMessage textchannel "You're not in a voice channel?")
          (.queue)))

      (str/starts-with? (.getContentStripped message) "!play")
      (let [botvoicestate (.getVoiceState (.getSelfMember (.getGuild event)))]
        (println "Play")

        (when-not (.inVoiceChannel botvoicestate)
          (.openAudioConnection (.getAudioManager (.getGuild event)) voicechannel))

        (doto (.sendMessage textchannel "Playing a random song. eventually.")
          (.queue))

        (let [audioplayer (.createPlayer playermanager)
              lastframe (atom nil)]
          (.loadItem playermanager "https://www.youtube.com/watch?v=q0hyYWKXF0Q"
            (proxy [AudioLoadResultHandler] []
              (trackLoaded [track]
                (.setSendingHandler (.getAudioManager (.getGuild member))
                  (proxy [AudioSendHandler] []
                    (canProvide []
                      (swap! lastframe (fn [f] (if f f (.provide audioplayer))))
                      (not (nil? @lastframe)))

                    (provide20MsAudio []
                      (swap! lastframe (fn [f] (if f f (.provide audioplayer))))
                      (let [^AudioFrame frame @lastframe]
                        (reset! lastframe nil)
                        (ByteBuffer/wrap
                          (.getData frame))))

                    (isOpus [] true)))
                (.playTrack audioplayer track)

                (doto (.sendMessage textchannel "Track loaded")
                  (.queue)))
              (playlistLoaded [playlist]
                (doto (.sendMessage textchannel "Playlist loaded")
                  (.queue)))
              (noMatches []
                (doto (.sendMessage textchannel "No matches")
                  (.queue)))
              (loadFailed [ex]
                (doto (.sendMessage textchannel "Load failed")
                  (.queue)))))
          )

        )

      :else (println "Nothing"))))



;; Serializable, saved state
(def app-atom (atom {}))

;; Ephemeral system objects - non-serializable
(def context-state (atom {}))


(defn bot-event [event]
  (handle-event {:event event :app-atom app-atom :ctx @context-state}))

(defn shutdown-guild [jda guild]
  (let [audio-manager (.getAudioManager guild)]
    (.closeAudioConnection audio-manager)))

(defn shutdown [{{:keys [^ExecutorService threadpool
                         ^JDA jda]} :ctx}]
  (spit "app-state.edn" @app-atom)
  (.shutdownNow threadpool)
  (when (not= JDA$Status/SHUTDOWN (.getStatus jda))
    (for [^Guild guild (.getGuilds jda)]
      (shutdown-guild jda guild))
    (.shutdown JDA)))

(defn -main [& args]
  (let [config
        (try
          (edn/read-string (slurp (io/resource "config.edn")))
          (catch Exception e {}))]
    (reset! app-atom
      (or
        (try
          (edn/read-string (slurp "app-state.edn"))
          (catch Exception e {}))
        {}))

    (when-not config
      (spit "config.edn" (pr-str {:token "insert_bot_token_here"}))
      (println "Generated config.edn file, please insert bot token in there to proceed.")
      (System/exit -1))

    (println "Starting nrepl")
    (let [nrepl (nrepl.server/start-server)]
      (spit ".nrepl-port" (:port nrepl))
      (println "nrepl port: " (:port nrepl)))

    (let [jdabuilder
          (doto (JDABuilder.)
            (.setToken (:token config))
            (.setAudioSendFactory (NativeAudioSendFactory.))

            #_(.setAudioEnabled true)
            (.setActivity (Activity/playing "Loading.."))
            (.setStatus OnlineStatus/DO_NOT_DISTURB)
            (.addEventListeners
              (into-array
                [(proxy [EventListener] []
                   (onEvent [event] (bot-event event)))]))
            (.setBulkDeleteSplittingEnabled true))

          jda
          (.build jdabuilder)

          playermanager
          (DefaultAudioPlayerManager.)]


      (AudioSourceManagers/registerRemoteSources playermanager)
      (AudioSourceManagers/registerLocalSource playermanager)
      (.setPlaylistPageCount
        ^YoutubeAudioSourceManager
        (.source playermanager YoutubeAudioSourceManager)
        10)

      (println "Invite link: "
        (.getInviteUrl jda
          [Permission/MESSAGE_READ
           Permission/MESSAGE_WRITE
           Permission/MESSAGE_HISTORY
           Permission/MESSAGE_ADD_REACTION
           Permission/MESSAGE_EMBED_LINKS
           Permission/MESSAGE_ATTACH_FILES
           Permission/MESSAGE_MANAGE
           Permission/MESSAGE_EXT_EMOJI
           Permission/MANAGE_CHANNEL
           Permission/VOICE_CONNECT
           Permission/VOICE_SPEAK
           Permission/NICKNAME_CHANGE]))

      (swap! context-state assoc
        :jda jda
        :threadpool (Executors/newScheduledThreadPool 4)
        :playermanager playermanager)
      (println "Setup and ready."))))
