(ns leadbot.text-utils
  (:require [clojure.string :as str]
            [clojure.data.json :as json])
  (:import
    (net.dv8tion.jda.internal.entities TextChannelImpl)
    (net.dv8tion.jda.api EmbedBuilder MessageBuilder)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (javax.swing.text.rtf Constants)))

;; Bot Name
(def myname "LeadBoat")

(defn isitme? [botname]
  (= botname myname))

(defn send-message [^TextChannelImpl textchannel message]
  (println "Sending Message: " message)
  (.queue
    (.sendMessage textchannel message))
  (println "Message Sent"))

(defn send-playing [textchannel {:keys [title author seek duration]}]

  (let [embed
        (doto (EmbedBuilder.)
          (.setTitle "Now Playing")
          ;(.setDescription (str title " " author))
          (.addField "Title:" title true)
          (.addBlankField true)
          (.addField "Artist:" author true)
          (.addField
            (str "Progress - " (quot (* seek 100) duration) "%")
            (str (quot seek 1000) "s / " (quot duration 1000) "s")
            false))]

    (send-message textchannel (.build embed))))


(defn build-playlist-message [playlist]
  (str "Playlist: (" (count playlist) ")\n"
    (str/join "\n"
      (map #(str (get % :track/title) " - " (get % :track/author) " [ " (get % :added/name) " ]")
        (take 15 playlist)))))

(defn build-track-list
  ; tracks {:track/title :track/author :added/by}
  ([ctx event title tracks]
   (build-track-list ctx event title tracks nil))
  ([ctx event title tracks selected]
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
               [(get t :idx) {(get t :track/title) (get t :track/author) :selected true}]
               [(get t :idx) {(get t :track/title) (get t :track/author)}]))
           (map t-list)
           (json/write-str)
           (str/replace #"],\[" "],\n ["))

         message
         (doto (MessageBuilder.)
           (.append (str "\n" title "\n"))
           (.appendCodeBlock codeblocktext "json"))]

     (.build message))))