(ns csv-cleaver.csv-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.csv :as csv])
  (:import
   (java.io StringReader)))

(deftest reads-plain-records
  (let [rs (csv/records "a,b\n1,2\n")]
    (is (= 2 (count rs)))
    (is (= ["a,b\n" "1,2\n"] (mapv :text rs)))
    (is (= [2 2] (mapv :fields rs)))
    (is (every? (comp empty? :damage) rs))))

(deftest keeps-a-quoted-newline-inside-one-record
  (testing "the defect that made the old line-based splitter corrupt data"
    (let [rs (csv/records "id,notes\n1,\"first\nsecond\"\n2,plain\n")]
      (is (= 3 (count rs)) "the quoted newline must not start a new record")
      (is (= "1,\"first\nsecond\"\n" (:text (second rs))))
      (is (= 2 (:fields (second rs)))))))

(deftest preserves-each-terminator-exactly
  (let [rs (csv/records "a\r\nb\nc\rd")]
    (is (= ["a\r\n" "b\n" "c\r" "d"] (mapv :text rs)))
    (is (= ["\r\n" "\n" "\r" ""] (mapv :terminator rs)))))

(deftest handles-doubled-quotes
  (let [rs (csv/records "1,\"say \"\"hi\"\" now\"\n")]
    (is (= 1 (count rs)))
    (is (empty? (:damage (first rs))))
    (is (= 2 (:fields (first rs))))))

(deftest quoted-delimiters-do-not-split-fields
  (is (= 2 (:fields (first (csv/records "1,\"a,b,c\"\n"))))))

(deftest reports-a-stray-quote
  (testing "a quote in the middle of an unquoted field"
    (is (= #{:stray-quote} (:damage (first (csv/records "a\"b,c\n"))))))
  (testing "text after a closing quote"
    (is (= #{:stray-quote} (:damage (first (csv/records "\"ab\"c,d\n")))))))

(deftest reports-an-unterminated-quote
  (let [r (first (csv/records "1,\"never closed\n"))]
    (is (= #{:unterminated-quote} (:damage r)))
    (is (= "1,\"never closed\n" (:text r)))))

(deftest empty-input-has-no-records
  (is (= [] (csv/records ""))))

(deftest a-lone-terminator-is-a-record
  (is (= ["\n"] (mapv :text (csv/records "\n")))))

(deftest cursor-refills-across-buffer-boundaries
  (testing "a record longer than the read buffer, and a \\r landing on the very
            last character of a buffer, which is where unrd has to work"
    (let [text (str (apply str (repeat 100 "abcdefghij")) "\r\n" "x,y\r\n")]
      (with-open [rdr (StringReader. text)]
        (let [cur (csv/cursor rdr 8)
              sb  (StringBuilder.)
              a   (csv/read-record! cur sb (long (int \,)))
              b   (csv/read-record! cur sb (long (int \,)))]
          (is (= 1002 (count (:text a))))
          (is (= "x,y\r\n" (:text b)))
          (is (nil? (csv/read-record! cur sb (long (int \,))))))))))

(deftest reduce-records-can-stop-early
  (let [seen (atom 0)]
    (with-open [rdr (StringReader. "a\nb\nc\nd\n")]
      (csv/reduce-records rdr \, (fn [acc _]
                                   (swap! seen inc)
                                   (if (= 2 @seen) (reduced acc) acc))
                          nil))
    (is (= 2 @seen))))

(deftest reduce-records-defaults-to-comma
  (with-open [rdr (StringReader. "a,b\n")]
    (is (= 2 (:fields (csv/reduce-records rdr (fn [_ r] r) nil))))))

(deftest parses-fields-with-quoting-removed
  (is (= ["a" "b" "c"] (csv/parse-fields "a,b,c\n")))
  (is (= ["a,b" "c"] (csv/parse-fields "\"a,b\",c\n")))
  (is (= ["say \"hi\""] (csv/parse-fields "\"say \"\"hi\"\"\"\n")))
  (is (= ["one\ntwo"] (csv/parse-fields "\"one\ntwo\"")))
  (is (= ["" ""] (csv/parse-fields ",\n")))
  (is (= ["a" "b"] (csv/parse-fields "a;b\n" \;))))

(deftest detects-the-delimiter
  (is (= \, (csv/detect-delimiter "a,b,c\n1,2,3\n")))
  (is (= \; (csv/detect-delimiter "a;b;c\n1;2;3\n")))
  (is (= \tab (csv/detect-delimiter "a\tb\tc\n1\t2\t3\n")))
  (is (= \| (csv/detect-delimiter "a|b|c\n1|2|3\n")))
  (testing "a semicolon file whose fields also contain commas"
    (is (= \; (csv/detect-delimiter "name;note\nAnn;a, b, c\nBob;d, e, f\n"))))
  (testing "nothing separator-like falls back to a comma"
    (is (= \, (csv/detect-delimiter "single\ncolumn\n")))
    (is (= \, (csv/detect-delimiter "")))))
