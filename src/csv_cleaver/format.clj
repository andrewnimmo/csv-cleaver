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
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.text :as text])
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

(defn usable?
  "Whether a parsed number is one this application can actually act on.

   Numbers arrive as doubles, and a long enough run of digits does not overflow
   in any visible way — it simply becomes infinity, which then throws the moment
   anything asks for it as a long. A thousand ones typed into the row box did
   exactly that, and because the throw happened inside the language switch, the
   window silently refused to change language at all."
  [value]
  (and (number? value)
       (Double/isFinite (double value))
       (< (Math/abs (double value)) (double Long/MAX_VALUE))))

(defn too-large?
  "Whether `s` is a number too big to use, as distinct from not a number.

   Compared as an exact integer rather than by parsing to a double, because the
   whole difficulty is that doubles stop being able to tell. Locale-independent:
   only the digits matter, and no language writes a different set of them."
  [s]
  (let [digits (str/replace (str s) #"[^0-9]" "")]
    (boolean
     (and (seq digits)
          (pos? (.compareTo (BigInteger. digits)
                            (BigInteger/valueOf Long/MAX_VALUE)))))))

(defn parse-number
  "Parse `s` the way this language writes numbers, so that a German user typing
   65.000 means sixty-five thousand rather than sixty-five. Returns nil unless
   the whole string was consumed and the result is one we can work with."
  [ctx s]
  (let [trimmed (str/trim (str s))]
    (when (seq trimmed)
      (let [nf       (NumberFormat/getNumberInstance ^Locale (or (:locale ctx) Locale/ROOT))
            position (ParsePosition. 0)
            parsed   (.parse nf trimmed position)]
        (when (and parsed (= (.getIndex position) (count trimmed)))
          (let [value (.doubleValue parsed)]
            ;; Infinity is what a very long number becomes here, and returning
            ;; it would push the failure into every caller instead of ending it
            ;; at the one place that knows the string was unusable.
            (when (usable? value) value)))))))

(defn parse-count
  "A row count as the user typed it — 65,000 or 65.000 or 65 000 — as a long,
   or nil when it is not a positive whole number."
  [ctx s]
  (let [cleaned (str/replace (str s) whitespace "")]
    (when-let [value (parse-number ctx cleaned)]
      (when (and (pos? value) (== value (Math/floor value)))
        (long value)))))

(defn unit-word
  "This language's own word for a unit, taken from the phrase it uses to say a
   size. French says \"25,0 Mo\", so a French user typing \"25 Mo\" is typing
   what the application itself showed them, and being told nothing is wrong
   with it would be indefensible."
  [ctx size-key]
  ;; The pinned fold: this exists so "25 mb" matches "MB", and both sides must
  ;; fold identically whatever the machine's locale — a Turkish default turns
  ;; "gib" and "GIB" into different strings.
  (-> (i18n/tr ctx size-key "") str/trim text/upper not-empty))

(defn size-tokens
  "Every unit this language could reasonably be handed, as [token key factor],
   largest first so that GB is tried before B. The ASCII forms are accepted as
   well as the translated ones: people type MB in every language, and a box that
   refuses it would be worse than one that is a little liberal."
  [ctx]
  (->> [[(unit-word ctx :size/gb)    :size/gb    (* 1024 1024 1024)]
        ["GB"                        :size/gb    (* 1024 1024 1024)]
        [(unit-word ctx :size/mb)    :size/mb    (* 1024 1024)]
        ["MB"                        :size/mb    (* 1024 1024)]
        [(unit-word ctx :size/kb)    :size/kb    1024]
        ["KB"                        :size/kb    1024]
        [(unit-word ctx :size/bytes) :size/bytes 1]
        ["B"                         :size/bytes 1]]
       (filter first)
       (distinct)))

(defn size-parts
  "A typed size split into the number and the unit it was written in, as
   {:value :unit :factor}, or nil. A bare number means megabytes, which is what
   someone typing into a box labelled \"in each file\" intends."
  [ctx s]
  (let [text (-> (str s) str/trim text/upper)
        [digits unit factor]
        (or (some (fn [[token key f]]
                    (when (str/ends-with? text token)
                      [(subs text 0 (- (count text) (count token))) key f]))
                  (size-tokens ctx))
            [text :size/mb (* 1024 1024)])]
    (when-let [value (parse-number ctx (str/replace (str/trim digits) whitespace ""))]
      ;; The unit multiplies, so a value that was usable on its own may not be
      ;; once it becomes bytes. Checked here rather than after the throw.
      (when (and (pos? value) (usable? (* value (long factor))))
        {:value value :unit unit :factor factor}))))

(defn parse-size
  "A file size — 25, 25MB, 1.5 GB, 1,5 GB, 25 Mo — as bytes, or nil."
  [ctx s]
  (when-let [{:keys [value factor]} (size-parts ctx s)]
    (long (* value (long factor)))))

(defn- places-in
  "How many decimals a value needs to come back looking as it went in."
  [^double value]
  (let [s (str value)]
    (if-let [point (str/index-of s ".")]
      (min 3 (- (count s) (long point) 1))
      0)))

(defn restate
  "A number typed in one language, written as another writes it: 65,000 becomes
   65.000 when the window changes from English to German.

   This exists because the text in the row-count box is text, not a number. It
   is read back in whatever language the window is currently in, so leaving it
   alone when the language changes does not merely look wrong — English 65,000
   read as German is sixty-five, and the split would quietly produce a thousand
   times too many files.

   Only the leading number is touched, so a unit such as MB survives. Anything
   this language cannot read as a number is returned exactly as it was: half
   typed input belongs to the user, and guessing at it would be worse than
   leaving it."
  [from to s]
  (let [text (str s)]
    (if-let [[_ digits tail]
             (re-matches (re-pattern "([\\d.,\\s\\u00a0\\u202f]*\\d)(.*)") text)]
      (if-let [value (parse-number from (str/replace digits whitespace ""))]
        (str (if (== value (Math/floor value))
               (i18n/number to (long value))
               (i18n/decimal to value (places-in value)))
             tail)
        text)
      text)))

(defn size-box-text
  "A byte count as something the size box can hold and this language can read
   back: the largest unit it divides into evenly, in this language's own word
   for that unit.

   Exactness wins over prettiness. 1.5 GB comes back as 1,536 MB rather than as
   a rounded 1.5 GB, because a size box that quietly changes the number in it is
   worse than one that picks a smaller unit."
  [ctx bytes]
  (let [b (long bytes)
        [key factor] (or (first (filter (fn [[_ f]]
                                          (and (>= b (long f))
                                               (zero? (rem b (long f)))))
                                        size-units))
                         [:size/bytes 1])]
    (i18n/tr ctx key (i18n/number ctx (quot b (long factor))))))

(defn restate-size
  "A size typed in one language, written as another writes it — unit included,
   so that 1.5 GB becomes 1,5 Go in a French window.

   The unit the user chose is kept. Going through a byte count and picking a
   unit afresh would turn their 1.5 GB into 1,536 MB, which is the same size and
   not what they wrote.

   Falls back to rewriting only the number when the text is not a size this
   language can read, which is the same promise `restate` makes: what the user
   typed is theirs."
  [from to s]
  (if-let [{:keys [value unit]} (size-parts from s)]
    (i18n/tr to unit (if (== value (Math/floor value))
                       (i18n/number to (long value))
                       (i18n/decimal to value (places-in value))))
    (restate from to s)))

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
      ;; A run that wrote nothing is never a success. Reporting "0 files created
      ;; in under a second" in green says the opposite of what happened.
      (zero? n)                  (i18n/tr ctx :done/nothing-written)
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
