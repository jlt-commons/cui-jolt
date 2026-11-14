(ns cui.impl
  "Marshalling runtime for cui.core. Turns Clojure values into CUI's C structs
  and back, driven by the generated tables in cui.spec.

  Memory model: everything a wrapper allocates (option structs, C strings,
  arrays, out-parameter cells) lives in the FRAME ARENA bound to *arena*.
  cui.core/run opens one per frame and closes it only after the NEXT frame has
  been presented, because CUI may still read option strings (a tooltip, say)
  while it paints the frame they were passed in."
  (:require [jolt.ffi :as ffi]
            [clojure.string]
            [cui.raw :as raw]
            [cui.spec :as spec]))

;; ---------------------------------------------------------------------------
;; arenas

(def ^:dynamic *arena* nil)
(def ^:dynamic *app* nil)

(def ^:private outside-arena (delay (ffi/global-arena)))

(defn arena
  "The current frame arena. Outside a frame, allocations fall back to the
  process-lifetime arena; wrap setup code in cui.core/with-frame to scope it."
  []
  (or *arena* @outside-arena))

(defn call-with-arena
  "Runs (f) with a fresh confined arena bound as *arena*, closed afterwards."
  [f]
  (let [a (ffi/confined-arena)]
    (try (binding [*arena* a] (f))
         (finally (ffi/close-arena a)))))

;; ---------------------------------------------------------------------------
;; tables built once from cui.spec

(def enums
  (into {} (for [[e m] spec/enum-names]
             [e (into {} (for [[k v] m] [(keyword k) v]))])))

(def enum-keys
  (into {} (for [[e m] enums] [e (into {} (for [[k v] m] [v k]))])))

(def icons (into {} (for [[k v] spec/icon-names] [(keyword k) v])))

