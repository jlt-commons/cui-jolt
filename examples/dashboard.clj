(ns dashboard
  "A small analytics dashboard: metrics with sparklines, charts, a settings
  card, a dialog and toasts. Run: jolt -A:examples -m dashboard"
  (:require [cui.core :as ui]))

(def state
  {:range (atom 1)
   :dark? (atom false)
   :accent (atom 2)
   :email (atom "ada@example.com")
   :weekly (atom true)
   :due (atom {:year 2026 :month 11 :day 20})
   :tint (atom {:r 99 :g 102 :b 241 :a 255})
   :volume (atom 0.6)
   :confirm (atom false)})

(def accents ["Neutral" "Violet" "Blue" "Green" "Amber" "Orange" "Coral" "Rose"])
(def months ["Jun" "Jul" "Aug" "Sep" "Oct" "Nov"])

(defn metric-row [u]
  (ui/row u {:gap 12 :width :fill}
    (ui/metric u "Revenue" "$48.2k"
               {:delta 12.4 :delta-label "vs last month" :icon :dollar-sign
                :spark [31 34 33 38 41 44 48] :width :fill})
    (ui/metric u "Active users" "3,912"
               {:delta 4.1 :spark [3.2 3.4 3.3 3.6 3.8 3.9] :width :fill})
    (ui/metric u "Build time" "2m 14s"
               {:delta -8.0 :lower-is-better true :spark [160 150 152 141 134] :width :fill})))

(defn charts [u]
  (ui/row u {:gap 12 :width :fill :align :stretch}
    (ui/card u {:width [:grow 2] :gap 8}
      (ui/row u {:width :fill}
        (ui/label u "Signups")
        (ui/spacer u)
        (ui/segmented u "range" ["7d" "30d" "90d"] (:range state) {:size :sm}))
      (ui/line-chart u "signups"
                     [{:name "Organic" :values [120 132 128 150 171 190]}
                      {:name "Paid" :values [80 76 95 101 99 120] :color :series-3}]
                     {:labels months :height 200 :smooth true :area true}))
    (ui/card u {:width :fill :gap 8}
      (ui/label u "Traffic sources")
      (ui/donut-chart u "sources" [52 28 20] ["Search" "Direct" "Social"] {:height 200}))))

(defn settings [u]
  (ui/card u {:width :fill :gap 12}
    (ui/row u {:width :fill}
      (ui/heading u "Settings" 4)
      (ui/spacer u)
      (ui/badge u "Beta" {:tone :violet}))
    (ui/input u "email" (:email state)
              {:label "Notification email" :icon :mail :width :fill :clearable true})
    (ui/row u {:gap 16 :wrap true}
      (ui/switch u "Weekly report" (:weekly state))
      (when (ui/switch u "Dark theme" (:dark? state))
        (ui/set-theme u (if @(:dark? state) :dark :light))))
    (ui/row u {:gap 12 :width :fill}
      (when (ui/select u "accent" accents (:accent state) {:label "Accent" :width :fill})
        (ui/set-accent u @(:accent state)))
      (ui/date-picker u "due" (:due state) {:label "Report due" :width :fill}))
    (ui/slider u "Volume" (:volume state) 0 1 {:show-value true :format "%.2f"})
    (ui/progress u @(:volume state) {:label "Quota used"})
    (ui/row u {:gap 8}
      (when (ui/button u "Save" {:variant :primary :icon :check})
        (ui/toast u "Settings saved" {:tone :success :description @(:email state)}))
      (when (ui/button u "Reset data" {:variant :danger :icon :trash-2})
        (reset! (:confirm state) true)))))

(defn confirm-dialog [u]
  (ui/dialog u "Reset all data?" (:confirm state)
             {:description "This removes every report. It cannot be undone."}
    (ui/dialog-footer u
      (when (ui/button u "Cancel") (reset! (:confirm state) false))
      (when (ui/button u "Reset" {:variant :danger})
        (reset! (:confirm state) false)
        (ui/toast u "Data reset" {:tone :danger})))))

(defn frame [u]
  (ui/scroll u {:width :fill :height :fill}
    (ui/column u {:pad 24 :gap 16 :width :fill}
      (ui/row u {:width :fill}
        (ui/column u {:gap 2}
          (ui/heading u "Larkspur" 2)
          (ui/secondary u "Everything at a glance"))
        (ui/spacer u)
        (ui/icon-button u :bell {:tooltip "Notifications"})
        (ui/avatar u "Ada Lovelace" {:status "online"}))
      (metric-row u)
      (charts u)
      (settings u)))
  (confirm-dialog u))

(defn -main [& _]
  (ui/run {:title "Dashboard" :width 980 :height 860 :accent :blue} frame))
