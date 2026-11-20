(ns assistant
  "A chat app that talks to Claude through the Agent Client Protocol: it starts
  `claude-agent-acp` (via npx) and streams reasoning, tool calls, plans and
  answers into CUI's chat components. Each chat is an ACP session working in
  the given directory. The workspace panel closes below 1180 points and the
  chat list becomes a drawer below 760.
  Run: jolt -A:examples -m assistant [working-dir]"
  (:require [acp]
            [cui.core :as ui]
            [cui.raw :as raw]
            [clojure.string :as str]))

(def agent-cmd ["npx" "-y" "@agentclientprotocol/claude-agent-acp"])

(def conn (atom nil))
(def status (atom "Starting Claude…"))  ; nil once the agent is ready
(def cwd (atom (System/getProperty "user.dir")))
(def chats (atom []))    ; [{:sid :title :turns [..] :busy :plan [..] :usage {..}}]
(def current (atom 0))
(def draft (atom ""))
(def models (atom []))   ; the agent's "model" config options
(def model (atom 0))
(def perms (atom []))    ; queued session/request_permission requests
(def perm-open (atom false))
(def version (atom 0))   ; bumps on every agent update, drives autoscroll
(def seen (atom -1))
(def narrow (atom false))
(def drawer (atom false))

(defn now [] (System/currentTimeMillis))

;; ---- chat state, updated from the ACP reader thread