(def ^:private pointer-kinds #{:str :strs :vec :ref :raw})

(def structs
  (into {}
        (for [[t L] spec/layouts]
          (let [fields (mapv (fn [[uk ck kind cf]]
                               {:key uk :ckey ck :kind kind :count-field cf
                                :off (ffi/field-offset L ck)})
                             (get spec/fields t))
                by-key (reduce (fn [m f] (assoc m (:key f) f (:ckey f) f)) {} fields)]
            [t {:layout L :size (ffi/layout-size L) :fields fields :by-key by-key}]))))

(defn- struct-info [t]
  (or (structs t) (throw (ex-info (str "cui: unknown struct " t) {:struct t}))))

;; ---------------------------------------------------------------------------
;; small value coercions

(def ^:private mac?
  (boolean (re-find #"(?i)mac|darwin" (or (System/getProperty "os.name") ""))))

(def mod-flags
  (assoc (enums :cui_mod) :primary (if mac? ((enums :cui_mod) :super) ((enums :cui_mod) :ctrl))
         :cmd ((enums :cui_mod) :super)))

(defn mods
  "A modifier mask from an int, a keyword or a collection of keywords:
  #{:primary :shift}. :primary is Command on macOS and Ctrl elsewhere."
  [x]
  (cond (nil? x) 0
        (integer? x) x
        (keyword? x) (or (mod-flags x) (throw (ex-info (str "cui: unknown modifier " x)
                                                       {:valid (keys mod-flags)})))
        :else (reduce bit-or 0 (map mods x))))

(defn enum-val [e x]
  (cond (nil? x) 0
        (integer? x) x
        (keyword? x) (or (get-in enums [e x])
                         (throw (ex-info (str "cui: " x " is not a " (name e))
                                         {:enum e :valid (sort (keys (enums e)))})))
        (true? x) 1 (false? x) 0
        :else (throw (ex-info (str "cui: bad " (name e) " value") {:value x}))))

(defn enum-key [e n] (get-in enum-keys [e n] n))

(def ^:private fonts {:body 0 :medium 1 :display 2 :mono 3 :icons 4})

(defn font-val [x]
  (cond (nil? x) 0 (integer? x) x
        :else (or (fonts x) (throw (ex-info (str "cui: unknown font " x) {:valid (keys fonts)})))))

(defn utf8-len
  "Bytes in the UTF-8 encoding of s."
  [s]
  (if (nil? s) 0
      (reduce (fn [n ch] (let [c (int ch)]
                           (+ n (cond (< c 0x80) 1 (< c 0x800) 2 (< c 0x10000) 3 :else 4))))
              0 (str s))))

(defn str-arg
  "A string argument. Keywords name built-in icons (:plus, :search ...)."
  [x]
  (cond (nil? x) nil
        (string? x) x
        (keyword? x) (or (icons x) (throw (ex-info (str "cui: unknown icon " x) {:icon x})))
        :else (str x)))

(defn- hex-color [^String s]
  (let [h (subs s 1)
        n (Long/parseLong h 16)]
    (case (count h)
      6 {:r (bit-and (bit-shift-right n 16) 255) :g (bit-and (bit-shift-right n 8) 255)
         :b (bit-and n 255) :a 255}
      8 {:r (bit-and (bit-shift-right n 24) 255) :g (bit-and (bit-shift-right n 16) 255)
         :b (bit-and (bit-shift-right n 8) 255) :a (bit-and n 255)}
      (throw (ex-info (str "cui: bad color " s) {:color s})))))

(defn color
  "Canonical cui_color map from: a theme token keyword (:accent, :text-secondary,
  :series-1 ...), 0xRRGGBB, \"#rrggbb\" / \"#rrggbbaa\", [r g b] / [r g b a],
  or a map. nil means unset (CUI then uses the component's default)."
  [v]
  (cond (nil? v) {:r 0 :g 0 :b 0 :a 0}
        (map? v) (merge {:r 0 :g 0 :b 0 :a 255} v)
        (keyword? v) (if-let [t (get-in enums [:cui_token v])]
                       {:r t :g 0xC5 :b 0x1D :a 0}
                       (throw (ex-info (str "cui: unknown color token " v)
                                       {:valid (sort (keys (enums :cui_token)))})))
        (integer? v) {:r (bit-and (bit-shift-right v 16) 255) :g (bit-and (bit-shift-right v 8) 255)
                      :b (bit-and v 255) :a 255}
        (string? v) (hex-color v)
        (sequential? v) (let [[r g b a] v] {:r r :g g :b b :a (or a 255)})
        :else (throw (ex-info "cui: bad color" {:color v}))))

(defn size
  "Canonical cui_size map from :fit, :fill, a number (fixed points),
  [:grow w], [:pct p] or [:px v]."
  [v]
  (cond (nil? v) {:kind 0 :value 0.0}
        (map? v) v
        (= v :fit) {:kind 0 :value 0.0}
        (= v :fill) {:kind 1 :value 1.0}
        (number? v) {:kind 2 :value v}
        (sequential? v) (let [[k x] v]
                          {:kind (case k :grow 1 :px 2 :fixed 2 :pct 3 :percent 3 :fit 0)
                           :value (or x 1)})
        :else (throw (ex-info "cui: bad size" {:size v}))))

(defn- coerce-struct [t v]
  (case t
    :cui_color (color v)
    :cui_size (size v)
    :cui_vec2 (if (sequential? v) (let [[x y] v] {:x x :y y}) v)
    :cui_rect (if (sequential? v) (let [[x y w h] v] {:x x :y y :w w :h h}) v)
    :cui_date (if (sequential? v) (let [[y m d] v] {:year y :month m :day d}) v)
    v))

(defn- num-for [t x]
  (cond (= t :float) (double (or x 0))
        (= t :double) (double (or x 0))
        (true? x) 1 (false? x) 0 (nil? x) 0
        :else (long x)))

;; ---------------------------------------------------------------------------
;; encode / decode

(declare encode-struct! vec-ptr! ref-ptr! str-ptr strs-ptr)

(defn elem-size [[k a n]]
  (case k
    :scalar (ffi/sizeof a)
    :bool 1
    (:enum :font) 4
    :struct (:size (struct-info a))
    :array (* n (elem-size a))
    (ffi/sizeof :pointer)))

(defn encode!
  "Writes x at p as `kind`. Out-pointers register in the writeback list wb."
  [p [k a n] x wb]
  (case k
    :scalar (ffi/write p a (num-for a x))
    :bool (ffi/write p :bool (boolean x))
    :enum (ffi/write p :int (enum-val a x))
    :font (ffi/write p :int (font-val x))
    :struct (encode-struct! p a x wb)
    :array (let [s (elem-size a)]
             (loop [i 0 xs (seq x)]
               (when (and xs (< i n))
                 (encode! (+ p (* i s)) a (first xs) wb)
                 (recur (inc i) (next xs)))))
    :str (ffi/write p :pointer (str-ptr x))
    :strs (ffi/write p :pointer (strs-ptr x))
    :vec (ffi/write p :pointer (vec-ptr! wb a x))
    :ref (ffi/write p :pointer (ref-ptr! wb a x))
    :raw (ffi/write p :pointer (or x 0))))

(defn encode-struct! [p t v wb]
  (let [v (coerce-struct t v)]
    (when (some? v)
      (when-not (map? v)
        (throw (ex-info (str "cui: " (name t) " expects a map") {:struct t :value v})))
      (let [{:keys [by-key]} (struct-info t)]
        (doseq [[k x] v]
          (let [f (or (by-key k)
                      (throw (ex-info (str "cui: " (name t) " has no option " k)
                                      {:struct t :option k
                                       :valid (sort (map :key (:fields (struct-info t))))})))]
            (encode! (+ p (:off f)) (:kind f) x wb)
            ;; fill a sibling count (spark -> spark_count) the caller left out
            (when-let [cf (:count-field f)]
              (let [cfi (by-key cf)]
                (when (and (some? x) (not (contains? v cf)) (not (contains? v (:key cfi))))
                  (ffi/write (+ p (:off cfi)) :int (count x)))))))))))

(defn decode [p [k a n]]
  (case k
    :scalar (ffi/read p a)
    :bool (ffi/read p :bool)
    :enum (enum-key a (ffi/read p :int))
    :font (ffi/read p :int)
    :struct (let [{:keys [fields]} (struct-info a)]
              (into {} (for [f fields :when (not= :_ (:ckey f))]
                         [(:key f) (decode (+ p (:off f)) (:kind f))])))
    :array (let [s (elem-size a)] (mapv #(decode (+ p (* % s)) a) (range n)))
    :str (let [q (ffi/read p :pointer)] (when-not (zero? q) (ffi/ptr->string q)))
    (ffi/read p :pointer)))

(defn- zeroish? [x] (or (nil? x) (false? x) (and (number? x) (zero? x))))

(defn- writeback-struct
  "The value C left in struct t at p, merged over the caller's original map:
  pointer fields keep the caller's values, and fields the caller left out are
  added only if C set them to something non-zero."
  [p t orig]
  (let [{:keys [fields]} (struct-info t)
        orig (if (map? orig) orig {})]
    (reduce
     (fn [m {:keys [key ckey kind off]}]
       (let [[k a] kind
             given? (or (contains? orig key) (contains? orig ckey))
             okey (if (contains? orig ckey) ckey key)]
         (cond (= ckey :_) m
               (pointer-kinds k) m
               (= k :struct) (let [sub (get orig okey)]
                               (if (or given? (not (every? zeroish? (vals (decode (+ p off) kind)))))
                                 (assoc m okey (if (map? sub)
                                                 (writeback-struct (+ p off) a sub)
                                                 (decode (+ p off) kind)))
                                 m))
               :else (let [raw (if (#{:enum} k) (ffi/read (+ p off) :int) nil)
                           x (decode (+ p off) kind)]
                       (if (or given? (not (zeroish? (or raw x))))
                         (assoc m okey x)
                         m)))))
     orig fields)))

;; ---------------------------------------------------------------------------
;; allocation helpers used by generated wrappers

(defn alloc [t] (ffi/alloc (arena) (:layout (struct-info t))))

(defn read-struct [t p] (decode p [:struct t]))

(defn str-ptr [x]
  (if (nil? x) 0 (ffi/string->ptr (arena) (str-arg x))))

(defn strs-ptr
  "A NULL-terminated char*[] from a collection of strings (or icon keywords)."
  [xs]
  (if (nil? xs) 0
      (let [xs (vec xs) n (count xs) ps (ffi/sizeof :pointer)
            p (ffi/alloc (arena) (* ps (inc n)))]
        (dotimes [i n] (ffi/write p :pointer (str-ptr (nth xs i)) (* i ps)))
        p)))

(defn vec-ptr!
  "A C array of `ek` elements from a collection."
  [wb ek xs]
  (if (nil? xs) 0
      (let [xs (vec xs) s (elem-size ek)
            p (ffi/alloc (arena) (max 1 (* s (count xs))))]
        (dotimes [i (count xs)] (encode! (+ p (* i s)) ek (nth xs i) wb))
        p)))

(defn- opts-struct? [t]
  (let [n (name t)]
    (or (clojure.string/ends-with? n "_opts") (#{"cui_box" "cui_app_desc"} n))))

(defn in-ptr!
  "Pointer to a read-only argument: a struct map, or an array for scalar kinds.
  An options struct given as nil is passed zeroed (all defaults)."
  [wb [k t :as ek] x]
  (cond (and (= k :struct) (nil? x)) (if (opts-struct? t) (alloc t) 0)
        (= k :struct) (let [p (alloc t)] (encode-struct! p t x wb) p)
        (nil? x) 0
        (sequential? x) (vec-ptr! wb ek x)
        :else (vec-ptr! wb ek [x])))

;; ---- out-parameters: atoms in, atoms updated after the call

(defn ref? [x] (or (instance? clojure.lang.IAtom x) (volatile? x)))

(defn- deref-val [x] (if (ref? x) @x x))

(defn- many? [[k] cur]
  (and (sequential? cur)
       (or (not= k :struct)
           (not (number? (first cur))))))

(defn ref-count [x] (let [c (deref-val x)] (if (sequential? c) (count c) 1)))

(defn ref-ptr!
  "Pointer to a C in/out cell for an atom (or volatile). A scalar atom maps to
  one cell; an atom holding a vector maps to an array. Plain values work too,
  but changes C makes are then dropped."
  [wb ek x]
  (if (and (= :struct (first ek)) (integer? x))
    x                             ; a raw pointer to C-owned state (terminal-buffer)
  (let [cur (deref-val x)
        many (many? ek cur)
        items (if many (vec cur) [cur])
        s (elem-size ek)
        p (ffi/alloc (arena) (max 1 (* s (count items))))]
    (dotimes [i (count items)] (encode! (+ p (* i s)) ek (nth items i) wb))
    (when (ref? x) (vswap! wb conj [:ref p ek many x cur]))
    p)))

(defn terminal-buffer
  "A persistent cui_terminal_buffer (scrollback of text-cap bytes and line-cap
  lines), as a raw pointer. Pass it to terminal, terminal-write and friends.
  The struct must outlive frames, so it cannot live in an atom."
  ([] (terminal-buffer 65536 2048))
  ([text-cap line-cap]
   (let [t (ffi/alloc (:size (struct-info :cui_terminal_buffer)))
         text (ffi/alloc text-cap)
         lines (ffi/alloc (* line-cap (:size (struct-info :cui_terminal_line))))]
     (raw/cui_terminal_init t text text-cap lines line-cap)
     t)))

(defrecord TextBuffer [ptr cap])

(defn text-buffer
  "A persistent C text buffer of cap bytes for text fields. Cheaper than an
  atom for large text, since nothing is copied per frame. Free it with
  free-text-buffer when done."
  ([cap] (text-buffer cap ""))
  ([cap init]
   (let [p (ffi/alloc cap)]
     (ffi/write-bytes p (str init))
     (->TextBuffer p cap))))

(defn buffer-text [^TextBuffer b] (ffi/ptr->string (:ptr b)))

(defn set-buffer-text! [^TextBuffer b s]
  (let [s (str s)]
    (when (>= (utf8-len s) (:cap b))
      (throw (ex-info "cui: text longer than its buffer" {:cap (:cap b)})))
    (ffi/write-bytes (:ptr b) s)
    (ffi/write (:ptr b) :uint8 0 (utf8-len s))
    b))

(defn free-text-buffer [^TextBuffer b] (ffi/free (:ptr b)))

(defn buf-cap [x]
  (if (instance? TextBuffer x) (:cap x)
      (max 1024 (+ 256 (* 2 (utf8-len (deref-val x)))))))

(defn buf-ptr!
  "Pointer to a writable text buffer: a TextBuffer, or an atom of a string
  that is copied in and, if the user edited it, written back."
  [wb x]
  (if (instance? TextBuffer x)
    (:ptr x)
    (let [s (str (or (deref-val x) ""))
          p (ffi/alloc (arena) (buf-cap x))]
      (ffi/write-bytes p s)
      (when (ref? x) (vswap! wb conj [:buf p nil nil x s]))
      p)))

(defn- same? [[k a] x y]
  (if (and (= k :scalar) (= a :float) (number? x) (number? y))
    (< (Math/abs (- (double x) (double y))) (* 1e-6 (max 1.0 (Math/abs (double y)))))
    (= x y)))

(defn- set-ref! [box v] (if (volatile? box) (vreset! box v) (reset! box v)))

(defn flush!
  "Copies C's changes back into the caller's atoms."
  [entries]
  (doseq [[kind p ek many box cur] entries]
    (case kind
      :buf (let [s (ffi/ptr->string p)] (when (not= s cur) (set-ref! box s)))
      :ref (let [s (elem-size ek)
                 rd (fn [i orig]
                      (if (= :struct (first ek))
                        (let [t (second ek)
                              orig (if (#{:cui_color :cui_vec2 :cui_rect :cui_date :cui_size} t)
                                     (coerce-struct t orig) orig)]
                          (writeback-struct (+ p (* i s)) t orig))
                        (decode (+ p (* i s)) ek)))
                 new (if many (vec (map-indexed rd cur)) (rd 0 cur))]
             (when-not (if many
                         (and (= (count new) (count cur)) (every? true? (map #(same? ek %1 %2) new cur)))
                         (same? ek new cur))
               (set-ref! box new))))))

(defmacro with-call
  "Runs body with a fresh writeback list bound to wb, then flushes it."
  [[wb] & body]
  `(let [~wb (volatile! [])
         r# (do ~@body)]
     (flush! @~wb)
     r#))

(defn arg-val
  "A by-value scalar argument."
  [[k a] x]
  (case k
    :enum (enum-val a x)
    :font (font-val x)
    :bool (boolean x)
    :scalar (num-for a x)
    x))
