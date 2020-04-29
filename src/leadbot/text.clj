(ns leadbot.text
  (:require [clojure.string :as str])
  (:import
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (net.dv8tion.jda.api.audio AudioSendHandler)
    (java.nio ByteBuffer)
    (com.sedmelluq.discord.lavaplayer.track.playback AudioFrame)))


(defn play-youtube
  [{:keys [playermanager] :as ctx}
   event
   {:keys [last-match] :as menu}]

  (let [message (.getMessage event)
        author (.getAuthor event)
        member (.getMember event)
        textchannel (.getTextChannel message)
        voicechannel (.getChannel (.getVoiceState member))


        botvoicestate (.getVoiceState (.getSelfMember (.getGuild event)))

        url last-match]

    (println "Playing " url)

    (when-not (.inVoiceChannel botvoicestate)
      (.openAudioConnection (.getAudioManager (.getGuild event)) voicechannel))

    (doto (.sendMessage textchannel "Playing a random song. eventually.")
      (.queue))

    (let [audioplayer (.createPlayer playermanager)
          lastframe (atom nil)]

      (.loadItem
        playermanager url
        (proxy [AudioLoadResultHandler] []
          (trackLoaded [track]
            (.setSendingHandler
              (.getAudioManager (.getGuild member))
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
              (.queue))))))))

(def command-menu
  [{:match #"!play"
    :submenu
    [{:match #".*"
      :action play-youtube}]}])


(defn menu-match
   [ctx selected-menu event message]
  (let [message-parts (str/split message #" ")
        {action-fn :action :as selected-menu}
        (loop [message message-parts
               menu selected-menu]

          (when-not (or (empty? message) (empty? menu))
            (let [cmd (first message)
                  curr-menu (first menu)
                  {:keys [submenu action last-match] :as m}
                  (assoc curr-menu :last-match (re-matches (:match curr-menu) cmd))]


              (cond
                (nil? last-match)
                (recur message (rest menu))

                (and last-match submenu)
                (recur (rest message) submenu)

                (and last-match action (not-empty (rest message)))
                (assoc m :args (rest message))

                (and last-match action)
                m))))]

    (when action-fn
      (action-fn ctx event selected-menu))))







(defmulti handle-event (fn [req] (class (:event req))))


(defmethod handle-event :default [{:keys [event]}]
  (println "Unhandled event class:" (class event)))


(defmethod handle-event GuildMessageReceivedEvent
  [{:keys                                                      [^GuildMessageReceivedEvent event]
    {:keys [^DefaultAudioPlayerManager playermanager] :as ctx} :ctx
    :as                                                        req}]
  (println "Received Message")
  (let [message (.getMessage event)
        _ (println (.getContentStripped message))
        author (.getAuthor event)
        member (.getMember event)
        textchannel (.getTextChannel message)
        voicechannel (.getChannel (.getVoiceState member))


        menu-action
        (future (menu-match ctx command-menu event (.getContentStripped message)))]

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

      @menu-action
      @menu-action


      :else (println "Nothing"))))