(ns hello
  "The CUI hello example, in Jolt. Run: jolt -A:examples -m hello"
  (:require [cui.core :as ui]))

(def notifications (atom true))
(def clicks (atom 0))

(defn frame [u]
  (ui/column u {:pad 32 :gap 16}
    (ui/heading u "Hello, CUI" 2)
    (ui/secondary u "A native window, a few components and no dependencies.")
    (ui/switch u "Notifications" notifications)
    (ui/row u {:gap 8}
      (when (ui/button u "Click me" {:variant :primary})
        (swap! clicks inc))
      (ui/textf u "Clicked %d times" @clicks))))

(defn -main [& _]
  (ui/run {:title "Hello" :width 420 :height 300} frame))
