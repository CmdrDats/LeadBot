(defproject leadbot "1.0.0"
  :description "A great, no-nonsense discord music player that doesn't forget."
  :url "http://example.com/FIXME"
  :license
  {:name "Eclipse Public License"
   :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [net.dv8tion/JDA "4.1.1_140"]
   [com.sedmelluq/lavaplayer "1.3.47"]
   [com.sedmelluq/lavaplayer-natives-extra "1.3.13"]
   [com.sedmelluq/jda-nas "1.1.0"]
   [com.jagrosh/JLyrics "0.4"]
   [nrepl "0.6.0"]]
  :repositories
  [["bintray" {:id "bintray" :url "https://jcenter.bintray.com"}]
   ["bintray-sedmelluq" {:id "bintray-sedmelluq" :url "https://dl.bintray.com/sedmelluq/com.sedmelluq"}]]
  :main leadbot.core)
