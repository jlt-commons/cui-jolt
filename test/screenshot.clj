(ns screenshot
  "Renders an example's frame fn into a PNG-ready RGBA file, with a hidden window.
  jolt -A:test:examples -m screenshot dashboard/frame 980 860 out.rgba [dark]
  Set CUI_SCALE=2 for a retina-sized image."
  (:require [cui.core :as ui]
            [cui.raw :as raw]))

(defn -main [sym w h out & [theme]]
  (let [sym (symbol sym)
        _ (require (symbol (namespace sym)))
        frame @(resolve sym)
        w (parse-long w) h (parse-long h)
        n (atom 0)]
    (ui/run {:title "shot" :width w :height h :hidden true :renderer :software
             :theme (keyword (or theme "light")) :accent :blue}
      (fn [u]
        (frame u)
        (when (= 30 (swap! n inc))       ; let entrance animations settle
          (let [[px pw ph] (ui/screenshot (ui/current-app))]
            (with-open [o (java.io.FileOutputStream. out)] (.write o px))
            (println "size" pw ph))
          (ui/quit!))
        (raw/cui_request_redraw u)))
    (println "wrote" out)))
