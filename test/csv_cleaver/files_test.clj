(ns csv-cleaver.files-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.encoding :as encoding]
   [csv-cleaver.files :as files]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File)
   (java.nio.charset Charset)
   (java.nio.file Files)))

(deftest strips-and-finds-extensions
  (is (= "sales" (files/base-name (File. "sales.csv"))))
  (is (= "sales.2024" (files/base-name (File. "sales.2024.csv"))))
  (is (= "noext" (files/base-name (File. "noext"))))
  (is (= ".hidden" (files/base-name (File. ".hidden"))) "a leading dot is not an extension")
  (is (= "csv" (files/extension (File. "sales.csv"))))
  (is (= "tsv" (files/extension (File. "sales.tsv"))))
  (testing "output is CSV whatever the input was called"
    (is (= "csv" (files/extension (File. "noext"))))))

(deftest finds-the-parent-directory
  (is (= "/tmp" (.getAbsolutePath (files/parent-dir (File. "/tmp/a.csv")))))
  (testing "a bare relative name still has somewhere to go"
    (is (some? (files/parent-dir (File. "a.csv"))))))

(deftest reading-skips-a-byte-order-mark
  (tu/with-temp-dir [dir]
    (let [f (tu/write-file dir "a.csv" "a,b\n" "UTF-8" [0xEF 0xBB 0xBF])
          d (encoding/detect f)]
      (with-open [r (files/reader f d)]
        (is (= "a,b\n" (slurp r)) "the mark must not arrive as a stray character")))))

(deftest writing-reproduces-the-byte-order-mark
  (testing "Excel relies on the mark to open a UTF-8 file correctly; dropping it
            turns every accented character into mojibake"
    (tu/with-temp-dir [dir]
      (let [source (tu/write-file dir "in.csv" "name\nRené\n" "UTF-8" [0xEF 0xBB 0xBF])
            d      (encoding/detect source)
            out    (io/file dir "out.csv")]
        (with-open [w (files/writer out d)]
          (.write w "name\nRené\n"))
        (let [written (Files/readAllBytes (.toPath out))]
          (is (= [-17 -69 -65] (take 3 (vec written))))
          (is (= "name\nRené\n" (subs (tu/read-file out) 1))))))))

(deftest writing-omits-a-mark-that-was-never-there
  (tu/with-temp-dir [dir]
    (let [d   (encoding/detect (tu/write-file dir "in.csv" "a,b\n"))
          out (io/file dir "out.csv")]
      (with-open [w (files/writer out d)]
        (.write w "a,b\n"))
      (is (= "a,b\n" (tu/read-file out))))))

(deftest round-trips-windows-1252
  (tu/with-temp-dir [dir]
    (let [source (tu/write-file dir "in.csv" "name\nRené\n" "windows-1252" nil)
          d      (encoding/detect source)
          out    (io/file dir "out.csv")
          text   (with-open [r (files/reader source d)] (slurp r))]
      (with-open [w (files/writer out d)]
        (.write w ^String text))
      (is (= (vec (Files/readAllBytes (.toPath source)))
             (vec (Files/readAllBytes (.toPath out))))
          "byte for byte, in the encoding it arrived in"))))

(deftest measures-encoded-length
  (is (= 1 (files/byte-length "a" (Charset/forName "UTF-8"))))
  (is (= 2 (files/byte-length "é" (Charset/forName "UTF-8"))))
  (is (= 1 (files/byte-length "é" (Charset/forName "windows-1252")))))
