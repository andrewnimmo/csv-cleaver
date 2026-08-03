(ns csv-cleaver.format
  "Turns numbers and outcomes into the sentences the window shows.

   Every function here takes an i18n context as its first argument and returns
   text in that language, with numbers written the way that language writes
   them. Nothing in this namespace contains a word of English — the wording all
   comes from resources/i18n.

   Sentences live here rather than in the view because they are the part a
   non-expert actually reads, they are the part most likely to be reworded, and
   a sentence is far easier to test than a widget."
  (:require
   [clojure.string :as str]
   [csv-cleaver.i18n :as i18n])
  (:import
   (java.text NumberFormat ParsePosition)
   (java.util Locale)))

(def size-units
  [[:size/gb (* 1024 1024 1024)]
   [:size/mb (* 1024 1024)]
   [:size/kb 1024]])

(def whitespace
  "Ordinary spaces, underscores, and the no-break and narrow no-break spaces
   that French and several other locales use to group thousands."
  (re-pattern "[\\s\\u00a0\\u202f_]"))

(defn file-size
  "A byte count as this language says it."
  [ctx bytes]
  (let [b (long bytes)]
    (if (< b 1024)
      (i18n/tr ctx :size/bytes (i18n/number ctx b))
      (let [[key factor] (first (filter (fn [[_ f]] (>= b (long f))) size-units))
            value        (/ (double b) (long factor))]
        (i18n/tr ctx key (i18n/decimal ctx value (if (>= value 100) 0 1)))))))

(defn duration
  "A length of time as a phrase: 6.4 seconds, 6,4 Sekunden, 1 minute 34 seconds."
  [ctx ms]
  (let [ms (long ms)]
    (cond
      (< ms 1000)  (i18n/tr ctx :duration/under-second)
      (< ms 60000) (i18n/tr ctx :duration/seconds
                            (i18n/decimal ctx (/ (double ms) 1000.0) 1))
      :else        (let [total   (quot ms 1000)
                         minutes (quot total 60)
                         seconds (rem total 60)]
                     (str (i18n/trn ctx :duration/minutes minutes
                                    (i18n/number ctx minutes))
                          (when (pos? seconds)
                            (str " " (i18n/trn ctx :duration/seconds-part seconds
                                               (i18n/number ctx seconds)))))))))

;; ── Reading what the user typed ─────────────────────────────────────────────

(defn parse-number
  "Parse `s` the way this language writes numbers, so that a German user typing
   65.000 means sixty-five thousand rather than sixty-five. Returns nil unless
   the whole string was consumed."
  [ctx s]
  (let [trimmed (str/trim (str s))]
    (when (seq trimmed)
      (let [nf       (NumberFormat/getNumberInstance ^Locale (or (:locale ctx) Locale/ROOT))
            position (ParsePosition. 0)
            parsed   (.parse nf trimmed position)]
        (when (and parsed (= (.getIndex position) (count trimmed)))
          (.doubleValue parsed))))))

(defn parse-count
  "A row count as the user typed it — 65,000 or 65.000 or 65 000 — as a long,
   or nil when it is not a positive whole number."
  [ctx s]
  (let [cleaned (str/replace (str s) whitespace "")]
    (when-let [value (parse-number ctx cleaned)]
      (when (and (pos? value)
                 (== value (Math/floor value))
                 (< value (double Long/MAX_VALUE)))
        (long value)))))

(defn parse-size
  "A file size — 25, 25MB, 1.5 GB, 1,5 GB — as bytes, or nil.
   A bare number means megabytes, which is what someone typing into a box
   labelled \"in each file\" intends."
  [ctx s]
  (let [text (-> (str s) str/trim str/upper-case)]
    (when-let [[_ digits unit] (re-matches (re-pattern "([\\d.,\\s\\u00a0\\u202f]+?)\\s*(GB|MB|KB|B)?")
                                           text)]
      (when-let [value (parse-number ctx (str/replace digits whitespace ""))]
        (let [factor (case unit
                       "GB" (* 1024 1024 1024)
                       "KB" 1024
                       "B"  1
                       (* 1024 1024))]
          (when (pos? value)
            (long (* value factor))))))))

