(ns csv-cleaver.csv
  "Reads CSV as *records* rather than as lines.

   This is the correction at the heart of the rewrite. A CSV field wrapped in
   double quotes may contain line breaks, so one logical record can span several
   physical lines:

       id,notes
       1,\"first line
       second line\"

   Splitting that on physical lines tears the record in half and silently
   corrupts both halves. Everything here works in terms of whole records.

   Records carry their own text verbatim, terminator included, so a record can
   be written straight back out without being re-quoted or normalised. What goes
   in comes out, byte for byte, in the same encoding.

   Nothing here holds more than one record in memory at a time.

   Hand-written rather than built on clojure.data.csv, for five reasons set out
   in docs/DECISIONS.md §17: verbatim output cannot survive a re-serialisation,
   malformed input has to be counted rather than thrown on, only record
   boundaries are needed and parsing every field would be wasted work, streaming
   here has to be cancellable and report progress, and no library sniffs the
   delimiter. The cost is that this is a state machine over quote state, doubled
   quotes, three terminators and end-of-file in every position — the one place
   in this codebase where a bug corrupts data instead of throwing. Change
   nothing here without reading csv_test.clj and hostile_input_test.clj first."
  (:import
   (java.io Reader StringReader)))

(def default-buffer-size 65536)

(def candidate-delimiters
  "Tried in order when sniffing an unknown file. Comma wins ties by being first."
  [\, \; \tab \|])

(definterface ICursor
  (^int rd [])
  (unrd []))

(deftype Cursor
         [^Reader rdr
          ^chars buf
          ^{:unsynchronized-mutable true :tag int} pos
          ^{:unsynchronized-mutable true :tag int} lim]
  ICursor
  (rd [_]
    (if (< pos lim)
      (let [c (aget buf pos)]
        (set! pos (unchecked-inc-int pos))
        (int c))
      (let [n (.read rdr buf 0 (alength buf))]
        (if (pos? n)
          (do (set! pos (int 1))
              (set! lim (int n))
              (int (aget buf 0)))
          (do (set! pos (int 0))
              (set! lim (int 0))
              -1)))))
  (unrd [_]
    (set! pos (unchecked-dec-int pos))
    nil))

(defn cursor
  "A buffered character cursor over `rdr`. Reading a character at a time through
   a BufferedReader is too slow for files big enough to break Excel, so this
   scans its own array instead."
  (^ICursor [rdr] (cursor rdr default-buffer-size))
  (^ICursor [^Reader rdr size] (Cursor. rdr (char-array size) (int 0) (int 0))))

