(ns devtools
  "A dev workbench using components added in recent CUI: code editor, live
  markdown preview, candlestick chart, status tags, cron descriptions and a
  terminal.
  Run: jolt -A:examples -m devtools"
  (:require [cui.core :as ui]
            [clojure.string]))

(def src (ui/text-buffer 8192 "# Release notes

Shipped **cui-jolt** against latest CUI.

- [x] regenerate bindings
- [x] layout check: 2277 values match
- [ ] write more examples

```clojure
(ui/knob u \"pan\" pan -1 1)
```
"))
(def tab (atom 0))
(def cron (atom "0 9 * * 1-5"))

(def candles
  (vec (take 24 (iterate (fn [{:keys [close]}]
                           (let [o close c (+ o (- (rand 6) 2.8))]
                             {:open o :close c :high (+ (max o c) (rand 2))
                              :low (- (min o c) (rand 2)) :volume (+ 100 (rand 400))}))
                         {:open 100 :close 101 :high 102 :low 99 :volume 300}))))

(defonce term
  (doto (ui/terminal-buffer)
    (ui/terminal-write "\u001b[1;32mcui-jolt\u001b[0m shell. Try \u001b[36mhelp\u001b[0m.\n")))

(defn run-command [cmd]
  ;; ponytail: built-in commands only, no subprocess
  (let [[c & args] (clojure.string/split (clojure.string/trim cmd) #"\s+")]
    (case c
      "" nil
      "help" (ui/terminal-write term "help  echo  tags  clear\n")
      "echo" (ui/terminal-write term (str (clojure.string/join " " args) "\n"))
      "tags" (ui/terminal-write term "\u001b[32mbuild ok\u001b[0m  \u001b[33mdeploy pending\u001b[0m  \u001b[31mcanary failed\u001b[0m\n")
      "clear" (ui/terminal-clear term)
      (ui/terminal-write term (str "\u001b[31munknown command:\u001b[0m " c "\n")))))

(defn terminal-tab [u]
  (ui/card u {:width :fill :gap 8}
    (let [r (ui/terminal u "term" term {:height 480 :width :fill :prompt "$ "})]
      (when-let [cmd (:command r)]
        (ui/terminal-write term (str "$ " cmd "\n"))
        (run-command cmd)))))

(defn editor-tab [u]
  (ui/row u {:gap 12 :width :fill :align :stretch}
    (ui/card u {:width :fill :gap 8}
      (ui/label u "README.md")
      (let [r (ui/code-editor u "ed" src {:lang "markdown" :height 380 :width :fill})]
        (ui/secondary u (format "Ln %d, Col %d  ·  %d lines" (:line r) (:column r) (:line-count r)))))
    (ui/card u {:width :fill :gap 8}
      (ui/label u "Preview")
      (ui/markdown u "md" (ui/buffer-text src) {:width :fill}))))

(defn ops-tab [u]
  (ui/column u {:gap 12 :width :fill}
    (ui/card u {:width :fill :gap 8}
      (ui/label u "ACME / USD")
      (ui/candlestick u "px" candles {:height 260 :width :fill}))
    (ui/card u {:width :fill :gap 8}
      (ui/label u "Pipelines")
      (ui/row u {:gap 8 :wrap true}
        (ui/status-tag u "build" :success)
        (ui/status-tag u "lint" :success)
        (ui/status-tag u "e2e" :in-progress)
        (ui/status-tag u "deploy" :pending)
        (ui/status-tag u "canary" :failed)))
    (ui/card u {:width :fill :gap 8}
      (ui/input u "cron" cron {:label "Schedule (cron)" :width :fill :icon :clock})
      (ui/secondary u (or (ui/cron-describe u @cron) "invalid expression")))))

(defn frame [u]
  (ui/column u {:pad 24 :gap 16 :width :fill :height :fill}
    (ui/row u {:width :fill}
      (ui/heading u "Workbench" 2)
      (ui/spacer u)
      (ui/tabs u "t" ["Editor" "Ops" "Terminal"] tab))
    (case @tab 0 (editor-tab u) 1 (ops-tab u) (terminal-tab u))))

(defn -main [& _]
  (ui/run {:title "Workbench" :width 1040 :height 760} frame))
