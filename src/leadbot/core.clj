(ns leadbot.core
  (:gen-class)
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [leadbot.audio :as audio]
    [leadbot.playlist :as pl]
    [leadbot.text :as text]
    [nrepl.server]
    [leadbot.audio-utils :as au]
    [leadbot.db :as db])
  (:import
    (java.util.concurrent Executors ExecutorService)
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (com.sedmelluq.discord.lavaplayer.source AudioSourceManagers)
    (com.sedmelluq.discord.lavaplayer.source.youtube YoutubeAudioSourceManager)
    (net.dv8tion.jda.api JDA JDA$Status OnlineStatus JDABuilder Permission)
    (net.dv8tion.jda.api.entities Guild Activity)
    (net.dv8tion.jda.api.hooks EventListener)))



;; Serializable, saved state
(def app-atom (atom {}))

;; Ephemeral system objects - non-serializable
(def context-state (atom {}))

(defn bot-event [event]
  (text/handle-event {:event event :app-atom app-atom :ctx @context-state}))

(defn shutdown-guild [jda guild]
  (let [audio-manager (.getAudioManager guild)]
    (.closeAudioConnection audio-manager)))

(defn shutdown
  [{{:keys [^ExecutorService threadpool
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
          (edn/read-string (slurp "config.edn"))
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

    ;(db/create-db)
    (let [db
          (do
            (try (db/create-db) (catch Exception e))
            (db/setup-db))

          jdabuilder
          (doto (JDABuilder.)
            (.setToken (:token config))
            ;(.setAudioSendFactory (NativeAudioSendFactory.))

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
        :playermanager playermanager
        :player audio/player-atom
        :db db)

      (println "Setup and ready."))))
