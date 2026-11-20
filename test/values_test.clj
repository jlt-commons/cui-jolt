(ns values-test
  "Coercions and struct encoding, with no window. jolt -A:test -m values-test"
  (:require [cui.core :as ui]
            [cui.impl :as impl]
            [jolt.ffi :as ffi]))

(def failures (atom []))
(defmacro check [label expr] `(when-not ~expr (swap! failures conj ~label)))
(defn throws? [f] (try (f) false (catch Throwable _ true)))

(defn -main [& _]
  ;; colors
  (check "hex int" (= {:r 0x63 :g 0x66 :b 0xF1 :a 255} (ui/color 0x6366F1)))
  (check "hex string" (= {:r 255 :g 0 :b 0 :a 128} (ui/color "#ff000080")))
  (check "vector" (= {:r 1 :g 2 :b 3 :a 255} (ui/color [1 2 3])))
  (check "token" (= 0xC5 (:g (ui/color :accent))))
  (check "bad token" (throws? #(ui/color :nope)))
  ;; sizes
  (check ":fill" (= {:kind 1 :value 1.0} (ui/size :fill)))
  (check "px" (= {:kind 2 :value 120} (ui/size 120)))
  (check "pct" (= {:kind 3 :value 50} (ui/size [:pct 50])))
  ;; enums and flags
  (check "enum kw" (= 2 (impl/enum-val :cui_button_variant :primary)))
  (check "enum int" (= 5 (impl/enum-val :cui_button_variant 5)))
  (check "enum bad" (throws? #(impl/enum-val :cui_button_variant :huge)))
  (check "mods" (= 3 (impl/mods #{:shift :ctrl})))
  (check "icon kw" (string? (ui/icon-str :plus)))
  (check "icon bad" (throws? #(ui/icon-str :no-such-icon)))
  ;; struct encode/decode round trip, with an auto-filled count
  (ui/with-frame
    (let [p (impl/in-ptr! (volatile! []) [:struct :cui_metric_opts]
                          {:delta 1.5 :spark [1 2 3] :width :fill :lower-is-better true})
          back (impl/read-struct :cui_metric_opts p)]
      (check "float field" (= 1.5 (:delta back)))
      (check "auto count" (= 3 (:spark-count back)))
      (check "size field decodes its enum" (= {:kind :grow :value 1.0} (:width back)))
      (check "bool field" (true? (:lower-is-better back))))
    (check "unknown option" (throws? #(impl/in-ptr! (volatile! []) [:struct :cui_button_opts] {:colour 1})))
    (check "snake keys too" (some? (impl/in-ptr! (volatile! []) [:struct :cui_box] {:pad_x 4}))))
  ;; struct return values
  (let [d (ui/date-today)]
    (check "date-today" (and (int? (:year d)) (<= 1 (:month d) 12))))
  ;; a text argument must outlive a GC within its frame: CUI reads it when it
  ;; paints, after every widget call of the frame has returned
  (ui/with-frame
    (let [p (impl/str-ptr (apply str "label-" (range 40)))]
      (dotimes [_ 20] (System/gc) (vec (range 100000)))
      (ffi/drain-auto-arenas!)
      (check "string survives gc in frame" (= (apply str "label-" (range 40)) (ffi/ptr->string p)))))
  (if (seq @failures)
    (do (println "FAIL" @failures) (System/exit 1))
    (println "values test passed")))
