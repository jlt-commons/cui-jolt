(ns assistant
  "A port of CUI's examples/assistant.c: a chat app with reasoning, tool
  calls, a plan, sources and a composer. Sending a message plays a short
  scripted answer. The workspace panel closes below 1180 points and the chat
  list becomes a drawer below 760. Run: jolt -A:examples -m assistant"
  (:require [cui.core :as ui]
            [cui.raw :as raw]))

(def state
  {:chat (atom 0)
   :draft (atom "Now compare these numbers with last quarter.")
   :asked (atom "")
   :model (atom 0)
   :vote (atom 0)
   :started (atom -1.0)      ; when the current answer began, < 0 idle
   :asked-once (atom false)
   :files (atom ["quarterly-report.pdf" "metrics.csv"])
   :removed (atom -1)
   :narrow (atom false)      ; a phone-sized window
   :drawer (atom false)})    ; the chat list on a narrow window

(def models
  [{:name "Atlas" :detail "Longer answers, reads files and code" :badge "New" :icon :layers}
   {:name "Atlas Fast" :detail "Short answers in about a second" :icon :zap}
   {:name "Atlas Mini" :detail "Runs on your own machine" :icon :box}])

(def sources
  [{:title "Immediate mode GUIs and why they keep coming back" :url "https://fieldnotes.dev/immediate-mode"
    :snippet "Retained and immediate mode toolkits and the trade offs between them."}
   {:title "Notes on subpixel text positioning" :url "https://glyphlab.io/notes/subpixel"
    :snippet "Shaping, hinting and subpixel positioning explained with examples."}
   {:title "Spring physics for interface motion" :url "https://motionbook.dev/springs"
    :snippet "Why spring physics feel more natural than cubic beziers for interface motion."}])

(def answer
  (str "Mostly because they do very little per frame. The interface is rebuilt from your data each frame, laid out "
       "in one pass and drawn in a handful of batches, so there is no tree of widget objects to keep in sync.\n\n"
       "Text is the expensive part, which is why glyphs are rasterized once into an atlas and reused. And motion "
       "feels quick when it is driven by springs that respond to input at once, instead of fixed length curves."))

(def today ["Why immediate mode feels fast" "Quarterly report summary" "Spring presets for menus"])
(def week ["Gantt chart layout ideas" "Kanban drag and drop" "Color palette for charts" "Release notes draft"])

(defn sidebar [u drawer?]
  (let [{:keys [chat drawer asked-once started draft]} state]
    (ui/column u {:width (if drawer? :fill 264) :height :fill :bg (when-not drawer? :surface-muted)
                  :pad (if drawer? 0 12) :gap 2}
      (ui/row u {:gap 10 :pad-x 6 :pad-top 4 :pad-bottom 10 :align :center}
        (ui/box u {:width 28 :height 28 :radius 9 :bg :accent :dir :stack :align :center :justify :center}
          (ui/text u "M" {:font :display :size 16 :color :accent-foreground :line-height 1.0}))
        (ui/text u "Muse" {:font :medium :size 15 :width :fill :nowrap true})
        (when (ui/icon-button u :square-pen {:size :sm :tooltip "New chat"})
          (reset! asked-once false) (reset! started -1.0) (reset! draft "")))
      (doseq [[section items offset] [["Today" today 0] ["Previous 7 days" week 3]]]
        (when (pos? offset) (ui/box u {:height 10}))
        (ui/nav-section u section)
        (doseq [[i label] (map-indexed vector items)
                :let [n (+ offset i)]]
          (when (ui/nav-item u label {:icon :message-square :selected (= @chat n) :group "chats"})
            (reset! chat n) (reset! drawer false))))
      (ui/spacer u)
      (ui/card u {:pad 14 :gap 8}
        (ui/row u {:gap 8 :align :center}
          (ui/icon u :zap 14 :accent)
          (ui/text u "Usage this month" {:font :medium :size 13 :width :fill}))
        (ui/progress u 0.42 {:height 6})
        (ui/text u "4,210 of 10,000 messages" {:size 12 :color :text-secondary}))
      (ui/box u {:height 6})
      (ui/user-menu u "Mara Quinn" {:detail "mara@muse.dev" :status "online"}
        (ui/menu-item u "Settings" {:icon :settings})
        (ui/menu-item u "Keyboard shortcuts" {:icon :keyboard})
        (ui/menu-separator u)
        (ui/menu-item u "Log out" {:icon :log-out})))))