(defn chat-idx [sid] (first (keep-indexed #(when (= sid (:sid %2)) %1) @chats)))

(defn update-chat! [sid f & args]
  ;; ponytail: chats are only ever appended, so an index stays valid
  (when-let [i (chat-idx sid)]
    (swap! chats #(apply update % i f args))
    (swap! version inc)))

(defn update-turn!
  "Applies f to the chat's last turn when it is the agent's."
  [sid f]
  (update-chat! sid update :turns
                (fn [ts] (if (= :agent (:role (peek ts))) (conj (pop ts) (f (peek ts))) ts))))

(defn add-item [turn item]
  (-> turn (update :items conj item) (update :thought-end #(or % (now)))))

(defn add-text [turn text]
  (let [last* (peek (:items turn))]
    (if (= :text (:type last*))
      (assoc-in turn [:items (dec (count (:items turn))) :text] (str (:text last*) text))
      (add-item turn {:type :text :text text}))))

(defn tool-output [{:keys [content rawOutput]}]
  (let [parts (keep (fn [{:keys [type content path]}]
                      (case type
                        "content" (:text content)
                        "diff" (str "Edited " path)
                        nil))
                    content)]
    (cond (seq parts) (str/join "\n" parts)
          (string? rawOutput) rawOutput
          rawOutput (acp/write-json rawOutput))))

(defn update-tool [turn {:keys [toolCallId title kind status rawInput] :as u}]
  (update turn :items
          (fn [items]
            (mapv (fn [it]
                    (if (= toolCallId (:id it))
                      (cond-> it
                        title (assoc :title title)
                        kind (assoc :kind kind)
                        status (assoc :status status)
                        (seq rawInput) (assoc :input (acp/write-json rawInput))
                        (tool-output u) (assoc :output (tool-output u))
                        (#{"completed" "failed"} status) (assoc :t1 (now)))
                      it))
                  items))))

(defn on-update [{:keys [sessionId update]}]
  (let [u update]
    (case (:sessionUpdate u)
      "agent_message_chunk" (when-let [t (-> u :content :text)] (update-turn! sessionId #(add-text % t)))
      "agent_thought_chunk" (when-let [t (-> u :content :text)] (update-turn! sessionId #(update % :thought str t)))
      "tool_call" (update-turn! sessionId
                                #(update-tool (add-item % {:type :tool :id (:toolCallId u) :title (:title u)
                                                           :status "pending" :t0 (now)})
                                              u))
      "tool_call_update" (update-turn! sessionId #(update-tool % u))
      "plan" (update-chat! sessionId assoc :plan (:entries u))
      "usage_update" (update-chat! sessionId assoc :usage (select-keys u [:used :size :cost]))
      "session_info_update" (when (:title u) (update-chat! sessionId assoc :title (:title u)))
      nil)))

(defn on-request [c id method params]
  (if (= method "session/request_permission")
    (do (swap! perms conj {:id id :sid (:sessionId params)
                           :title (or (-> params :toolCall :title) "Allow this action?")
                           :options (:options params)})
        (reset! perm-open true))
    (acp/respond-error! c id -32601 (str "Method not supported: " method))))

(defn answer-permission! [outcome]
  (let [p (first @perms)]
    (swap! perms subvec 1)
    (reset! perm-open (boolean (seq @perms)))
    (acp/respond! @conn (:id p) {:outcome outcome})))

;; ---- talking to the agent

(defn set-model! [sid]
  (when-let [m (get @models @model)]
    (acp/request! @conn "session/set_config_option" {:sessionId sid :configId "model" :value (:value m)}
                  (fn [_ e] (when e (update-chat! sid update :turns conj
                                                  {:role :agent :done true
                                                   :items [{:type :error :text (str "Model: " (:message e))}]}))))))

(defn new-chat! []
  (let [i (count @chats)]
    (swap! chats conj {:title "New chat" :turns [] :plan []})
    (reset! current i)
    (acp/request! @conn "session/new" {:cwd @cwd :mcpServers []}
                  (fn [r e]
                    (if e
                      (reset! status (str "Could not start a session: " (:message e)))
                      (let [sid (:sessionId r)
                            opt (first (filter #(= "model" (:id %)) (:configOptions r)))]
                        (swap! chats assoc-in [i :sid] sid)
                        (when (and opt (empty? @models))
                          (reset! models (:options opt))
                          (reset! model (or (first (keep-indexed #(when (= (:value %2) (:currentValue opt)) %1)
                                                                 (:options opt)))
                                            0)))
                        (when (and opt (not= (:value (get @models @model)) (:currentValue opt)))
                          (set-model! sid))
                        (swap! version inc)))))))

(defn send-prompt! [text]
  (let [{:keys [sid busy]} (get @chats @current)]
    (when (and sid (not busy) (not (str/blank? text)))
      (update-chat! sid #(-> %
                             (assoc :busy true)
                             (update :turns conj {:role :user :text text}
                                     {:role :agent :thought "" :items [] :t0 (now)})))
      (acp/request! @conn "session/prompt" {:sessionId sid :prompt [{:type "text" :text text}]}
                    (fn [r e]
                      (update-turn! sid #(cond-> (assoc % :done true :t1 (now))
                                           e (add-item {:type :error :text (:message e)})
                                           (= "cancelled" (:stopReason r)) (add-item {:type :note :text "Stopped"})))
                      (update-chat! sid assoc :busy false))))))

(defn cancel! []
  (when-let [sid (:sid (get @chats @current))]
    (acp/notify! @conn "session/cancel" {:sessionId sid})
    (doseq [p @perms :when (= sid (:sid p))] (answer-permission! {:outcome "cancelled"}))))

(defn start! []
  (let [c (acp/connect agent-cmd {:notify (fn [m p] (when (= m "session/update") (on-update p)))
                                  :request on-request})]
    (reset! conn c)
    (acp/request! c "initialize" {:protocolVersion 1
                                  :clientCapabilities {:fs {:readTextFile false :writeTextFile false}
                                                       :terminal false}
                                  :clientInfo {:name "cui-jolt-assistant" :version "0.1"}}
                  (fn [_ e]
                    (if e
                      (reset! status (str "Claude did not start: " (:message e)))
                      (do (reset! status nil) (new-chat!)))))))

;; ---- UI

(defn sidebar [u drawer?]
  (ui/column u {:width (if drawer? :fill 264) :height :fill :bg (when-not drawer? :surface-muted)
                :pad (if drawer? 0 12) :gap 2}
    (ui/row u {:gap 10 :pad-x 6 :pad-top 4 :pad-bottom 10 :align :center}
      (ui/box u {:width 28 :height 28 :radius 9 :bg :accent :dir :stack :align :center :justify :center}
        (ui/text u "M" {:font :display :size 16 :color :accent-foreground :line-height 1.0}))
      (ui/text u "Muse" {:font :medium :size 15 :width :fill :nowrap true})
      (when (and (ui/icon-button u :square-pen {:size :sm :tooltip "New chat"}) (nil? @status))
        (new-chat!) (reset! drawer false)))
    (ui/nav-section u "Chats")
    (doseq [[i {:keys [title busy]}] (reverse (map-indexed vector @chats))]
      (ui/push-id-int u i)
      (when (ui/nav-item u title {:icon :message-square :selected (= @current i) :group "chats"
                                  :badge (when busy "…")})
        (reset! current i) (reset! drawer false))
      (ui/pop-id u))
    (ui/spacer u)
    (ui/row u {:gap 8 :align :center :pad-x 6}
      (ui/icon u (if @status :circle-alert :sparkles) 14 (if @status :text-muted :accent))
      (ui/text u (or @status "Claude via ACP") {:size 12 :color :text-secondary :width :fill}))))

(defn secs [t0 t1] (/ (- (or t1 (now)) t0) 1000.0))

(def tool-icons {"read" :file-text "edit" :pencil "delete" :trash-2 "move" :arrow-right "search" :search
                 "execute" :terminal "fetch" :globe "think" :sparkles})

(def tool-status {"pending" :pending "in_progress" :running "completed" :done "failed" :failed})

(defn agent-turn [u k {:keys [thought items done t0 thought-end]} prompt]
  (ui/id-scope u (str "turn" k)
    (when (seq thought)
      (ui/reasoning u "think" {:thinking (and (not done) (nil? thought-end))
                               :seconds (secs t0 thought-end)}
        (ui/text u thought {:size 13.5 :color :text-secondary})))
    (doseq [[j it] (map-indexed vector items)]
      (ui/push-id-int u j)
      (case (:type it)
        :text (ui/markdown u "md" (:text it) {:width :fill :size 15 :streaming (not done)})
        :tool (let [st (tool-status (:status it) :pending)]
                (ui/tool-call u "tool" (or (:title it) "Tool")
                              {:icon (tool-icons (:kind it) :code) :detail (:kind it) :status st
                               :seconds (secs (:t0 it) (or (:t1 it) (when done (now))))
                               :input (:input it) :output (:output it)}))
        :error (ui/alert u (:text it) {:tone :danger})
        :note (ui/text u (:text it) {:size 12 :color :text-muted}))
      (ui/pop-id u))
    (when (and (not done) (empty? items) (empty? thought))
      (ui/agent-status u "Thinking" {:status :running :seconds (secs t0 nil)}))
    (when done
      (let [text (str/join "\n\n" (keep :text (filter #(= :text (:type %)) items)))
            act (ui/response-actions u "actions" {:copy-text text :no-share true})]
        (when (pos? (bit-and act raw/CUI_RESPONSE_REGENERATE)) (send-prompt! prompt))))))

(def suggestions [{:icon :search :title "Explain this project" :detail "Read the README and the code layout"}
                  {:icon :code :title "Find a bug" :detail "Look for something that could break"}
                  {:icon :file-text :title "Summarize the recent commits"}
                  {:icon :sparkles :title "Suggest an improvement"}])

(defn empty-state [u]
  (ui/column u {:width :fill :gap 16 :pad-top 80 :align :center}
    (ui/text u "What can I help with?" {:font :display :size 26})
    (ui/text u @cwd {:size 13 :color :text-muted})
    (let [pick (ui/prompt-suggestions u "sugg" suggestions {:columns (if @narrow 1 2)})]
      (when (>= pick 0) (send-prompt! (:title (suggestions pick)))))))

(defn conversation [u]
  (let [n? @narrow
        {:keys [sid turns busy usage]} (get @chats @current)]
    (ui/column u {:width :fill :height :fill}
      (ui/row u {:width :fill :pad-x (if n? 12 20) :pad-y 12 :gap (if n? 6 10) :align :center}
        (when (and n? (ui/icon-button u :menu {:tooltip "Chats"})) (reset! drawer true))
        (when (seq @models)
          (when (ui/model-picker u "model" (mapv (fn [m] {:name (:name m) :detail (:description m)}) @models)
                                 model {:ghost true :placement :bottom-start})
            (when sid (set-model! sid))))
        (ui/spacer u)
        (when (:size usage) (ui/context-meter u "ctx" (:used usage) (:size usage))))
      (ui/separator u)
      (when (not= @version @seen)
        ;; ponytail: follows every update, even if you scrolled up mid-answer
        (reset! seen @version)
        (ui/scroll-to u "chat" 1e9))
      (ui/scroll u {:id "chat" :width :fill :height :fill :fade-edges true}
        (ui/row u {:width :fill :justify :center :pad-x (if n? 12 24) :pad-y (if n? 16 24)}
          (ui/column u {:width :fill :max-width 760 :gap 14}
            (cond
              @status (ui/agent-status u @status {:status (if (str/starts-with? @status "Starting") :running :failed)})
              (nil? sid) (ui/agent-status u "Starting a session" {:status :running})
              (empty? turns) (empty-state u))
            (doseq [[k t] (map-indexed vector turns)]
              (if (= :user (:role t))
                (ui/chat-message u (:text t) {:mine true})
                (agent-turn u k t (:text (get turns (dec k)))))))))
      (ui/row u {:width :fill :justify :center :pad-x (if n? 12 24) :pad-bottom (if n? 12 20)}
        (ui/column u {:width :fill :max-width 760 :gap 8}
          (let [ev (ui/chat-composer u "composer" draft {:placeholder "Ask Claude" :busy (boolean busy)})]
            (when (and (pos? (bit-and ev raw/CUI_COMPOSER_SEND)) sid (not busy))
              (send-prompt! @draft) (reset! draft ""))
            (when (pos? (bit-and ev raw/CUI_COMPOSER_STOP)) (cancel!)))
          (ui/text u "Claude can read and change files in the working directory."
                   {:size 12 :color :text-muted :align :center :width :fill}))))))

(def plan-status {"pending" :pending "in_progress" :running "completed" :done})

(defn side-panel [u]
  (let [{:keys [plan usage turns]} (get @chats @current)
        tools (for [t turns, it (:items t) :when (= :tool (:type it))] it)]
    (ui/column u {:width 330 :height :fill :bg :surface :pad 20 :gap 16}
      (ui/text u "Workspace" {:font :medium :size 15})
      (ui/label u "Folder")
      (ui/row u {:gap 10 :align :center :width :fill :pad 8 :radius 12 :bg :surface-muted}
        (ui/icon u :folder 16 :text-secondary)
        (ui/text u @cwd {:size 13 :width :fill :nowrap true}))
      (if (seq plan)
        (ui/agent-plan u "plan" (mapv (fn [e] {:title (:content e) :status (plan-status (:status e) :pending)}) plan)
                       {:title "Plan"})
        (ui/text u "Claude shows its plan here when a task has several steps."
                 {:size 13 :color :text-muted}))
      (ui/label u "Session")
      (ui/text u (str (count tools) (if (= 1 (count tools)) " tool call" " tool calls")) {:size 13 :color :text-secondary})
      (when-let [c (:cost usage)]
        (ui/text u (format "Cost %.2f %s" (double (:amount c)) (:currency c)) {:size 13 :color :text-secondary})))))

(defn permission-dialog [u]
  (when-let [{:keys [title options]} (first @perms)]
    (ui/dialog u "Claude wants to run a tool" perm-open {:description title :persistent true :no-close-button true}
      (ui/dialog-footer u
        (doseq [{:keys [optionId name kind]} options]
          (ui/push-id u optionId)
          (when (ui/button u name {:variant (if (str/starts-with? kind "allow") :primary :outline)})
            (answer-permission! {:outcome "selected" :optionId optionId}))
          (ui/pop-id u))))))

(defn frame [u]
  (let [vw (:x (ui/viewport-size u))]
    (when (and @conn (acp/busy? @conn))
      ;; agent updates arrive on another thread; poll while a request is open
      (ui/request-wakeup u 0.05))
    (reset! narrow (< vw 760))
    (when-not @narrow (reset! drawer false))
    (ui/row u {:width :fill :height :fill :align :stretch :bg :background}
      (when-not @narrow (sidebar u false) (ui/separator u))
      (conversation u)
      (when (>= vw 1180) (ui/separator u) (side-panel u)))
    (ui/sheet u "Muse" drawer {:side :left :size 300}
      (sidebar u true))
    (permission-dialog u)))

(defn -main [& [dir]]
  (when dir (reset! cwd dir))
  (start!)
  (try
    (ui/run {:title "Muse" :width 1440 :height 900 :theme :dark :accent :neutral} frame)
    (finally (acp/close! @conn))))
