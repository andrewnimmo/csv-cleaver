(ns csv-cleaver.files
  "Opening CSV files for reading and writing without changing what they say.

   Two rules run through this namespace:

     * Whatever encoding a file arrived in, its pieces leave in the same one.
     * A byte-order mark on the input is reproduced on every output file. Excel
       leans on that mark to open a UTF-8 file correctly; dropping it turns
       every accented character into mojibake the moment the user double-clicks
       one of the results."
  (:require
   [clojure.string :as str]
   [csv-cleaver.text :as text])
  (:import
   (java.io BufferedWriter File FileInputStream FileOutputStream
            InputStreamReader OutputStreamWriter Reader Writer)
   (java.nio.charset Charset)))

(def write-buffer-size 65536)

(defn reader
  "A character reader over `file` in the encoding described by `detection`,
   positioned past any byte-order mark."
  ^Reader [^File file {:keys [charset bom-length]}]
  (let [in (FileInputStream. file)]
    (when (pos? (long (or bom-length 0)))
      (.skip in (long bom-length)))
    (InputStreamReader. in ^Charset charset)))

(defn writer
  "A character writer over `file` in the encoding described by `detection`,
   with the same byte-order mark the input carried, if any."
  ^Writer [^File file {:keys [charset bom-bytes]}]
  (let [out (FileOutputStream. file)]
    (when (seq bom-bytes)
      (.write out (byte-array (map unchecked-byte bom-bytes))))
    (BufferedWriter. (OutputStreamWriter. out ^Charset charset)
                     write-buffer-size)))

(defn base-name
  "The file's name with its extension removed: sales.2024.csv -> sales.2024"
  [^File file]
  (let [n (.getName file)
        i (.lastIndexOf n ".")]
    (if (pos? i) (subs n 0 i) n)))

(defn extension
  "The file's extension without the dot, or \"csv\" when it has none — the
   output of this application is always CSV whatever the input was called."
  [^File file]
  (let [n (.getName file)
        i (.lastIndexOf n ".")]
    (if (pos? i) (subs n (inc i)) "csv")))

(defn parent-dir
  "The directory holding `file`, falling back to the working directory for a
   bare relative name such as \"data.csv\"."
  ^File [^File file]
  (or (.getParentFile (.getAbsoluteFile file))
      (File. ".")))

(defn byte-length
  "Bytes `text` occupies once encoded as `charset`. Used when splitting to a
   size rather than a row count."
  ^long [^String text ^Charset charset]
  (alength (.getBytes text charset)))

(defn delete-tree!
  "Remove `file` and, if it is a directory, everything below it.

   Only ever pointed at a temporary folder this application created for an
   uploaded file and its results. Nothing a user chose is removed this way — a
   file in the way of a split goes to the Trash, where it can be got back."
  [^File file]
  (when (and file (.exists file))
    (when (.isDirectory file)
      (doseq [child (or (.listFiles file) (make-array File 0))]
        (delete-tree! child)))
    (.delete file)))

(defn inspect-dir
  "What is already at the destination, so the window can say so before anything
   is written rather than at the moment something is about to be replaced.

   Counts CSV files only: those are the ones that could be confused with, or
   taken over by, this split's output."
  [^File dir]
  (if (and dir (.isDirectory dir))
    {:exists?   true
     :csv-count (count (filter (fn [^File f]
                                 (and (.isFile f)
                                      (str/ends-with?
                                       (text/lower (.getName f)) ".csv")))
                               (or (.listFiles dir) (make-array File 0))))}
    {:exists? false :csv-count 0}))
