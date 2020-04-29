(ns leadbot.text-utils)

(defn send-message [textchannel message]
  (println message)
  (doto (.sendMessage textchannel message)
    (.queue)))
