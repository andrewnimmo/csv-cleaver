(ns csv-cleaver.naming
  "Names for the output files, and the check that stops this application from
   ever quietly replacing one.

   The index is zero-padded wide enough for the number of files actually being
   written, so a split into 12,000 pieces still sorts correctly in a file
   manager — the old fixed %04d silently stopped sorting past 9,999."
  (:require
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.util Locale)
   (java.util.regex Pattern)))

(def default-template "{name}_{index}")

(def minimum-pad-width 4)

(def illegal-name-chars #"[/\\:*?\"<>|]")

(defn pad-width
  "Digits to use for the index when writing `file-count` files."
  ^long [file-count]
  (max minimum-pad-width (count (str (max 1 (long file-count))))))

(defn render
  "Fill in `template` for one output file. Recognised tokens are {name}, which
   becomes the input file's name without its extension, and {index}."
  [template {:keys [base index width extension]}]
  (str (-> (str template)
           (str/replace "{name}" (str base))
           ;; Locale/ROOT so that a machine set to a locale with non-Latin
           ;; digits still writes file names a file manager can sort.
           (str/replace "{index}" (String/format Locale/ROOT
                                                 (str "%0" (or width minimum-pad-width) "d")
                                                 (to-array [(long index)]))))
       "." extension))

(defn template-problem
  "Why `template` cannot be used, as a translation key, or nil when it is fine.
   A key rather than a sentence: this namespace has no idea what language the
   window is in, and should not have to."
  [template]
  (let [t       (str template)
        literal (-> t (str/replace "{name}" "") (str/replace "{index}" ""))]
    (cond
      (str/blank? t)                       :problem/pattern-empty
      (not (str/includes? t "{index}"))    :problem/pattern-index
      (re-find illegal-name-chars literal) :problem/pattern-chars)))

(defn output-names
  "The names of all `file-count` output files, in order."
  [{:keys [template base extension file-count]}]
  (let [width (pad-width file-count)]
    (mapv #(render (or template default-template)
                   {:base base :index % :width width :extension extension})
          (range 1 (inc (long file-count))))))

(defn output-pattern
  "A regular expression matching every name `template` could produce for this
   input file, whatever the index.

   Collisions are found with this rather than against an exact list of names,
   for two reasons. A split by file size does not know its own file count until
   it has finished, so there is no exact list to compare. And it catches the
   more insidious case: leftovers from an earlier, larger split of the same
   file, which would otherwise sit in the output folder looking for all the
   world like part of the new results."
  ^Pattern [{:keys [template base extension]}]
  (let [body (->> (re-seq #"\{name\}|\{index\}|[^{]+|\{"
                          (str (or template default-template)))
                  (map (fn [part]
                         (case part
                           "{name}"  (Pattern/quote (str base))
                           "{index}" "\\d+"
                           (Pattern/quote part))))
                  (apply str))]
    (Pattern/compile (str "(?i)" body "\\." (Pattern/quote (str extension))))))

(defn existing-outputs
  "Files already in `dir` whose names this split could take, sorted by name.
   Empty when the folder is clear."
  [^File dir opts]
  (let [pattern (output-pattern opts)]
    (->> (or (.listFiles dir) (make-array File 0))
         (filter (fn [^File f] (.isFile f)))
         (filter (fn [^File f] (re-matches pattern (.getName f))))
         (sort-by (fn [^File f] (.getName f)))
         vec)))
