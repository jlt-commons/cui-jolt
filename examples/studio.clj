(ns studio
  "A mixer console using components added in recent CUI: knobs, faders, level
  meters, a radial gauge and readouts. Run: jolt -A:examples -m studio"
  (:require [cui.core :as ui]))

(def channels ["Kick" "Snare" "Bass" "Keys"])
(def gain (mapv (fn [_] (atom 0.0)) channels))   ; dB
(def pan (mapv (fn [_] (atom 0.0)) channels))    ; -1..1
(def master (atom -3.0))
(def bpm (atom 120))
(def t (atom 0.0))

(defn level [i]
  ;; ponytail: fake signal, wire a real audio source here
  (let [base (+ 0.55 (* 0.35 (Math/abs (Math/sin (+ @t (* i 1.7))))))]
    (min 1.0 (* base (Math/pow 10 (/ @(gain i) 20))))))

(defn strip [u i]
  (ui/card u {:gap 10 :pad 12}
    (ui/label u (channels i))
    (ui/knob u (str "pan" i) (pan i) -1 1 {:label "Pan" :bipolar true :size 44 :format "%.2f"})
    (ui/row u {:gap 8}
      (ui/fader u (str "fader" i) (gain i) -60 6 {:label "Vol" :unit "dB" :length 160 :reset 0 :format "%.1f"})
      (ui/level-meter u (str "meter" i) [(level i) (* 0.9 (level i))] {:length 160 :segmented true}))))

(defn frame [u]
  (swap! t + 0.08)
  (ui/column u {:pad 24 :gap 16 :width :fill}
    (ui/row u {:width :fill}
      (ui/heading u "Studio" 2)
      (ui/spacer u)
      (ui/status-tag u "Recording" :in-progress))
    (ui/row u {:gap 12}
      (ui/readout u "Tempo" (str @bpm) {:unit "BPM"})
      (ui/readout u "Master" (format "%.1f" @master) {:unit "dB"})
      (ui/step-slider u "BPM" bpm 60 180))
    (ui/row u {:gap 12 :align :start}
      (dotimes [i (count channels)] (strip u i))
      (ui/card u {:gap 10 :pad 12}
        (ui/label u "Master")
        (ui/radial-gauge u "cpu" (+ 30 (* 20 (Math/sin @t))) 0 100
                         {:label "CPU" :unit "%" :size 140 :format "%.0f"})
        (ui/knob u "master" master -60 6 {:label "Out" :unit "dB" :size 56 :format "%.1f"}))))
  (ui/request-redraw u))                          ; animate the meters

(defn -main [& _]
  (ui/run {:title "Studio" :width 980 :height 520 :theme :dark :accent :orange} frame))
