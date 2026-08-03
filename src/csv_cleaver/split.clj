(ns csv-cleaver.split
  "Turning one CSV file into several.

   Two guarantees hold throughout:

     * A record is never divided. Chunk boundaries fall between records, so a
       quoted field containing line breaks survives intact.
     * Records are copied verbatim in the encoding they arrived in. Nothing is
       re-quoted, re-spaced or otherwise tidied up on the way through, so a
       field this application does not understand is still a field it cannot
       damage.

   `plan` works out what a split would do without touching the disk, which is
   what feeds the sentence in the window and the collision check. `run!` then
   performs it."
  (:require
   [csv-cleaver.csv :as csv]
   [csv-cleaver.desktop :as desktop]
   [csv-cleaver.files :as files]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.scan :as scan])
  (:import
   (java.io File Writer)))

(def progress-interval
  "Records between progress callbacks and cancellation checks."
  5000)

(def busy-file-count
  "Above this many output files, warn the user rather than silently carpeting
   their folder."
  1000)

(def alarming-file-count
  "Above this many, a warning is not enough. Tens of thousands of files in one
   folder makes it slow or unusable in a file manager, takes a long time, and is
   far more often a mistyped row count than an intention. Orange text is easy to
   read as decoration — especially the first time someone uses the application,
   when they have nothing to compare it against — so this asks outright."
  10000)

(def excel-row-limit
  "The most rows a worksheet can hold in Excel 2007 and later. A file with more
   than this cannot be opened, which would defeat the point of the exercise."
  1048576)

(def space-margin
  "Fraction of the needed space to insist on over and above it. Output is a
   little larger than input when a header is repeated, the estimate is only an
   estimate, and filling someone's disk to the last byte is its own kind of
   failure."
  0.05)

(defn- ceil-div ^long [^long a ^long b]
  (if (zero? b) 0 (quot (+ a (dec b)) b)))

(defn free-space
  "Usable bytes on the volume that would hold `dir`.

   The folder may not exist yet, so this walks up to the nearest ancestor that
   does. Returns nil when nothing can be determined, in which case the check is
   skipped rather than guessed at."
  [^File dir]
  (when dir
    (loop [d (.getAbsoluteFile dir)]
      (cond
        (nil? d)        nil
        (.exists d)     (let [usable (.getUsableSpace d)]
                          (when (pos? usable) usable))
        :else           (recur (.getParentFile d))))))

(defn required-space
  "Bytes the output will occupy, near enough to decide whether it will fit.

   The pieces together are about the size of the original. What makes them
   larger is a header repeated into every file, and a byte-order mark on each,
   so those are added per file."
  ^long [{:keys [survey file-count include-header? has-header?]}]
  (let [total     (long (:bytes survey 0))
        records   (long (:records survey 0))
        per-row   (if (pos? records) (quot total records) 0)
        bom       (count (get-in survey [:encoding :bom-bytes]))
        extras    (if (and has-header? include-header?) (+ per-row bom) bom)]
    (+ total (* (max 0 (dec (long (or file-count 1)))) extras))))

