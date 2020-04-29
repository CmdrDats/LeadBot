(ns leadbot.text
  (:require
    [clojure.string :as str]
    [leadbot.audio :as audio]
    [leadbot.text-utils :as tu]
    [leadbot.xkcd :as xkcd])
  (:import
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (net.dv8tion.jda.api.events.message.guild.react GuildMessageReactionAddEvent)))



(def command-menu
  [{:match #"!play"
    :submenu
    [{:match #".*"
      :action audio/play-url}]}

   {:match #"!xkcd"
    :submenu
    [{:match #"random"
      :action xkcd/random-xkcd}
     {:match #".*"
      :action xkcd/specific-xkcd}]}])


(defn menu-match [ctx selected-menu event message]
  (println "Running matcher against: " message)

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

;; On Message Received
(defmethod handle-event GuildMessageReceivedEvent
  [{:keys                                                      [^GuildMessageReceivedEvent event]
    {:keys [^DefaultAudioPlayerManager playermanager] :as ctx} :ctx
    :as                                                        req}]
  (println "Received Message")
  (let [message (.getMessage event)
        author (.getAuthor event)
        member (.getMember event)
        textchannel (.getTextChannel message)
        voicechannel (.getChannel (.getVoiceState member))


        menu-action
        (future (menu-match ctx command-menu event (.getContentStripped message)))]

    (cond
      (.isBot author)
      nil

      (not voicechannel)
      (tu/send-message textchannel "You're not in a voice channel?")

      @menu-action
      @menu-action


      :else
      (println "Nothing"))))


;; On Add Reaction
(defmethod handle-event GuildMessageReactionAddEvent
  [event]
  (println event))