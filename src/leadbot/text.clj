(ns leadbot.text
  (:require
    [clojure.string :as str]
    [leadbot.audio :as audio]
    [leadbot.bot :as bot]
    [leadbot.playlist :as pl]
    [leadbot.text-utils :as tu]
    [leadbot.xkcd :as xkcd]
    [leadbot.queue-manager :as qm])
  (:import
    (com.sedmelluq.discord.lavaplayer.player DefaultAudioPlayerManager AudioLoadResultHandler)
    (net.dv8tion.jda.api.events.message.guild GuildMessageReceivedEvent)
    (net.dv8tion.jda.api.events.message.react MessageReactionAddEvent)
    (net.dv8tion.jda.internal.entities ReceivedMessage)
    (net.dv8tion.jda.api.entities MessageEmbed)))

(declare send-help)

;; TODO: Implement default actions when no submenu matched
(def command-menu
  [;; Playlist Management
   {:match #"!playlist"
    :submenu
    [{:match #"add"
      :submenu
      [{:match #".*"
        :doccmd "!playlist add <url>"
        :docstring "Add the url (YouTube / Soundcloud / etc) to your playlist"
        :action #'pl/add-song}]}

     {:match #"list"
      :doccmd "!playlist list"
      :docstring "Lists the songs on your playlist"
      :action #'pl/list-my-songs
      #_:submenu
      #_[{:match #".*"
          :doccmd "!playlist list <playlistname/username>"
          :docstring "List the playlist for <playlistname/username>"
          :action #'pl/list-songs}]}]}

   {:match #"!load"
    :doccmd "!load"
    :docstring "Load my playlist into the queue"
    :action #'pl/load-my-playlist
    #_:submenu
    #_[{:match #".*"
        :doccmd "!load <playlist>"
        :docstring "Load the <playlist> into the queue"
        :action #'pl/load-playlist}]}

   ;; Queue Management
   {:match #"!queue"
    :doccmd "!queue"
    :docstring "Print the current song queue"
    :action #'qm/print-queue}

   ;; Music Controls
   {:match #"!nowplaying"
    :doccmd "!nowplaying"
    :docstring "Sends the current playing song to the channel"
    :action #'audio/now-playing}

   {:match #"!next"
    :doccmd "!next"
    :docstring "Play's the next song in the queue"
    :action #'audio/next-track}

   {:match #"!pause"
    :doccmd "!pause"
    :docstring "Pause the current song"
    :action #'audio/pause-track}

   {:match #"!resume"
    :doccmd "!resume"
    :docstring "Resume the in-play song"
    :action #'audio/resume-track}

   ;; Bot Controls
   {:match #"!join"
    :doccmd "!join <voice_channel>"
    :docstring (str tu/myname " joins the specified channel")
    :submenu
    [{:match #".*"
      :action #'bot/join-voice-channel}]}

   ;; xkcd Comics
   {:match #"!xkcd"
    :submenu
    [{:match #"random"
      :doccmd "!xkcd random"
      :docstring "Sends a random xkcd to the channel"
      :action #'xkcd/random-xkcd}

     {:match #".*"
      :doccmd "!xkcd <xkcd_id>"
      :docstring "Send's a specific xkcd to the channel"
      :action #'xkcd/specific-xkcd}]}


   {:match #"!help"
    :doccmd "!help"
    :docstring "Send's this help message to the channel"
    :action #'send-help}])

(defn rollup-help [menu]
  (if-not (:docstring menu)
    (mapcat rollup-help (:submenu menu))
    (concat
      [(select-keys menu [:doccmd :docstring])]
      (mapcat rollup-help (:submenu menu)))))

(defn send-help [ctx ^GuildMessageReceivedEvent event menu]
  (let [message (.getMessage event)
        member (.getMember event)
        textchannel (.getTextChannel message)

        help-maps
        (mapcat rollup-help command-menu)

        ;; TODO: Maybe we can fix the visibility here.
        ;; Maybe make it a block where all the dockstring line up on the right?
        help-list
        (map
          #(str
             (get % :doccmd) " - "
             (get % :docstring) "\n")
          help-maps)]

    (tu/send-message textchannel
      (str
        "Below you'll find out what I can do\n"
        (str/join "" help-list)))))

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


(defn is-playing-message? [^MessageEmbed embed]
  (= "Now Playing" (.getTitle embed)))

(defn bot-received [ctx event]
  (let [^ReceivedMessage message (.getMessage event)
        author (.getAuthor event)
        embeds (.getEmbeds message)
        player-atom (get-in ctx [:player])]

    (when (pos? (count embeds))
      (doseq [e embeds]
        (when (is-playing-message? e)
          ;; TODO: This is our playing message, add reactions here for control
          ;; (Play / Pause) / Next / Vote
          (swap! player-atom assoc
            :nowplaying-event event))))))


(defmulti handle-event (fn [req] (class (:event req))))

(defmethod handle-event :default [{:keys [event]}]
  (println "Unhandled event class:" (class event)))

;; On Message Received
(defmethod handle-event GuildMessageReceivedEvent
  [{:keys                                                      [^GuildMessageReceivedEvent event]
    {:keys [^DefaultAudioPlayerManager playermanager] :as ctx} :ctx
    :as                                                        req}]
  (println "Received Message")
  (let [^ReceivedMessage message (.getMessage event)
        author (.getAuthor event)

        menu-action
        (future (menu-match ctx command-menu event (.getContentStripped message)))]

    (cond

      ;; TODO: If this is _my_ now playing message, bookmark it so it's editable later on
      (and (.isBot author) (tu/isitme? (.getName author)))
      (bot-received ctx event)

      ;; Ignore Other Bots
      (.isBot author)
      nil

      (not (str/starts-with? (.getContentStripped message) "!"))
      (println "Ignoring, not a command")

      @menu-action
      @menu-action


      :else
      (println "Nothing"))))


;; On Add Reaction
(defmethod handle-event MessageReactionAddEvent
  [event]

  (println event))