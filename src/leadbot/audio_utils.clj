(ns leadbot.audio-utils
  (:require
    [leadbot.text-utils :as tu])
  (:import
    (net.dv8tion.jda.api EmbedBuilder)
    (com.sedmelluq.discord.lavaplayer.player AudioPlayer)))


(defn send-playing [textchannel {:keys [title author seek duration]}]
  (let [embed
        (doto (EmbedBuilder.)
          (.setTitle "Now Playing")
          (.setDescription (str title " - " author))
          ;(.addBlankField true)
          (.addField
            (str "Progress - " (quot (* seek 100) duration) "%")
            (str seek "s / " duration "s")
            false))]

    (tu/send-message textchannel (.build embed))))

(defn get-track-info [^AudioPlayer local-audioplayer]
  {:title (.title (.getInfo (.getPlayingTrack local-audioplayer)))
   :author (.author (.getInfo (.getPlayingTrack local-audioplayer)))
   :link (.uri (.getInfo (.getPlayingTrack local-audioplayer)))
   :seek (quot (.getPosition (.getPlayingTrack local-audioplayer)) 1000)
   :duration (quot (.getDuration (.getPlayingTrack local-audioplayer)) 1000)})