(def ^:private dquote (int \"))
(def ^:private cr (int \return))
(def ^:private lf (int \newline))

(defn- finish
  [^StringBuilder sb fields damage terminator]
  {:text       (.toString sb)
   :fields     fields
   :terminator terminator
   :damage     (or damage #{})})

(defn read-record!
  "Read one logical CSV record from `cur`, accumulating its exact text into
   `sb` (which is cleared first). `delimiter` is a character code.

   Returns nil once the input is exhausted, otherwise a map:

     :text        the record verbatim, line terminator and all
     :fields      how many fields it holds
     :terminator  \"\\r\\n\", \"\\n\", \"\\r\", or \"\" for a final record with none
     :damage      a set, empty when the record is well formed:
                    :stray-quote          a quote where none is allowed, as in
                                          a\"b or \"a\"b — the file is malformed
                                          but the text is still passed through
                    :unterminated-quote   the file ended inside a quoted field"
  [^ICursor cur ^StringBuilder sb ^long delimiter]
  (.setLength sb 0)
  (loop [state :field-start
         fields 1
         damage nil]
    (let [c (.rd cur)]
      (cond
        (== c -1)
        (when (pos? (.length sb))
          (finish sb fields
                  (if (identical? state :quoted)
                    (conj (or damage #{}) :unterminated-quote)
                    damage)
                  ""))

        ;; Inside quotes every character is literal, including delimiters and
        ;; line breaks. This is the case the old line-based splitter got wrong.
        (identical? state :quoted)
        (do (.append sb (char c))
            (recur (if (== c dquote) :quote-seen :quoted) fields damage))

        ;; Just past a closing quote: another quote means it was an escaped one.
        (identical? state :quote-seen)
        (cond
          (== c dquote)
          (do (.append sb (char c)) (recur :quoted fields damage))

          (== c delimiter)
          (do (.append sb (char c)) (recur :field-start (inc fields) damage))

          (== c lf)
          (do (.append sb (char c)) (finish sb fields damage "\n"))

          (== c cr)
          (do (.append sb (char c))
              (let [n (.rd cur)]
                (if (== n lf)
                  (do (.append sb (char n)) (finish sb fields damage "\r\n"))
                  (do (when-not (== n -1) (.unrd cur))
                      (finish sb fields damage "\r")))))

          :else
          (do (.append sb (char c))
              (recur :unquoted fields (conj (or damage #{}) :stray-quote))))

        ;; Outside quotes.
        (== c dquote)
        (do (.append sb (char c))
            (if (identical? state :field-start)
              (recur :quoted fields damage)
              (recur :unquoted fields (conj (or damage #{}) :stray-quote))))

        (== c delimiter)
        (do (.append sb (char c)) (recur :field-start (inc fields) damage))

        (== c lf)
        (do (.append sb (char c)) (finish sb fields damage "\n"))

        (== c cr)
        (do (.append sb (char c))
            (let [n (.rd cur)]
              (if (== n lf)
                (do (.append sb (char n)) (finish sb fields damage "\r\n"))
                (do (when-not (== n -1) (.unrd cur))
                    (finish sb fields damage "\r")))))

        :else
        (do (.append sb (char c)) (recur :unquoted fields damage))))))

(defn reduce-records
  "Reduce `f` over the records of `rdr`, starting from `init`.

   `f` is called as (f acc record) and may return a `reduced` value to stop
   early — which is how cancellation works. Records are never retained, so a
   file of any size streams in constant memory."
  ([rdr f init] (reduce-records rdr \, f init))
  ([^Reader rdr delimiter f init]
   (let [cur (cursor rdr)
         sb  (StringBuilder. 256)
         d   (long (int delimiter))]
     (loop [acc init]
       (if (reduced? acc)
         @acc
         (if-let [record (read-record! cur sb d)]
           (recur (f acc record))
           acc))))))

(defn records
  "Every record of `s` as a vector. For tests and for sniffing a short sample —
   never for whole files."
  ([s] (records s \,))
  ([^String s delimiter]
   (with-open [rdr (StringReader. s)]
     (persistent! (reduce-records rdr delimiter conj! (transient []))))))

(defn parse-fields
  "Split one record's raw `text` into its field values, with the quoting taken
   off: a wrapped field loses its wrapper, and a doubled quote inside one
   becomes a single quote. The trailing line terminator is dropped.

   Splitting never needs this — records are copied verbatim — but working out
   whether the first row is a header does."
  ([^String text] (parse-fields text \,))
  ([^String text delimiter]
   (let [d   (char delimiter)
         len (.length text)]
     (loop [i 0, quoted? false, sb (StringBuilder.), out []]
       (if (= i len)
         (conj out (.toString sb))
         (let [c (.charAt text i)]
           (cond
             quoted?
             (if (= c \")
               (if (and (< (inc i) len) (= (.charAt text (inc i)) \"))
                 (do (.append sb \") (recur (+ i 2) true sb out))
                 (recur (inc i) false sb out))
               (do (.append sb c) (recur (inc i) true sb out)))

             (= c \") (recur (inc i) true sb out)
             (= c d) (recur (inc i) false (StringBuilder.) (conj out (.toString sb)))
             (or (= c \newline) (= c \return)) (recur (inc i) false sb out)
             :else (do (.append sb c) (recur (inc i) false sb out)))))))))

(defn detect-delimiter
  "Guess the delimiter of `head`, a sample taken from the top of a file.

   A delimiter is credible when it splits the sample into more than one field
   and gives every record the same field count. The candidate producing the most
   fields wins, because a semicolon-separated file usually contains commas too,
   but not the other way round. Falls back to a comma, which is also what a
   single-column file gets — and for a single column every delimiter is right."
  [^String head]
  (let [sample (fn [d]
                 (let [rs (take 10 (records head d))
                       rs (if (> (count rs) 1) (butlast rs) rs)]
                   (map :fields rs)))]
    (or (->> candidate-delimiters
             (map (fn [d] [d (sample d)]))
             (filter (fn [[_ counts]]
                       (and (seq counts)
                            (> (first counts) 1)
                            (apply = counts))))
             (sort-by (fn [[_ counts]] (- (first counts))))
             ffirst)
        \,)))
