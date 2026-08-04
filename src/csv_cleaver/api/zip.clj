(ns csv-cleaver.api.zip
  "Handing a split's output back over HTTP as one archive.

   A caller that uploaded a file cannot see where the results were written, so
   this is the only way it gets them. The archive is produced as it is read
   rather than built first: a split large enough to be worth doing is large
   enough that writing it out a second time is not a reasonable thing to do to
   someone's disk."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io File InputStream PipedInputStream PipedOutputStream)
   (java.util.zip ZipEntry ZipOutputStream)))

(def buffer-size
  "How far the writing thread may run ahead of the reader."
  (* 256 1024))

(defn- unique-names
  "Names inside the archive. Two output files cannot normally share a name, but
   an archive with a repeated entry is a broken archive, so it is not left to
   chance."
  [files]
  (first
   (reduce (fn [[names seen] ^File f]
             (let [base (.getName f)
                   n    (get seen base 0)]
               [(conj names (if (zero? n) base (str n "-" base)))
                (assoc seen base (inc n))]))
           [[] {}]
           files)))

(defn stream
  "An InputStream of a zip containing `files`.

   The writing happens on a daemon thread. A client that hangs up mid-download
   breaks the pipe, which ends that thread — the exception is swallowed
   deliberately: there is nobody left to report it to, and it means only that
   the caller stopped listening."
  ^InputStream [files]
  (let [in    (PipedInputStream. (int buffer-size))
        out   (PipedOutputStream. in)
        names (unique-names files)]
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               (with-open [zos (ZipOutputStream. out)]
                 (doseq [[^File f entry-name] (map vector files names)]
                   (when (.isFile f)
                     (.putNextEntry zos (ZipEntry. ^String entry-name))
                     (io/copy f zos)
                     (.closeEntry zos))))
               (catch Exception _
                 (try (.close out) (catch Exception _ nil)))))
           "csv-cleaver-api-zip")
      (.setDaemon true)
      (.start))
    in))
