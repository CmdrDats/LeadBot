(ns leadbot.bot
  (:require
    [leadbot.text-utils :as tu]
    [leadbot.audio :as audio]
    [leadbot.audio-utils :as au])
  (:import (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)))

(defn join-voice-channel
  [ctx ^GuildMessageReceivedEvent event
   {:keys [last-match] :as menu}]

  (let [message (.getMessage event)
        textchannel (.getTextChannel message)
        guild (.getGuild event)

        voicechannels
        (.getVoiceChannelsByName guild last-match true)]

    (if (> (count voicechannels) 1)
      (tu/send-message textchannel (str "Please be more specific, " voicechannels))
      (au/join-voice-channel guild (first voicechannels)))))

