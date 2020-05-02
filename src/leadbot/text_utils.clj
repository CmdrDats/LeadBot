(ns leadbot.text-utils
  (:import
    (net.dv8tion.jda.internal.entities TextChannelImpl)))

;; Bot Name
(def myname "LeadBoat")

(defn isitme? [botname]
  (= botname myname))

(defn send-message [^TextChannelImpl textchannel message]
  (println message)
  (.queue
    (.sendMessage textchannel message)))
