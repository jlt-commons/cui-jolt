(ns acp
  "A small Agent Client Protocol client: JSON-RPC 2.0 over an agent's stdio,
  one JSON object per line. Responses and agent requests are handled on a
  reader thread, so callbacks must only touch atoms."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---- JSON (Jolt ships no JSON library; this covers what ACP sends)

(defn write-json [x]
  (cond
    (nil? x) "null"
    (boolean? x) (str x)
    (number? x) (str x)
    (or (string? x) (keyword? x))
    (let [s (if (keyword? x) (name x) x)]
      (str \" (str/escape s (fn [c] (case c \" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
                                      (when (< (int c) 32) (format "\\u%04x" (int c))))))
           \"))
    (map? x) (str "{" (str/join "," (map (fn [[k v]] (str (write-json k) ":" (write-json v))) x)) "}")
    (sequential? x) (str "[" (str/join "," (map write-json x)) "]")
    :else (write-json (str x))))

(defn read-json
  "Parses a JSON string; object keys become keywords."
  [^String s]
  (let [i (volatile! 0)
        n (count s)
        peek* #(when (< @i n) (.charAt s @i))
        ws #(while (and (< @i n) (Character/isWhitespace (.charAt s @i))) (vswap! i inc))
        expect (fn [c] (ws) (when-not (= c (peek*)) (throw (ex-info (str "json: expected " c " at " @i) {})))
                 (vswap! i inc))
        hex4 #(let [v (Integer/parseInt (subs s @i (+ @i 4)) 16)] (vswap! i + 4) v)]
    (letfn [(string []
              (expect \")
              (let [sb (StringBuilder.)]
                (loop []
                  (let [c (.charAt s @i)]
                    (vswap! i inc)
                    (cond
                      (= c \") (str sb)
                      (= c \\) (let [e (.charAt s @i)]
                                 (vswap! i inc)
                                 (.append sb (case e \n "\n" \t "\t" \r "\r" \b "\b" \f "\f"
                                               \u (let [hi (hex4)]
                                                    (if (and (<= 0xD800 hi 0xDBFF) (= \\ (peek*)))
                                                      (do (vswap! i + 2)
                                                          (str (char (+ 0x10000 (bit-shift-left (- hi 0xD800) 10)
                                                                        (- (hex4) 0xDC00)))))
                                                      (str (char hi))))
                                               (str e)))
                                 (recur))
                      :else (do (.append sb c) (recur)))))))
            (value []
              (ws)
              (let [c (peek*)]
                (cond
                  (= c \{) (do (vswap! i inc) (ws)
                               (if (= \} (peek*)) (do (vswap! i inc) {})
                                   (loop [m {}]
                                     (let [k (string) _ (expect \:) m (assoc m (keyword k) (value))]
                                       (ws)
                                       (if (= \, (peek*)) (do (vswap! i inc) (recur m)) (do (expect \}) m))))))
                  (= c \[) (do (vswap! i inc) (ws)
                               (if (= \] (peek*)) (do (vswap! i inc) [])
                                   (loop [v []]
                                     (let [v (conj v (value))]
                                       (ws)
                                       (if (= \, (peek*)) (do (vswap! i inc) (recur v)) (do (expect \]) v))))))
                  (= c \") (string)
                  (str/starts-with? (subs s @i) "true") (do (vswap! i + 4) true)
                  (str/starts-with? (subs s @i) "false") (do (vswap! i + 5) false)
                  (str/starts-with? (subs s @i) "null") (do (vswap! i + 4) nil)
                  :else (let [start @i]
                          (while (and (< @i n) (str/index-of "+-0123456789.eE" (.charAt s @i))) (vswap! i inc))
                          (let [t (subs s start @i)]
                            (or (parse-long t) (parse-double t)
                                (throw (ex-info (str "json: bad value at " start) {}))))))))]
      (value))))

;; ---- JSON-RPC connection

(defn connect
  "Starts the agent process. handlers: {:notify (fn [method params])
  :request (fn [conn id method params])} — a request handler must eventually
  call respond!. :log (fn [line]) gets the agent's stderr."
  [cmd {:keys [notify request log]}]
  (let [proc (p/process cmd {:err :pipe})
        conn {:proc proc
              :w (io/writer (:in proc))
              :ids (atom 0)
              :pending (atom {})}]
    (future
      (try
        (doseq [line (line-seq (io/reader (:out proc)))
                :when (str/starts-with? line "{")]
          (let [{:keys [id method params result error]} (read-json line)]
            (cond
              (and method id) (request conn id method params)
              method (notify method params)
              :else (when-let [cb (get @(:pending conn) id)]
                      (swap! (:pending conn) dissoc id)
                      (cb result error)))))
        (catch Exception e (when log (log (str "acp reader: " (ex-message e))))))
      ;; the agent is gone: fail everything still waiting
      (doseq [[_ cb] @(:pending conn)] (cb nil {:message "agent exited"}))
      (reset! (:pending conn) {}))
    (future (doseq [line (line-seq (io/reader (:err proc)))] (when log (log line))))
    conn))

(defn- send! [conn msg]
  (locking conn
    (.write (:w conn) (str (write-json (assoc msg :jsonrpc "2.0")) "\n"))
    (.flush (:w conn))))

(defn request!
  "Sends a request; (cb result error) runs on the reader thread."
  [conn method params cb]
  (let [id (swap! (:ids conn) inc)]
    (swap! (:pending conn) assoc id cb)
    (send! conn {:id id :method method :params params})))

(defn notify! [conn method params] (send! conn {:method method :params params}))

(defn respond! [conn id result] (send! conn {:id id :result result}))

(defn respond-error! [conn id code message] (send! conn {:id id :error {:code code :message message}}))

(defn busy? [conn] (boolean (seq @(:pending conn))))

(defn close! [conn] (p/destroy-tree (:proc conn)))

(defn -main [& _]
  ;; self-check: no agent needed
  (let [src "{\"a\":[1,-2.5e1,\"x\\u00e9\\n\\\"\",null,true,false],\"b\":{},\"c\":[],\"e\":\"\\ud83d\\ude00\"}"
        v (read-json src)]
    (assert (= v {:a [1 -25.0 "xé\n\"" nil true false] :b {} :c [] :e "😀"}) (pr-str v))
    (assert (= v (read-json (write-json v))))
    (println "acp json ok")))
