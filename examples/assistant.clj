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
(def branch (atom nil))  ; the reply being split: {:chat :turn :blocks [..] :picked #{..}}
(def branch-open (atom false))
(def branch-quote (atom ""))
(def branch-ask (atom ""))

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
      ;; a fork's agent title is the parent's plus "(fork)"; a subchat keeps its own
      "session_info_update" (when (:title u)
                              (update-chat! sessionId #(cond-> % (not (:parent %)) (assoc :title (:title u)))))
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

(defn open-session!
  "Adds a chat, shows it and starts its ACP session with method (session/new
  or session/fork). (on-ready i) runs once the session exists."
  [chat method params on-ready]
  (let [i (count @chats)]
    (swap! chats conj (merge {:title "New chat" :turns [] :plan []} chat))
    (reset! current i)
    (acp/request! @conn method (merge {:cwd @cwd :mcpServers []} params)
                  (fn [r e]
                    (if e
                      (swap! chats assoc-in [i :turns]
                             [{:role :agent :done true
                               :items [{:type :error :text (str "Could not start a session: " (:message e))}]}])
                      (let [sid (:sessionId r)
                            opt (first (filter #(= "model" (:id %)) (:configOptions r)))]
                        (swap! chats assoc-in [i :sid] sid)
                        (when (and opt (empty? @models))
                          (reset! models (:options opt))
                          (reset! model (or (first (keep-indexed #(when (= (:value %2) (:currentValue opt)) %1)
                                                                 @models))
                                            0)))
                        (when (and opt (not= (:value (get @models @model)) (:currentValue opt)))
                          (set-model! sid))
                        (when on-ready (on-ready i))))
                    (swap! version inc)))))

(defn new-chat! [] (open-session! {} "session/new" {} nil))

(declare send-prompt!)

(defn blocks
  "Splits a markdown reply into the parts you can quote: paragraphs and list
  items. A fenced code block stays in one part."
  [text]
  (loop [[l & ls :as all] (str/split-lines text) cur [] out [] fence false]
    (if (empty? all)
      (vec (remove str/blank? (map #(str/join "\n" %) (conj out cur))))
      (let [f? (str/starts-with? (str/triml l) "```")]
        (cond
          fence (recur ls (conj cur l) out (not f?))
          f? (recur ls (conj cur l) out true)
          (str/blank? l) (recur ls [] (conj out cur) false)
          (re-find #"^\s{0,3}([-*+]|\d+[.)])\s" l) (recur ls [l] (conj out cur) false)
          :else (recur ls (conj cur l) out false))))))

(defn quoted [quote question]
  (str/trim (str (when-not (str/blank? quote)
                   (str (str/join "\n" (map #(str "> " %) (str/split-lines (str/trim quote)))) "\n\n"))
                 question)))

(defn branch!
  "Forks the parent chat's session into a subchat and asks about the quote
  there. The parent chat is left as it is."
  [parent turn quote question]
  (let [psid (:sid (get @chats parent))
        text (quoted quote question)
        title (str/trim (if (str/blank? question) quote question))]
    (when (and psid (not (str/blank? text)))
      ;; ponytail: the fork carries the whole parent conversation, also turns after
      ;; the quoted one; the quote is what points Claude at the right part
      (open-session! {:title (if (> (count title) 40) (str (subs title 0 40) "…") title)
                      :parent parent :from turn}
                     "session/fork" {:sessionId psid}
                     #(send-prompt! % text)))))

(defn send-prompt!
  ([text] (send-prompt! @current text))
  ([i text]
   (let [{:keys [sid busy]} (get @chats i)]
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
                       (update-chat! sid assoc :busy false)))))))

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
    ;; newest first, each subchat indented under the chat it came from
    (let [kids (group-by :parent (map-indexed #(assoc %2 :i %1) @chats))]
      ((fn walk [parent depth]
         (doseq [{:keys [i title busy]} (reverse (kids parent))]
           (ui/push-id-int u i)
           (ui/box u {:width :fill :pad-left (* 16 depth)}
             (when (ui/nav-item u title {:icon (if parent :git-branch :message-square) :selected (= @current i)
                                         :group "chats" :badge (when busy "…")})
               (reset! current i) (reset! drawer false)))
           (ui/pop-id u)
           (walk i (inc depth))))
       nil 0))
    (ui/spacer u)
    (ui/row u {:gap 8 :align :center :pad-x 6}
      (ui/icon u (if @status :circle-alert :sparkles) 14 (if @status :text-muted :accent))
      (ui/text u (or @status "Claude via ACP") {:size 12 :color :text-secondary :width :fill}))))

(defn secs [t0 t1] (/ (- (or t1 (now)) t0) 1000.0))

(def tool-icons {"read" :file-text "edit" :pencil "delete" :trash-2 "move" :arrow-right "search" :search
                 "execute" :terminal "fetch" :globe "think" :sparkles})

(def tool-status {"pending" :pending "in_progress" :running "completed" :done "failed" :failed})

(defn open-branch! [i k text]
  (reset! branch {:chat i :turn k :blocks (blocks text) :picked #{}})
  (reset! branch-quote "")
  (reset! branch-ask "")
  (reset! branch-open true))

(defn agent-turn [u i k {:keys [thought items done t0 thought-end]} prompt chat-busy]
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
            subs* (keep-indexed (fn [ci c] (when (and (= i (:parent c)) (= k (:from c))) [ci c])) @chats)]
        (ui/row u {:gap 4 :align :center :wrap true}
          (let [act (ui/response-actions u "actions" {:copy-text text :no-share true})]
            (when (pos? (bit-and act raw/CUI_RESPONSE_REGENERATE)) (send-prompt! prompt)))
          (when (and (seq text)
                     (ui/button u "Subchat" {:icon :git-branch :variant :ghost :size :sm :disabled (boolean chat-busy)
                                             :tooltip "Ask about part of this reply in a new chat"}))
            (open-branch! i k text)))
        (doseq [[ci c] subs*]
          (ui/push-id-int u ci)
          (ui/row u {:gap 6 :align :center}
            (ui/icon u :git-branch 14 :text-muted)
            (when (ui/link u (:title c)) (reset! current ci)))
          (ui/pop-id u))))))

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
        {:keys [sid turns busy usage parent]} (get @chats @current)]
    (ui/column u {:width :fill :height :fill}
      (ui/row u {:width :fill :pad-x (if n? 12 20) :pad-y 12 :gap (if n? 6 10) :align :center}
        (when (and n? (ui/icon-button u :menu {:tooltip "Chats"})) (reset! drawer true))
        (when (seq @models)
          ;; a searchable select, since CUI's model-picker menu does not scroll
          (when (ui/select u "model" (mapv :name @models) model {:searchable true :width 220})
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
              (and (nil? sid) (empty? turns)) (ui/agent-status u "Starting a session" {:status :running})
              (empty? turns) (empty-state u))
            (when parent
              (ui/row u {:gap 6 :align :center}
                (ui/icon u :git-branch 14 :text-muted)
                (ui/text u "Subchat of" {:size 13 :color :text-muted})
                (when (ui/link u (:title (get @chats parent))) (reset! current parent))))
            (doseq [[k t] (map-indexed vector turns)]
              (if (= :user (:role t))
                (ui/chat-message u (:text t) {:mine true :markdown true})
                (agent-turn u @current k t (:text (get turns (dec k))) busy))))))
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

(defn branch-dialog [u]
  ;; CUI text has no mouse selection, so you pick whole parts and trim the quote
  (when-let [{:keys [chat turn blocks picked]} @branch]
    (ui/dialog u "Start a subchat" branch-open
               {:description "Pick the parts of the reply to ask about. This chat stays as it is." :width 640}
      (ui/scroll u {:id "blocks" :width :fill :height 260 :gap 8}
        (doseq [[j b] (map-indexed vector blocks)]
          (ui/push-id-int u j)
          (let [clicked (ui/card-begin u {:hoverable true :selected (contains? picked j) :pad 10 :width :fill})]
            (ui/markdown u "b" b {:width :fill :size 13.5 :compact true})
            (ui/card-end u)
            (when clicked
              (let [p (if (picked j) (disj picked j) (conj picked j))]
                (swap! branch assoc :picked p)
                (reset! branch-quote (str/join "\n\n" (map blocks (sort p)))))))
          (ui/pop-id u)))
      (ui/textarea u "quote" branch-quote {:label "Quote" :placeholder "Pick parts above, then cut this down to what matters"
                                           :height 90 :width :fill})
      (let [ev (ui/input u "ask" branch-ask {:label "Your message" :placeholder "What do you want to know about it?"
                                             :autofocus true :width :fill})
            ready (not (str/blank? (quoted @branch-quote @branch-ask)))
            go (fn [] (reset! branch-open false) (branch! chat turn @branch-quote @branch-ask))]
        (when (and ready (pos? (bit-and ev raw/CUI_SUBMITTED))) (go))
        (ui/dialog-footer u
          (when (ui/button u "Cancel" {:variant :ghost}) (reset! branch-open false))
          (when (ui/button u "Start subchat" {:variant :primary :icon :git-branch :disabled (not ready)}) (go)))))))

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
    (branch-dialog u)
    (permission-dialog u)))

(defn -main [& [dir]]
  (when dir (reset! cwd dir))
  (start!)
  (try
    (ui/run {:title "Muse" :width 1440 :height 900 :theme :dark :accent :neutral} frame)
    (finally (acp/close! @conn))))
