(ns interaction-test
  "Drives CUI with synthetic input and checks that its changes reach the
  atoms. Needs no display: jolt -A:test -m interaction-test"
  (:require [cui.core :as ui]
            [cui.raw :as raw]))

(def on (atom true))
(def clicks (atom 0))
(def text (atom "hi"))
(def tab (atom 0))
(def vol (atom 0.25))
(def dialog-open (atom false))
(def dialog-seen (atom 0))
(def term (ui/terminal-buffer 4096 64))
(def commands (atom []))
(def failures (atom []))

(defn check [label ok] (when-not ok (swap! failures conj label)))

(defn center [r] [(+ (:x r) (/ (:w r) 2)) (+ (:y r) (/ (:h r) 2))])

(def rects (atom {}))

(defn frame [u]
  (ui/column u {:pad 24 :gap 12 :width 360}
    (ui/switch u "Notifications" on)
    (swap! rects assoc :switch (ui/last-rect u))
    (when (ui/button u "Click me" {:variant :primary :icon :plus})
      (swap! clicks inc))
    (swap! rects assoc :button (ui/last-rect u))
    (ui/input u "name" text {:placeholder "Name" :width :fill})
    (swap! rects assoc :input (ui/last-rect u))
    (ui/tabs u "tabs" ["One" "Two" "Three"] tab)
    (swap! rects assoc :tabs (ui/last-rect u))
    (ui/slider u "Volume" vol 0 1 {:show-value true})
    (when-let [c (:command (ui/terminal u "term" term {:height 120 :width :fill}))]
      (swap! commands conj c))
    (swap! rects assoc :term (ui/last-rect u)))
  (ui/dialog u "Confirm" dialog-open {:description "Scope macro test"}
    (swap! dialog-seen inc)
    (ui/text u "Body runs only while open")))

(defn move [u [x y]] (raw/cui_input_mouse_move u (float x) (float y)))
(defn press [u] (raw/cui_input_mouse_button u 0 true))
(defn release [u] (raw/cui_input_mouse_button u 0 false))

(defn tab-two [r] (let [{:keys [x y w h]} r] [(+ x (* w 0.5)) (+ y (/ h 2))]))

;; Input is applied BETWEEN frames, the way a platform layer feeds CUI: the
;; pointer moves, then presses, then releases, each before its own frame.
(def script
  {2 #(move % (center (:switch @rects)))  3 press  4 release
   6 #(move % (center (:button @rects)))  7 press  8 release
   10 #(move % (center (:input @rects))) 11 press 12 release
   14 #(ui/input-text % "!")
   16 #(move % (tab-two (:tabs @rects)))  17 press 18 release
   ;; open the modal dialog, then Escape closes it and CUI clears the flag
   20 (fn [_] (reset! dialog-open true))
   28 #(raw/cui_input_key % raw/CUI_KEY_ESCAPE true 0)
   29 #(raw/cui_input_key % raw/CUI_KEY_ESCAPE false 0)
   ;; after the dialog has animated out, type a command into the terminal;
   ;; it comes back exactly once as :command
   42 #(move % (center (:term @rects))) 43 press 44 release
   46 #(ui/input-text % "ls")
   48 #(raw/cui_input_key % raw/CUI_KEY_ENTER true 0)
   49 #(raw/cui_input_key % raw/CUI_KEY_ENTER false 0)})

(defn -main [& _]
  ;; A bare context: no window, no renderer. This is the integration path in
  ;; CUI's docs (your own window and input), so it needs no display at all.
  (let [u (ui/create {:theme :light})]
    (ui/set-viewport u 420 600 1)
    (dotimes [i 55]
      (when-let [f (script i)] (f u))
      (ui/with-frame
        (ui/begin-frame u (/ 1.0 60))
        (frame u)
        (when (System/getenv "DEBUG") (println i @rects))
        (ui/end-frame u)))
    (ui/destroy u))
  (check "switch toggled off" (= false @on))
  (check "button clicked once" (= 1 @clicks))
  (check "text typed into atom" (= "hi!" @text))
  (check "tab index moved" (= 1 @tab))
  (check "slider untouched" (= 0.25 @vol))
  (check "dialog body ran while open" (pos? @dialog-seen))
  (check "escape closed dialog via bool*" (= false @dialog-open))
  (check "terminal returned the typed command" (= ["ls"] @commands))
  (println {:on @on :clicks @clicks :text @text :tab @tab :vol @vol :dialog-open @dialog-open :dialog-frames @dialog-seen :commands @commands})
  (if (seq @failures)
    (do (println "FAIL" @failures) (System/exit 1))
    (println "interaction test passed")))
