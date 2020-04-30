(ns leadbot.audio
  (:require
    [leadbot.text-utils :as tu])
  (:import
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (net.dv8tion.jda.api.audio AudioSendHandler)
    (java.nio ByteBuffer)
    (com.sedmelluq.discord.lavaplayer.track.playback AudioFrame)))

(def now-playing-atom (atom nil))

(defn now-playing
  [{:keys [playermanager] :as ctx}
   event
   {:keys [last-match] :as menu}]

  (let [np @now-playing-atom
        {:keys [audioplayer]}
        (if (:audioplayer np) np (swap! now-playing-atom assoc :audioplayer (.createPlayer playermanager)))

        message (.getMessage event)
        textchannel (.getTextChannel message)

        track-title (.title (.getInfo (.getPlayingTrack audioplayer)))
        track-author (.author (.getInfo (.getPlayingTrack audioplayer)))

        track-seek (.getPosition (.getPlayingTrack audioplayer))
        track-duration (.getDuration (.getPlayingTrack audioplayer))]

    (tu/send-message textchannel (str track-title " - " track-author))
    (tu/send-message textchannel (str track-seek " - " track-duration))))

(defn play-url
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

    (if (not voicechannel)
      (tu/send-message textchannel "You're not in a voice channel?")
      (do
        (when-not (.inVoiceChannel botvoicestate)
          (.openAudioConnection (.getAudioManager (.getGuild event)) voicechannel))

        (tu/send-message textchannel (str "Playing " url))

        (let [np @now-playing-atom
              {:keys [audioplayer]}
              (if (:audioplayer np) np (swap! now-playing-atom assoc :audioplayer (.createPlayer playermanager)))

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
                (tu/send-message textchannel "Track loaded"))

              (playlistLoaded [playlist]
                (tu/send-message textchannel "Playlist loaded"))

              (noMatches []
                (tu/send-message textchannel "No matches"))

              (loadFailed [ex]
                (tu/send-message textchannel "Load failed")))))))))