(defn answer-block [u key t live?]
  (ui/push-id u key)
  (let [thinking (and live? (< t 2.6))]
    (ui/reasoning u "think" {:thinking thinking :seconds (if live? (if thinking t 2.6) 6)}
      (ui/text u "This is about perceived speed. Two parts: how little work a frame does, and how fast input reaches the screen."
               {:size 13.5 :color :text-secondary})
      (ui/text u "Find sources on retained against immediate mode, text rendering cost and interface motion."
               {:size 13.5 :color :text-secondary})))
  (when (or (not live?) (> t 1.2))
    (let [st (if (and live? (< t 3.6)) :running :done)]
      (ui/tool-call u "search" "web_search"
                    {:icon :globe :detail "immediate mode gui performance" :status st
                     :seconds (if (= st :done) 1.4 (- t 1.2))
                     :input "{\"query\": \"immediate mode gui performance\", \"limit\": 5}"
                     :output "{\"results\": 3, \"took_ms\": 1380}"})))
  (when (and live? (> t 2.6) (< t 4.2))
    (ui/agent-status u "Reading 3 sources" {:status :running :seconds (- t 2.6)}))
  (when (or (not live?) (> t 3.6))
    (if live?
      (ui/typewriter u "answer" answer {:size 15 :speed 140 :caret true})
      (ui/text u answer {:size 15}))
    (when (or (not live?) (> t 6.5))
      (ui/row u {:gap 6 :align :center}
        (doseq [k (range 3)] (ui/citation u (inc k) (sources k))))
      (ui/source-list u "sources" sources)
      (let [act (ui/response-actions u "actions" {:copy-text answer :vote (:vote state)})]
        (when (pos? (bit-and act raw/CUI_RESPONSE_REGENERATE)) (reset! (:started state) (ui/time u)))
        (when (pos? (bit-and act raw/CUI_RESPONSE_SHARE)) (ui/toast u "Link copied" {:tone :success})))))
  (ui/pop-id u))

(def parts [{:label "Instructions" :value 3200} {:label "Files" :value 41800} {:label "Messages" :value 68400}])

(defn elapsed [u] (let [s @(:started state)] (if (>= s 0) (- (ui/time u) s) 99.0)))

