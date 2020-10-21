(ns leadbot.text-utils
  (:require [clojure.string :as str]
            [clojure.data.json :as json])
  (:import
    (net.dv8tion.jda.internal.entities TextChannelImpl ReceivedMessage)
    (net.dv8tion.jda.api EmbedBuilder MessageBuilder)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (javax.swing.text.rtf Constants)
    (net.dv8tion.jda.api.entities MessageEmbed TextChannel)))

;; Bot Name
(defn isitme? [{:keys [myname]} botname]
  (= botname myname))

(defn send-message [^TextChannelImpl textchannel message]
  (println "Sending Message: " message)
  (.queue
    (.sendMessage textchannel message))
  (println "Message Sent"))

(defn delete-message [^ReceivedMessage message]
  (println "Deleting Message: " (.getMessageId message) " - " message)
  (.queue
    (.deleteMessageById (.getTextChannel message) (.getMessageId message))))

(defn build-now-playing [{:keys [title author seek duration]}]
  (let [np
        (doto (EmbedBuilder.)
          (.setTitle "Now Playing")
          ;(.setDescription (str title " " author))
          (.addField "Title:" title true)
          (.addBlankField true)
          (.addField "Artist:" author true))

        add-progress
        (if (> 2000 seek)
          (doto np
            (.addField "Length" (str (quot duration 1000) "s") false))
          (doto np
            (.addField
              (str "Progress - " (quot (* seek 100) duration) "%")
              (str (quot seek 1000) "s / " (quot duration 1000) "s")
              false)))]

    (.build add-progress)))

(defn send-playing [textchannel track]
  (let [embed (build-now-playing track)]
    (println "MESSAGE: " textchannel "  " embed)
    (send-message textchannel embed)))

(defn update-playing [^GuildMessageReceivedEvent last-nowplaying-event track]
  (let [^MessageEmbed embed (build-now-playing track)]
    (.queue
      (.editMessageById
        (.getChannel last-nowplaying-event)
        (.getMessageId last-nowplaying-event)
        embed))
    ;; TODO: I feel this should work but it's not. instead do above
    #_(.queue (.apply (.editMessage (.getMessage last-nowplaying-event) embed) update-message))
    ))

(defn build-track-list
  ; tracks {:track/title :track/author :added/by}
  ([title tracks]
   (build-track-list title tracks nil))
  ([title tracks selected]
   (let [t-list
         (map-indexed
           (fn [idx t]
             (if (= idx selected)
               (assoc t :selected true :idx idx)
               (assoc t :idx idx)))
           tracks)

         t-list
         (take 10 t-list)

         ;; If you want it a dynamic embedded field...
         #_add-fn
         #_(fn [^EmbedBuilder mb t]
           (.addField mb (str (:track/title t)) (:track/author t) false))
         #_embed
         #_(doto (EmbedBuilder.)
           (.setTitle title)
           (.setDescription "!playlist select <number>"))
         #_embed #_(reduce add-fn embed t-list)
         #_(send-message textchannel (.build (reduce add-fn message t-list)))
         ;;

         ;; "json" code block for prettyness
         ;; https://www.online-tech-tips.com/software-reviews/how-to-add-color-to-messages-on-discord/
         ;; https://highlightjs.org/static/demo/
         codeblocktext
         (->
           (fn [{:keys [selected] :as t}]
             (if selected
               [(get t :idx) {(get t :track/title) (get t :track/author) :length (str (quot (get t :track/duration) 1000) "s") :selected true}]
               [(get t :idx) {(get t :track/title) (get t :track/author) :length (str (quot (get t :track/duration) 1000) "s")}]))
           (map t-list)
           (json/write-str)
           (str/replace #"],\[" "],\n [") ; Drop each item on to its own line
           )

         message
         (doto (MessageBuilder.)
           (.append (str "\n" title "\n"))
           (.appendCodeBlock codeblocktext "json"))]

     (.build message))))



;current now playing
;new now playing = when a message is sent to current channel or it's a command on a new channel
; if current and new, delete current, send new to new
;; if nil current and new, send new
;; if nil new and current, update current


(defn set-new-chat-event [player-atom event]
  (swap! player-atom assoc :new-comms-event event))

(defn set-current-chat-event [player-atom event]
  (swap! player-atom assoc
    :nowplaying-event event
    :new-comms-event nil))

(defn update-chat-event
  [ctx ^GuildMessageReceivedEvent new-nowplaying-event]

  (let [player-atom (get-in ctx [:player])
        ^GuildMessageReceivedEvent current-nowplaying-event
        (:nowplaying-event @player-atom)

        current-channel (when current-nowplaying-event (.getName (.getChannel current-nowplaying-event)))
        new-channel (.getName (.getChannel new-nowplaying-event))]

    (when (and current-channel (= current-channel new-channel))
      (set-new-chat-event player-atom new-nowplaying-event))))