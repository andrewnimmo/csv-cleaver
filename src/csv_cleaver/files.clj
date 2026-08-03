(ns csv-cleaver.files
  "Opening CSV files for reading and writing without changing what they say.

   Two rules run through this namespace:

     * Whatever encoding a file arrived in, its pieces leave in the same one.
     * A byte-order mark on the input is reproduced on every output file. Excel
       leans on that mark to open a UTF-8 file correctly; dropping it turns
       every accented character into mojibake the moment the user double-clicks
       one of the results."
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
