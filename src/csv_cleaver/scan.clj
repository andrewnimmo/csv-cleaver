(ns csv-cleaver.scan
  "Surveys a CSV file once, the moment it is chosen.

   The old application only counted rows after the user pressed Split, which
   meant the file card had nothing to say and any problem in the file surfaced
   halfway through writing output. Surveying up front means the window can show
   the row count, the encoding and any damage while the user is still deciding,
   and the split itself then needs no guesswork."
  (:require
   [clojure.string :as str]
   [csv-cleaver.csv :as csv]
   [csv-cleaver.encoding :as encoding]
   [csv-cleaver.files :as files])
  (:import
   (java.io File)))

(def head-sample-chars
  "Characters read from the top of the file to sniff the delimiter and work out
   whether the first row is a header."
  8192)

(def check-interval
  "Records between cancellation checks. Checking every record would cost more
   than the scan itself."
  25000)

(defn numeric-looking?
  "True when `s` reads as a number to an ordinary person: 42, -3.5, 1,024, 99%.
   Used only to tell a header row from a data row."
  [^String s]
  (let [t (-> s (.trim) (.replace "," "") (.replace "%" "") (.replace "£" "")
              (.replace "$" "") (.replace "€" ""))]
    (boolean (and (seq t) (re-matches #"[-+]?\d*\.?\d+([eE][-+]?\d+)?" t)))))

(def date-like #"\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}")

(def word-like
  "Letters, and the punctuation people put in column names. Deliberately does
   not include digits: id_2 is common, but a cell of mostly digits is data."
  #"^[\p{L}][\p{L}\s_.\-]*$")

(defn cell-shape
  "What kind of thing a cell holds, coarsely. Enough to notice that a column of
   numbers has a word sitting on top of it."
  [s]
  (let [t (str/trim (str s))]
    (cond
      (empty? t)                 :empty
      (numeric-looking? t)       :number
      (re-find date-like t)      :date
      (re-matches word-like t)   :word
      :else                      :other)))

(defn- fraction [pred coll]
  (if (empty? coll) 0.0 (/ (double (count (filter pred coll))) (count coll))))

(defn- modal [coll]
  (when (seq coll) (key (apply max-key val (frequencies coll)))))

(defn header-signals
  "Score each independent hint that `first-row` names the columns rather than
   holding data. `data-rows` is a sample of the rows beneath it.

   Every signal is a fraction from 0 to 1. None of them is conclusive on its
   own, which is exactly why they are combined rather than chained."
  [first-row data-rows]
  (let [columns   (count first-row)
        column    (fn [i] (keep #(nth % i nil) data-rows))
        ;; One disagreeing column out of three is strong evidence, not a third
        ;; of it: a spreadsheet rarely has every column numeric, so requiring
        ;; agreement everywhere would miss most real files.
        saturate  (fn [f] (min 1.0 (* 2.0 (double f))))]
    {;; A word sitting on a column of numbers or dates. The strongest hint,
     ;; and the only one the previous implementation used.
     :type-disagreement
     (if (or (zero? columns) (empty? data-rows))
       0.0
       (saturate
        (fraction true?
                  (for [i (range columns)]
                    (let [below (modal (map cell-shape (column i)))]
                      (and (contains? #{:number :date} below)
                           (not= below (cell-shape (nth first-row i)))))))))

     ;; The rows below agree with each other and disagree with row one. This is
     ;; what catches a file of nothing but words, where the first hint is blind.
     :column-consistency
     (if (or (zero? columns) (< (count data-rows) 2))
       0.0
       (saturate
        (fraction true?
                  (for [i (range columns)]
                    (let [shapes (map cell-shape (column i))]
                      ;; (seq shapes) is not decoration. A column that exists in
                      ;; row one and in no row below it — "Dear Sir," at the top
                      ;; of a letter, and no comma after — leaves this empty, and
                      ;; (apply = ()) throws. That crashed the scan on an
                      ;; ordinary text file.
                      (and (seq shapes)
                           (apply = shapes)
                           (not= (first shapes) (cell-shape (nth first-row i)))))))))

     ;; A bare number as a column name is vanishingly rare, so one is close to
     ;; proof that this row is data. Treated as all or nothing for that reason.
     :no-bare-numbers
     (if (some #(= :number (cell-shape %)) first-row) 0.0 1.0)

     ;; Column names are nearly always distinct; data repeats itself freely.
     ;; With a single column the test means nothing, so it abstains.
     :uniqueness
     (cond
       (< columns 2)                                              0.5
       (= columns (count (distinct (map str/trim first-row))))     1.0
       :else                                                       0.0)

     ;; A header with a blank in it is unusual; a data row with one is not.
     :no-empty-cells
     (if (zero? columns) 0.0 (fraction #(not= :empty (cell-shape %)) first-row))

     ;; Words, not values.
     :word-like
     (fraction #(= :word (cell-shape %)) first-row)

     ;; Column names cluster in a narrow band of short lengths; data sprawls.
     :length-profile
     (let [lengths (map (comp count str/trim str) first-row)]
       (if (empty? lengths)
         0.0
         (if (and (<= (apply max lengths) 40)
                  (<= (- (apply max lengths) (apply min lengths)) 20))
           1.0 0.0)))

     ;; A naming convention applied across the whole row. All-lowercase counts:
     ;; id, name, city is by far the commonest style of column name, and
     ;; omitting it was why an obvious header scored only middling.
     :case-style
     (fraction (fn [s]
                 (let [t (str/trim (str s))]
                   (boolean (or (re-matches #"[a-z]+" t)
                                (re-matches #"[A-Z][a-z]+(\s[A-Z][a-z]+)*" t)
                                (re-matches #"[a-z]+(_[a-z0-9]+)+" t)
                                (re-matches #"[a-z]+([A-Z][a-z0-9]*)+" t)))))
               first-row)}))

(def signal-weights
  "How much each hint counts.

   Type disagreement, column consistency and the absence of bare numbers are
   evidence drawn from comparing the row with the data beneath it. The rest
   merely describe the row itself and count for less, because a data row can
   perfectly well be short, unique and wordy."
  {:type-disagreement  0.25
   :column-consistency 0.20
   :no-bare-numbers    0.25
   :uniqueness         0.08
   :no-empty-cells     0.05
   :word-like          0.12
   :length-profile     0.00
   :case-style         0.05})

(def header-certain
  "At or above this, tick the box without asking."
  0.62)

(def header-doubtful
  "At or below this, leave the box unticked without asking."
  0.34)

(defn header-evidence
  "Whether `first-row` names the columns, how sure we are, and why.

     :verdict  :header, :data, or :unsure
     :score    0.0 to 1.0
     :signals  the individual hints, for anyone debugging a wrong answer

   Three outcomes rather than two, because guessing wrong silently is worse
   than admitting the file is ambiguous and showing the user the row."
  [first-row data-rows]
  (if (empty? first-row)
    {:verdict :data :score 0.0 :signals {}}
    (let [signals (header-signals first-row data-rows)
          score   (reduce + (map (fn [[k weight]] (* weight (get signals k 0.0)))
                                 signal-weights))]
      {:verdict (cond
                  (>= score header-certain)  :header
                  (<= score header-doubtful) :data
                  :else                      :unsure)
       :score   score
       :signals signals})))

(defn looks-like-header?
  "Whether to tick the header checkbox by default. An ambiguous file gets the
   benefit of the doubt, because repeating a header row that turns out to be
   data is a visible mistake, while treating a header as data quietly puts a row
   of column names into the middle of someone's spreadsheet."
  [first-row data-rows]
  (= :header (:verdict (header-evidence first-row data-rows))))

(defn- head-text
  "The first `head-sample-chars` characters of `file`."
  ^String [^File file detection]
  (with-open [r (files/reader file detection)]
    (let [buf (char-array head-sample-chars)
          n   (.read r buf 0 head-sample-chars)]
      (if (pos? n) (String. buf 0 n) ""))))

(def empty-survey
  {:records 0 :fields 0 :ragged 0 :stray-quote 0 :unterminated-quote 0})

(defn- tally
  [acc {:keys [fields damage]}]
  (let [first-fields (or (:fields acc) fields)]
    (cond-> (assoc acc :records (inc (long (:records acc))) :fields first-fields)
      (not= fields first-fields)             (update :ragged inc)
      (contains? damage :stray-quote)        (update :stray-quote inc)
      (contains? damage :unterminated-quote) (update :unterminated-quote inc))))

(defn survey
  "Read `file` once and describe it. Options:

     :cancelled?  zero-argument predicate, polled periodically; when it returns
                  true the scan stops and the result carries :cancelled? true
     :on-progress called with the running record count, for the \"Checking…\"
                  state on the file card

   The result:

     :file            the java.io.File surveyed
     :bytes           its size on disk
     :encoding        the map from csv-cleaver.encoding/detect
     :delimiter       the character that separates fields
     :records         total records, header included
     :fields          fields in the first record
     :header-likely?  whether the first row reads like column names
     :damage          {:ragged n :stray-quote n :unterminated-quote n}
     :healthy?        true when nothing at all looked wrong
     :cancelled?      true when the scan was stopped early"
  ([^File file] (survey file {}))
  ([^File file {:keys [cancelled? on-progress delimiter]
                :or   {cancelled?  (constantly false)
                       on-progress (fn [_])}}]
   (let [detection (encoding/detect file)
         head      (head-text file detection)
         ;; An explicit delimiter overrides detection. Everything downstream —
         ;; the record count, the field count, the damage tally — depends on it,
         ;; which is why changing it re-surveys rather than patching the result.
         detected  (csv/detect-delimiter head)
         delimiter (or delimiter detected)
         ;; Twenty rows is plenty of evidence and costs nothing: they are
         ;; already in the sample read to sniff the delimiter.
         preview   (mapv #(csv/parse-fields (:text %) delimiter)
                         (take 21 (csv/records head delimiter)))
         first-row (first preview)
         data-rows (vec (rest preview))
         evidence  (header-evidence first-row data-rows)
         stopped?  (volatile! false)
         counted   (with-open [r (files/reader file detection)]
                     (csv/reduce-records
                      r delimiter
                      (fn [acc record]
                        (let [acc (tally acc record)
                              n   (long (:records acc))]
                          (if (zero? (rem n check-interval))
                            (do (on-progress n)
                                (if (cancelled?)
                                  (do (vreset! stopped? true) (reduced acc))
                                  acc))
                            acc)))
                      (assoc empty-survey :fields nil)))
         damage    (select-keys counted [:ragged :stray-quote :unterminated-quote])]
     {:file           file
      :bytes          (.length file)
      :encoding       detection
      :delimiter      delimiter
      ;; Kept apart from the effective one, so the window can say "you chose
      ;; semicolons; detection found commas" rather than claiming to have
      ;; detected what the user typed in.
      :detected-delimiter detected
      ;; A file with one column is a valid CSV and structurally faultless, which
      ;; is why it used to be reported as healthy. It is also what a letter or a
      ;; log looks like to this application, and calling that healthy tells the
      ;; user something true and useless. Whether it looks like a table at all is
      ;; a separate question from whether it is damaged.
      :tabular?       (> (long (or (:fields counted) 0)) 1)
      :records        (:records counted)
      :fields         (or (:fields counted) 0)
      ;; The first two rows as parsed. Shown on the file card so that the
      ;; separator and the header decision can both be checked by eye — neither
      ;; is any use as a claim the user cannot verify.
      :first-row      (vec first-row)
      :preview        (vec (take 2 preview))
      :header         evidence
      :header-likely? (= :header (:verdict evidence))
      :damage         damage
      :healthy?       (every? zero? (vals damage))
      :cancelled?     @stopped?})))

(defn data-rows
  "Rows of actual data in `survey`, given whether the first row is a header."
  ^long [surveyed has-header?]
  (let [n (long (:records surveyed))]
    (if has-header? (max 0 (dec n)) n)))
