(ns assistant-test
  "Checks the assistant's JSON and reply splitting. No agent or display needed:
  jolt -A:test -m assistant-test"
  (:require [acp]
            [assistant :as a]))

(defn -main [& _]
  (let [src "{\"a\":[1,-2.5e1,\"x\\u00e9\\n\\\"\",null,true,false],\"b\":{},\"c\":[],\"e\":\"\\ud83d\\ude00\"}"
        v (acp/read-json src)]
    (assert (= v {:a [1 -25.0 "xé\n\"" nil true false] :b {} :c [] :e "😀"}) (pr-str v))
    (assert (= v (acp/read-json (acp/write-json v)))))
  (let [reply "Three options:\n\n1. **Cache** it\n   with a TTL\n2. Batch the calls\n- Drop it\n\n```clj\n(a)\n\n(b)\n```\nAfter."]
    (assert (= (a/blocks reply)
               ["Three options:" "1. **Cache** it\n   with a TTL" "2. Batch the calls" "- Drop it"
                "```clj\n(a)\n\n(b)\n```\nAfter."])
            (pr-str (a/blocks reply))))
  (assert (= (a/quoted "line one\nline two" "Why?") "> line one\n> line two\n\nWhy?"))
  (assert (= (a/quoted "  " "Just ask") "Just ask"))
  (println "assistant test passed"))
