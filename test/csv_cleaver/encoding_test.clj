(ns csv-cleaver.encoding-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.encoding :as encoding]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.nio.charset Charset)))

(def utf-8-bom [0xEF 0xBB 0xBF])
(def utf-16le-bom [0xFF 0xFE])
(def utf-16be-bom [0xFE 0xFF])
(def utf-32le-bom [0xFF 0xFE 0x00 0x00])
(def utf-32be-bom [0x00 0x00 0xFE 0xFF])

(deftest detects-a-utf-8-byte-order-mark
  (tu/with-temp-dir [dir]
    (let [f (tu/write-file dir "a.csv" "a,b\n" "UTF-8" utf-8-bom)
          d (encoding/detect f)]
      (is (= :bom (:basis d)))
      (is (= "UTF-8" (:label d)))
      (is (= 3 (:bom-length d)))
      (is (= utf-8-bom (:bom-bytes d))))))

(deftest detects-utf-16-marks
  (tu/with-temp-dir [dir]
    (doseq [[bom label] [[utf-16le-bom "UTF-16LE"] [utf-16be-bom "UTF-16BE"]]]
      (let [f (tu/write-file dir (str label ".csv") "a,b\n" label bom)
            d (encoding/detect f)]
        (is (= label (:label d)) label)
        (is (= 2 (:bom-length d)))))))

(deftest utf-32le-is-not-mistaken-for-utf-16le
  (testing "the UTF-32LE mark starts with the whole UTF-16LE mark, so testing
            shortest-first would get this wrong"
    (tu/with-temp-dir [dir]
      (let [f (tu/write-file dir "a.csv" "a,b\n" "UTF-32LE" utf-32le-bom)
            d (encoding/detect f)]
        (is (= "UTF-32LE" (:label d)))
        (is (= 4 (:bom-length d)))))))

(deftest detects-utf-32be
  (tu/with-temp-dir [dir]
    (let [f (tu/write-file dir "a.csv" "a,b\n" "UTF-32BE" utf-32be-bom)]
      (is (= "UTF-32BE" (:label (encoding/detect f)))))))

(deftest plain-utf-8-without-a-mark
  (tu/with-temp-dir [dir]
    (let [f (tu/write-file dir "a.csv" "name,city\nRené,Köln\n")
          d (encoding/detect f)]
      (is (= :utf-8 (:basis d)))
      (is (= 0 (:bom-length d)))
      (is (empty? (:bom-bytes d))))))

(deftest falls-back-to-windows-1252
  (testing "what Excel writes on Windows: high bytes that are not valid UTF-8"
    (tu/with-temp-dir [dir]
      (let [f (tu/write-file dir "a.csv" "name\nRené\n" "windows-1252" nil)
            d (encoding/detect f)]
        (is (= :fallback (:basis d)))
        (is (= "windows-1252" (:label d)))))))

(deftest ascii-is-treated-as-utf-8
  (tu/with-temp-dir [dir]
    (is (= :utf-8 (:basis (encoding/detect (tu/write-file dir "a.csv" "a,b\n1,2\n")))))))

(deftest an-empty-file-is-utf-8
  (tu/with-temp-dir [dir]
    (let [d (encoding/detect (tu/write-file dir "empty.csv" ""))]
      (is (= "UTF-8" (:label d)))
      (is (= :utf-8 (:basis d))))))

(deftest read-sample-stops-at-the-end-of-a-short-file
  (tu/with-temp-dir [dir]
    (is (= 4 (alength (encoding/read-sample (tu/write-file dir "a.csv" "abcd")))))))

(deftest read-sample-caps-at-the-sample-size
  (tu/with-temp-dir [dir]
    (let [big (apply str (repeat (* 2 encoding/sample-size) "x"))]
      (is (= encoding/sample-size
             (alength (encoding/read-sample (tu/write-file dir "big.csv" big))))))))

(deftest match-bom-returns-nil-without-one
  (is (nil? (encoding/match-bom (byte-array [1 2 3]))))
  (is (nil? (encoding/match-bom (byte-array 0))))
  (testing "a file too short to hold the mark it starts to look like"
    (is (nil? (encoding/match-bom (byte-array [(unchecked-byte 0xEF)]))))))

(deftest decodes-cleanly-distinguishes-truncation-from-corruption
  (let [two-thirds-of-a-euro-sign (byte-array [(unchecked-byte 0xE2) (unchecked-byte 0x82)])]
    (testing "mid-character at the end of a sample is not corruption"
      (is (encoding/decodes-cleanly? two-thirds-of-a-euro-sign
                                     (Charset/forName "UTF-8") false)))
    (testing "the same bytes at the true end of a file are"
      (is (not (encoding/decodes-cleanly? two-thirds-of-a-euro-sign
                                          (Charset/forName "UTF-8") true))))))

(deftest describes-each-basis-in-plain-words
  (is (= "Text: UTF-8" (encoding/describe {:label "UTF-8" :basis :bom})))
  (is (= "Text: UTF-8" (encoding/describe {:label "UTF-8" :basis :utf-8})))
  (is (= "Text: windows-1252 (Western European)"
         (encoding/describe {:label "windows-1252" :basis :fallback}))))
