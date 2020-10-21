(ns leadbot.text
  (:require
    [clojure.string :as str]
    [leadbot.audio :as audio]
    [leadbot.bot :as bot]
    [leadbot.playlist :as pl]
    [leadbot.text-utils :as tu]
    [leadbot.xkcd :as xkcd]
    [leadbot.queue :as qm]
    [clojure.data.json :as json])
  (:import
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (net.dv8tion.jda.api.events.message.react MessageReactionAddEvent)
    (net.dv8tion.jda.internal.entities ReceivedMessage)
    (net.dv8tion.jda.api.entities MessageEmbed)
    (net.dv8tion.jda.api MessageBuilder)))

(declare send-help)

;; TODO: Implement default actions when no submenu matched
; {:match #"playlist"        - Exclude prefix, that is added
;    :changecomms true       - This changes where new messages are sent to
;    :submenu
;    [{:match #"add"         - submenu matching
;      :submenu
;      [{:match #".*"
;        :doccmd "playlist add <url>"                                              - The "HOW TO" use the command
;        :docstring "Add the url (YouTube / Soundcloud / etc) to your playlist"    - What the command does and maybe an example
;        :action #'pl/add-song}]}        - Your function to call, [ctx ^GuildMessageReceivedEvent event menu]

(def command-menu
  [;; Playlist Management
   {:match #"playlist"
    :submenu
    [{:match #"add"
      :submenu
      [{:match #".*"
        :doccmd "playlist add <url>"
        :docstring "Add the url (YouTube / Soundcloud / etc) to your playlist"
        :action #'pl/add-song}]}

     {:match #"remove"
      :submenu
      [{:match #".*"
        :doccmd "playlist remove <url/listID>"
        :docstring "Remove the specific song from your playlist"
        :action #'pl/remove-song}]}

     {:match #"list"
      :doccmd "playlist list <playlist/user>"
      :docstring "Lists the songs on your playlist"
      :action #'pl/list-songs}

     {:match #"scroll"
      :doccmd "playlist scroll"
      :docstring "Scrolls the list that's displayed by, playlist list"
      :action #'pl/scroll-list}]}

   {:match #"load"
    :doccmd "load <playlist/user>"
    :docstring "Loads either yours, or a specific playlist into the queue"
    :changecomms true
    :action #'pl/load-playlist}

   ;; Queue Management
   {:match #"queue"
    :doccmd "queue"
    :docstring "Print the current song queue"
    :action #'qm/print-queue}

   {:match #"play"
    :doccmd "play"
    :docstring "Plays the first song in the queue if nothing is playing"
    :changecomms true
    :action #'audio/play}

   ;; Music Controls
   {:match #"nowplaying"
    :doccmd "nowplaying"
    :docstring "Sends the current playing song to the channel"
    :changecomms true
    :action #'audio/now-playing}

   {:match #"next"
    :doccmd "next"
    :docstring "Play's the next song in the queue"
    :changecomms true
    :action #'audio/next-track}

   {:match #"pause"
    :doccmd "pause"
    :docstring "Pause the current song"
    :changecomms true
    :action #'audio/pause-track}

   {:match #"resume"
    :doccmd "resume"
    :docstring "Resume the in-play song"
    :changecomms true
    :action #'audio/resume-track}

   {:match #"shuffle"
    :doccmd "shuffle"
    :docstring "Shuffle the current song list"
    :changecomms true
    :action #'qm/shuffle-queue}

   ;; Bot Controls
   {:match #"join"
    :doccmd "join <voice_channel>"
    :docstring "I join the specified channel"
    :submenu
    [{:match #".*"
      :changecomms true
      :action #'bot/join-voice-channel}]}

   ;; xkcd Comics
   {:match #"xkcd"
    :submenu
    [{:match #"random"
      :doccmd "xkcd random"
      :docstring "Sends a random xkcd to the channel"
      :action #'xkcd/random-xkcd}

     {:match #".*"
      :doccmd "xkcd <xkcd_id>"
      :docstring "Send's a specific xkcd to the channel"
      :action #'xkcd/specific-xkcd}]}

   ;; Help
   {:match #"help"
    :doccmd "help"
    :docstring "Send's this help message to the channel"
    :action #'send-help}])

(defn rollup-help [menu]
  (if-not (:docstring menu)
    (mapcat rollup-help (:submenu menu))
    (concat
      [(select-keys menu [:doccmd :docstring])]
      (mapcat rollup-help (:submenu menu)))))

(defn send-help
  [{:keys [cmd-prefix] :as ctx}
   ^GuildMessageReceivedEvent event
   menu]
  (let [message (.getMessage event)
        textchannel (.getTextChannel message)

        help-maps
        (mapcat rollup-help command-menu)

        codeblocktext
        (->
          (fn [t]
            {(str cmd-prefix (get t :doccmd)) (get t :docstring)})
          (map help-maps)
          (json/write-str)
          (str/replace #"},\{" "},\n {") ; Drop each item on to its own line
          )

        message
        (doto (MessageBuilder.)
          (.append (str "\nThis is what I can do\n"))
          (.appendCodeBlock codeblocktext "json"))]

    (tu/send-message textchannel
      (.build message))))

(defn menu-match [{:keys [cmd-prefix] :as ctx} selected-menu event message]
  (println "Running matcher against: " message " " cmd-prefix)

  (let [player-atom (get-in ctx [:player])
        message-parts (str/split message #" ")
        cmd (first message-parts)

        ; Strip the command character off the message before starting to match
        message-parts
        (if (= cmd-prefix (str (first cmd)))
          (assoc message-parts 0 (str/replace-first cmd cmd-prefix ""))
          message-parts)

        {action-fn :action changecomms? :changecomms :as selected-menu}
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

    (when changecomms?
      (tu/set-new-chat-event player-atom event))

    (when action-fn
      (println "Running action for: " selected-menu)
      (action-fn ctx event selected-menu))))


(defn is-playing-embed? [^MessageEmbed embed]
  (= "Now Playing" (.getTitle embed)))

(defn is-playing-message? [^ReceivedMessage message]
  (= (.getContentStripped message) "Now Playing... Loading"))

(defn bot-received [ctx event]
  (let [^ReceivedMessage message (.getMessage event)
        embeds (.getEmbeds message)
        player-atom (get-in ctx [:player])]

    (cond
      (is-playing-message? message)
      (swap! player-atom assoc
        :nowplaying-event event)

      (pos? (count embeds))
      (doseq [e embeds]
        (when (is-playing-embed? e)
          ;; TODO: This is our playing message, add reactions here for control
          ;; (Play / Pause) / Next / Vote
          (swap! player-atom assoc
            :nowplaying-event event))))))


(defmulti handle-event (fn [req] (class (:event req))))

(defmethod handle-event :default [{:keys [event]}]
  #_(println "Unhandled event class:" (class event)))

;; On Message Received
(defmethod handle-event GuildMessageReceivedEvent
  [{:keys [^GuildMessageReceivedEvent event]
    {:keys [cmd-prefix] :as ctx} :ctx
    :as req}]
  (println "Received Message")
  (let [^ReceivedMessage message (.getMessage event)
        author (.getAuthor event)


        is-this-in-menu?
        (fn [message]
          (let [first-word (first (str/split (.getContentStripped message) #" "))
                first-menu (map #(get % :match) command-menu)]

            (reduce
              (fn [acc regex]
                (let [m (re-matches regex first-word)]
                  (if (nil? m)acc m)))
              first-menu)))]

    (tu/update-chat-event ctx event)

    (cond

      ;; TODO: If this is _my_ now playing message, bookmark it so it's editable later on
      ;(and (.isBot author) (tu/isitme? ctx (.getName author)))
      ;(bot-received ctx event)

      ;; Ignore Other Bots
      (.isBot author)
      nil

      (not (str/starts-with? (.getContentStripped message) cmd-prefix))
      (println "Ignoring, not a command")

      (is-this-in-menu? message)
      (menu-match ctx command-menu event (.getContentStripped message))


      :else
      (println "Nothing"))))


;; On Add Reaction
(defmethod handle-event MessageReactionAddEvent
  [event]

  (println event))