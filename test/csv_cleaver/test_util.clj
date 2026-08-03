(ns csv-cleaver.test-util
  "Temporary files and folders for tests, cleaned up afterwards."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io File)
   (java.nio.charset Charset)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn temp-dir
  ^File []
  (.toFile (Files/createTempDirectory "csv-cleaver" (make-array FileAttribute 0))))

(defn delete-tree!
  [^File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)] (delete-tree! child)))
  (.delete f))

(defmacro with-temp-dir
  "Bind `sym` to a fresh temporary directory, removed however the body ends."
  [[sym] & body]
  `(let [~sym (temp-dir)]
     (try ~@body
          (finally (delete-tree! ~sym)))))

(defn write-file
  "Write `content` into `dir` as `filename`, in `charset` (UTF-8 by default),
   prefixed with `bom` bytes when given. Returns the File."
  (^File [dir filename content] (write-file dir filename content "UTF-8" nil))
  (^File [dir filename ^String content charset bom]
   (let [file (io/file dir filename)]
     (io/make-parents file)
     (with-open [out (io/output-stream file)]
       (when (seq bom)
         (.write out (byte-array (map unchecked-byte bom))))
       (.write out (.getBytes content (Charset/forName charset))))
     file)))

(defn read-file
  (^String [^File file] (read-file file "UTF-8"))
  (^String [^File file charset]
   (String. (Files/readAllBytes (.toPath file)) (Charset/forName charset))))

(defn names
  "Just the file names, for readable assertions."
  [files]
  (mapv (fn [^File f] (.getName f)) files))