;; ── Sentences ───────────────────────────────────────────────────────────────

(defn message
  "Render a problem, which is either a translation key with arguments or a raw
   string from somewhere untranslatable, such as the operating system's own
   description of a failure."
  [ctx m]
  (cond
    (nil? m)     nil
    (string? m)  m
    (keyword? m) (i18n/tr ctx m)
    (:key m)     (let [render (if (= :bytes (:arg-format m))
                                #(file-size ctx %)
                                #(i18n/number ctx %))]
                   ;; Numbers carried in a problem are still numbers, so they
                   ;; get this language's conventions like any other — as a
                   ;; grouped count, or as a size where the problem is about
                   ;; how much room something needs.
                   (apply i18n/tr ctx (:key m)
                          (map #(if (number? %) (render %) %) (:args m))))
    :else        (:text m)))

(defn plan-sentence
  "The live line under the row-count box: what pressing Split would do.

   Someone who cannot judge 65,000 against 100,000 in the abstract can judge
   nineteen files against thirty, which is the whole point of showing it."
  [ctx {:keys [mode file-count data-rows rows-per-file last-file-rows problem]}]
  (let [n #(i18n/number ctx %)]
    (cond
      problem
      (message ctx problem)

      ;; Pluralised on the row count, not the file count: "19 files of 1 rows
      ;; each" is the sort of thing that makes an application look careless, and
      ;; one row per file is exactly the setting somebody reaches by mistyping.
      (= 1 (long file-count))
      (i18n/trn ctx :plan/one-file data-rows (n data-rows))

      (= mode :bytes)
      (i18n/trn ctx :plan/by-size rows-per-file (n file-count) (n rows-per-file))

      (= (long last-file-rows) (long rows-per-file))
      (i18n/trn ctx :plan/even rows-per-file (n file-count) (n rows-per-file))

      :else
      (i18n/trn ctx :plan/uneven rows-per-file
                (n file-count) (n (dec (long file-count)))
                (n rows-per-file) (n last-file-rows)))))

(defn damage-summary
  "What is wrong with the chosen file, or nil when nothing is.

   :headline goes on the chip, :detail beside it. The detail always ends by
   saying the data is safe, because that is the question the wording otherwise
   leaves hanging."
  [ctx {:keys [damage records]}]
  (let [ragged       (long (or (:ragged damage) 0))
        stray        (long (or (:stray-quote damage) 0))
        unterminated (long (or (:unterminated-quote damage) 0))
        affected     (+ ragged stray unterminated)]
    (when (pos? affected)
      {:headline (i18n/trn ctx :damage/headline affected (i18n/number ctx affected))
       :detail
       (str/join " "
                 (cond-> []
                   (pos? ragged)
                   (conj (i18n/trn ctx :damage/ragged ragged (i18n/number ctx ragged)))

                   (pos? stray)
                   (conj (i18n/trn ctx :damage/stray-quote stray (i18n/number ctx stray)))

                   (pos? unterminated)
                   (conj (i18n/tr ctx :damage/unterminated))

                   :always
                   (conj (i18n/tr ctx :damage/reassurance (i18n/number ctx records)))))})))

(defn completion-sentence
  "The headline on the finished panel."
  [ctx {:keys [files elapsed-ms cancelled?]}]
  (let [n (count files)]
    (cond
      (and cancelled? (zero? n)) (i18n/tr ctx :done/stopped-none)
      cancelled?                 (i18n/trn ctx :done/stopped-some n (i18n/number ctx n))
      :else                      (i18n/trn ctx :done/created n
                                           (i18n/number ctx n)
                                           (duration ctx elapsed-ms)))))

(defn progress-sentence
  "The line under the progress bar during a run."
  [ctx {:keys [rows-done files-done]} total-rows]
  (i18n/tr ctx :splitting/progress
           (i18n/number ctx (max 1 (inc (long files-done))))
           (i18n/number ctx rows-done)
           (i18n/number ctx total-rows)))