(defn plan
  "Describe what a split would produce, without writing anything.

     :survey           from csv-cleaver.scan/survey
     :mode             :rows or :bytes
     :value            rows per file, or bytes per file
     :has-header?      the first record names the columns
     :include-header?  repeat that row in every output file

   Returns :mode, :file-count, :exact? (false when splitting by size, where the
   count depends on the data), :data-rows, :rows-per-file, :last-file-rows,
   :row-cap, plus :problem when the settings cannot work and :warning when they
   can but probably should not.

   Splitting by size raises a problem the row mode does not have. The user
   chooses a number of megabytes, never a number of rows, so with short rows a
   perfectly reasonable target can yield files of three million rows — which
   Excel refuses to open, defeating the whole purpose. When `excel-safe?` is
   set, a size split also rolls over at Excel's row limit, whichever comes
   first.

   The reverse case is left alone. If the user types two million rows per file
   they are told that Excel will not open the result, but the number they typed
   is honoured: they may not be using Excel at all, and silently overriding an
   explicit instruction is worse than an unopenable file."
  [{:keys [survey mode value has-header? include-header? excel-safe? out-dir]
    :or   {excel-safe? true}}]
  (let [value     (long (or value 0))
        rows      (scan/data-rows survey has-header?)
        records   (long (:records survey 0))
        avg-bytes (if (pos? records)
                    (max 1 (quot (long (:bytes survey 0)) records))
                    1)]
    (cond
      (not (pos? value))
      {:mode mode :problem (if (= mode :rows)
                             :problem/rows-needed
                             :problem/size-needed)}

      (zero? rows)
      {:mode mode :problem :problem/no-data}

      :else
      (let [row-cap   (when (and excel-safe? (= mode :bytes)) excel-row-limit)
            estimated (if (= mode :rows) value (max 1 (quot value avg-bytes)))
            per-file  (if row-cap (min estimated (long row-cap)) estimated)
            files     (ceil-div rows per-file)
            last-rows (let [r (rem rows per-file)]
                        (if (zero? r) per-file r))
            needed    (required-space {:survey survey :file-count files
                                       :has-header? has-header?
                                       :include-header? include-header?})
            free      (free-space out-dir)
            headroom  (when free (- free (long (* needed (+ 1.0 space-margin)))))]
        (cond-> {:mode           mode
                 :file-count     files
                 :exact?         (= mode :rows)
                 :data-rows      rows
                 :rows-per-file  per-file
                 :last-file-rows last-rows
                 :row-cap        row-cap
                 :required-bytes needed
                 :free-bytes     free}

          ;; A "split" that would produce a single file is not a split. Copying
          ;; the input under a new name is not what anyone asked for, and going
          ;; ahead also drags the user through a name-clash dialog about files
          ;; this run will never write. The plan is still described in full, so
          ;; the window can say what would have happened.
          (= 1 files)
          (assoc :problem {:key :problem/nothing-to-split :args [rows]})

          ;; Refused, not warned about. Filling someone's disk is worse than
          ;; declining to split, and once it is full the failure spreads well
          ;; beyond this application.
          (and headroom (neg? headroom))
          (assoc :problem {:key :problem/not-enough-space
                           :args [needed free] :arg-format :bytes})

          (and headroom (not (neg? headroom)) (< headroom (quot free 20)))
          (assoc :warning {:key :plan/tight-space
                           :args [headroom] :arg-format :bytes})

          (and (= mode :rows) (> value excel-row-limit))
          (assoc :warning {:key :plan/over-excel :args [excel-row-limit]})

          (and row-cap (< (long row-cap) estimated))
          (assoc :warning {:key :plan/capped-at-excel :args [excel-row-limit]})

          (> files busy-file-count)
          (assoc :warning {:key :plan/many-files :args [files]}))))))

(defn- ensure-dir!
  [^File dir]
  (when-not (.isDirectory dir)
    (when-not (.mkdirs dir)
      (throw (ex-info (str "Could not create " (.getAbsolutePath dir))
                      {:dir     dir
                       :message {:key  :problem/folder-create
                                 :args [(.getAbsolutePath dir)]}}))))
  dir)

(defn- terminated
  "`text` with a line terminator on the end, so a header written into the top of
   an output file cannot run into the first data row. Only ever relevant for a
   source file whose final record has no terminator."
  [^String text fallback]
  (if (or (.endsWith text "\n") (.endsWith text "\r"))
    text
    (str text fallback)))

