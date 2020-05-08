(ns leadbot.audio-utils
  (:require
    [leadbot.text-utils :as tu])
  (:import
    (net.dv8tion.jda.api EmbedBuilder)
    (com.sedmelluq.discord.lavaplayer.player AudioPlayer)
    (com.sedmelluq.discord.lavaplayer.track AudioTrack)
    (net.dv8tion.jda.api.entities Guild)))

(defn join-voice-channel [^Guild guild voicechannel]
  (when-not (.inVoiceChannel (.getVoiceState (.getSelfMember guild)))
    (.openAudioConnection (.getAudioManager guild) voicechannel)))

(defn get-playing-track [^AudioPlayer local-audioplayer]
  (.getPlayingTrack local-audioplayer))

(defn get-track-info [^AudioTrack track]
  {:title (.title (.getInfo track))
   :author (.author (.getInfo track))
   :link (.uri (.getInfo track))
   :seek (.getPosition track)
   :duration (.getDuration track)})

(defn get-track-info-schema [^AudioTrack track]
  {:track/title (.title (.getInfo track))
   :track/author (.author (.getInfo track))
   :track/link (.uri (.getInfo track))
   ;:track/seek (.getPosition track)
   :track/duration (.getDuration track)})