(defn conversation [u]
  (let [{:keys [narrow drawer model draft asked asked-once started files removed]} state
        n? @narrow
        t (elapsed u)]
    (ui/column u {:width :fill :height :fill}
      (ui/row u {:width :fill :pad-x (if n? 12 20) :pad-y 12 :gap (if n? 6 10) :align :center}
        (when (and n? (ui/icon-button u :menu {:tooltip "Chats"})) (reset! drawer true))
        (ui/model-picker u "model" models model {:ghost true :placement :bottom-start})
        (ui/spacer u)
        (ui/context-meter u "ctx" 113400 200000 {:parts parts})
        (if n?
          (ui/icon-button u :share-2 {:tooltip "Share"})
          (ui/button u "Share" {:icon :share-2 :variant :outline :size :sm})))
      (ui/separator u)
      (ui/scroll u {:id "chat" :width :fill :height :fill :fade-edges true}
        (ui/row u {:width :fill :justify :center :pad-x (if n? 12 24) :pad-y (if n? 16 24)}
          (ui/column u {:width :fill :max-width 760 :gap 14}
            (ui/chat-message u "Why do immediate mode toolkits feel so fast? Cite a few sources." {:mine true})
            (answer-block u "first" t false)
            (when @asked-once
              (ui/gap u 12)
              (ui/chat-message u @asked {:mine true})
              (answer-block u "second" t true)))))
      (ui/row u {:width :fill :justify :center :pad-x (if n? 12 24) :pad-bottom (if n? 12 20)}
        (ui/column u {:width :fill :max-width 760 :gap 8}
          (let [busy (and @asked-once (< t 7.5))
                ev (ui/chat-composer u "composer" draft
                                     {:placeholder "Ask a follow up" :busy busy
                                      :attachments @files :removed removed
                                      :models models :model model})
                on? #(pos? (bit-and ev %))]
            (when (on? raw/CUI_COMPOSER_SEND)
              (reset! asked @draft) (reset! draft "")
              (reset! asked-once true) (reset! started (ui/time u)))
            (when (on? raw/CUI_COMPOSER_STOP) (reset! started (- (ui/time u) 99)))
            (when (and (on? raw/CUI_COMPOSER_REMOVE) (< -1 @removed (count @files)))
              (swap! files #(into (subvec % 0 @removed) (subvec % (inc @removed)))))
            (when (on? raw/CUI_COMPOSER_ATTACH) (ui/toast u "Drop files on the window to attach them")))
          (ui/text u "Answers can be wrong. Each one links the sources it used."
                   {:size 12 :color :text-muted :align :center :width :fill}))))))

(def file-rows [["quarterly-report.pdf" "2.4 MB"] ["metrics.csv" "86 KB"] ["notes.md" "4 KB"]])
(def suggestions [{:icon :chart-bar :title "Compare with last quarter" :detail "Revenue, churn and costs"}
                  {:icon :list-checks :title "Turn this into a checklist"}])

(defn side-panel [u]
  (let [t (if @(:asked-once state) (elapsed u) 99.0)
        step (fn [done running] (cond (> t done) :done (> t running) :running :else :pending))]
    (ui/column u {:width 330 :height :fill :bg :surface :pad 20 :gap 16}
      (ui/text u "Workspace" {:font :medium :size 15})
      (ui/agent-plan u "plan"
                     [{:title "Search for sources" :detail "3 results" :status (step 3.6 -1)}
                      {:title "Read the sources" :detail (if (> t 4.2) "3 of 3" "Reading") :status (step 4.2 2.6)}
                      {:title "Write the answer" :status (step 7.5 3.6)}
                      {:title "Check the citations" :status (step 7.5 99)}]
                     {:title "Plan"})
      (ui/label u "Files")
      (ui/column u {:gap 6 :width :fill}
        (doseq [[k [fname fsize]] (map-indexed vector file-rows)]
          (ui/push-id-int u k)
          (ui/row u {:gap 10 :align :center :width :fill :pad 8 :radius 12 :bg :surface-muted}
            (ui/icon u :file-text 16 :text-secondary)
            (ui/text u fname {:size 13 :width :fill :nowrap true})
            (ui/text u fsize {:size 12 :color :text-muted}))
          (ui/pop-id u)))
      (ui/label u "Try asking")
      (let [pick (ui/prompt-suggestions u "sugg" suggestions {:columns 1})]
        (when (>= pick 0) (reset! (:draft state) (:title (suggestions pick))))))))

(defn frame [u]
  (let [vw (:x (ui/viewport-size u))
        {:keys [narrow drawer]} state]
    (reset! narrow (< vw 760))
    (when-not @narrow (reset! drawer false))
    (ui/row u {:width :fill :height :fill :align :stretch :bg :background}
      (when-not @narrow (sidebar u false) (ui/separator u))
      (conversation u)
      (when (>= vw 1180) (ui/separator u) (side-panel u)))
    (ui/sheet u "Muse" drawer {:side :left :size 300}
      (sidebar u true))))

(defn -main [& _]
  (ui/run {:title "Muse" :width 1440 :height 900 :theme :dark :accent :neutral} frame))