(defn execute!
  "Split the surveyed file. Blocking — call it on a background thread.

     :survey           from csv-cleaver.scan/survey
     :out-dir          java.io.File to write into, created if missing
     :mode :value :has-header? :include-header?   as for `plan`
     :template         file name pattern, defaults to {name}_{index}
     :plan             the result of `plan`, used for the index padding
     :cancelled?       zero-argument predicate, polled every few thousand rows
     :on-progress      called with {:rows-done :files-done :current-name}

   Returns :files (what was written and kept), :rows, :elapsed-ms, :cancelled?
   and :abandoned — the partial file deleted on cancellation, or nil.

   On cancellation the file being written is closed and removed, so what is left
   on disk is always a whole number of complete output files."
  [{:keys [survey out-dir mode value has-header? include-header? template
           cancelled? on-progress replace-existing remove-file]
    file-plan :plan
    :or   {template    naming/default-template
           cancelled?  (constantly false)
           on-progress (fn [_])
           ;; Injectable so that tests never go near the real Trash.
           remove-file desktop/move-to-trash!}}]
  (let [{:keys [file encoding delimiter]} survey
        charset   (:charset encoding)
        dir       (ensure-dir! out-dir)
        base      (files/base-name file)
        extension (files/extension file)
        width     (naming/pad-width (:file-count file-plan 1))
        row-cap   (:row-cap file-plan)
        by-bytes? (= mode :bytes)
        limit     (long value)
        started   (System/nanoTime)
        state     (volatile! {:writer nil :current nil :header nil
                              :header-seen? false :rows 0 :byte-count 0
                              :index 0 :files [] :written []})]
    (letfn [(close-current! []
              (when-let [^Writer w (:writer @state)]
                (.close w)
                (vswap! state (fn [s]
                                (-> s
                                    (update :written conj {:file (:current s)
                                                           :rows (:rows s)})
                                    (update :files conj (:current s))
                                    (assoc :writer nil :current nil))))))
            (open-next! []
              (close-current!)
              (let [index (inc (long (:index @state)))
                    fname (naming/render template {:base      base
                                                   :index     index
                                                   :width     width
                                                   :extension extension})
                    f     (File. dir ^String fname)
                    w     (files/writer f encoding)]
                (when-let [header (and include-header? (:header @state))]
                  (.write w ^String header))
                (vswap! state assoc :writer w :current f :index index
                        :rows 0 :byte-count 0)
                (on-progress {:rows-done    (long (:total @state 0))
                              :files-done   (dec index)
                              :current-name fname})))
            (abandon! []
              (let [w (:writer @state)
                    f (:current @state)]
                (when w (.close ^Writer w))
                (when (and f (.exists ^File f)) (.delete ^File f))
                (vswap! state assoc :writer nil :current nil)
                f))]
      (let [outcome
            (with-open [rdr (files/reader file encoding)]
              (csv/reduce-records
               rdr delimiter
               (fn [_ {:keys [text terminator]}]
                 (if (and has-header? (not (:header-seen? @state)))
                   (do (vswap! state assoc
                               :header-seen? true
                               :header (terminated text (if (seq terminator) terminator "\n")))
                       nil)
                   (let [record-bytes (if by-bytes?
                                        (files/byte-length text charset)
                                        0)
                         {:keys [writer rows byte-count]} @state
                         roll? (or (nil? writer)
                                   (if by-bytes?
                                     (or (and (pos? (long rows))
                                              (> (+ (long byte-count) (long record-bytes)) limit))
                                         ;; Whichever comes first: the size the
                                         ;; user asked for, or the row limit
                                         ;; that keeps the file openable.
                                         (and row-cap (>= (long rows) (long row-cap))))
                                     (>= (long rows) limit)))]
                     (when roll? (open-next!))
                     (let [^Writer w (:writer @state)]
                       (.write w ^String text))
                     (vswap! state (fn [s]
                                     (-> s
                                         (update :rows inc)
                                         (update :total (fnil inc 0))
                                         (update :byte-count + record-bytes))))
                     (let [total (long (:total @state))]
                       (when (zero? (rem total progress-interval))
                         ;; :files-done means files *finished*, everywhere. It
                         ;; used to be the current file's number here and the
                         ;; count of finished ones in open-next!, so the window
                         ;; announced "File 2" while writing the first.
                         (on-progress {:rows-done    total
                                       :files-done   (max 0 (dec (long (:index @state))))
                                       :current-name (when-let [f (:current @state)]
                                                       (.getName ^File f))})
                         (when (cancelled?) (reduced :cancelled)))))))
               nil))
            cancelled (= outcome :cancelled)
            abandoned (if cancelled (abandon!) (do (close-current!) nil))
            ;; "Replace them" has to mean it, or the folder ends up part new and
            ;; part old with nothing to tell them apart. But these files were
            ;; recognised by their names alone — this application has no idea
            ;; what they actually are, and the folder may have been chosen by
            ;; mistake. So they go to the Trash, never to deletion, and only
            ;; after the split has succeeded.
            others    (when (and (seq replace-existing) (not cancelled))
                        (let [kept (set (map (fn [^File f] (.getAbsolutePath f))
                                             (:files @state)))]
                          (remove #(contains? kept (.getAbsolutePath ^File %))
                                  replace-existing)))
            sorted    (group-by #(boolean (remove-file %)) others)]
        {:files       (:files @state)
         :written     (:written @state)
         ;; What went to the Trash, and what could not be moved there — on a
         ;; platform without one, nothing is touched and the window says so.
         :trashed     (vec (get sorted true))
         :left-behind (vec (get sorted false))
         :rows        (long (:total @state 0))
         :elapsed-ms  (quot (- (System/nanoTime) started) 1000000)
         :cancelled?  cancelled
         :abandoned   abandoned}))))

(defn collisions
  "Files already sitting in `out-dir` that this split would take the names of.
   Called before a single byte is written; an empty result means the split
   cannot destroy anything."
  [{:keys [survey out-dir template]}]
  (let [file (:file survey)]
    (naming/existing-outputs
     out-dir
     {:template  (or template naming/default-template)
      :base      (files/base-name file)
      :extension (files/extension file)})))
