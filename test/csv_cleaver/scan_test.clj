(ns csv-cleaver.scan-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.test-util :as tu]))

(deftest recognises-numbers-as-people-write-them
  (is (scan/numeric-looking? "42"))
  (is (scan/numeric-looking? "-3.5"))
  (is (scan/numeric-looking? "1,024"))
  (is (scan/numeric-looking? "99%"))
  (is (scan/numeric-looking? "£12.50"))
  (is (scan/numeric-looking? " 7 "))
  (is (scan/numeric-looking? "1.2e3"))
  (is (not (scan/numeric-looking? "name")))
  (is (not (scan/numeric-looking? "")))
  (is (not (scan/numeric-looking? "12a"))))

(deftest classifies-cells-coarsely
  (is (= :empty (scan/cell-shape "")))
  (is (= :empty (scan/cell-shape "   ")))
  (is (= :number (scan/cell-shape "42")))
  (is (= :number (scan/cell-shape "£12.50")))
  (is (= :date (scan/cell-shape "2026-08-03")))
  (is (= :date (scan/cell-shape "03/08/2026")))
  (is (= :word (scan/cell-shape "name")))
  (is (= :word (scan/cell-shape "first_name")))
  (is (= :other (scan/cell-shape "a@b.com"))))

(deftest spots-a-header-row
  (testing "words above numbers, the strongest single hint"
    (is (scan/looks-like-header? ["id" "name" "total"]
                                 [["1" "Ann" "12.50"] ["2" "Bob" "9.00"]])))
  (testing "a bare number in row one is near proof that it is data"
    (is (not (scan/looks-like-header? ["1" "Ann" "12.50"]
                                      [["2" "Bob" "9.00"] ["3" "Cy" "4.00"]]))))
  (testing "nothing to compare against yields no confident verdict"
    (is (not (scan/looks-like-header? ["id" "name"] [])))
    (is (not (scan/looks-like-header? [] [])))))

(deftest reports-how-sure-it-is
  (testing "three outcomes, because guessing wrong silently is worse than
            admitting a file is ambiguous"
    (is (= :header (:verdict (scan/header-evidence
                              ["id" "name" "total"]
                              [["1" "Ann" "12.50"] ["2" "Bob" "9.00"]]))))
    (is (= :data (:verdict (scan/header-evidence
                            ["1" "Ann" "12.50"]
                            [["2" "Bob" "9.00"] ["3" "Cy" "4.00"]]))))
    (testing "a column of words under a word gives no evidence either way"
      (is (= :unsure (:verdict (scan/header-evidence
                                ["name"] [["Ann"] ["Bob"] ["Cy"]])))))))

(deftest each-hint-can-be-inspected-on-its-own
  (testing "so a wrong answer can be diagnosed rather than guessed at"
    (let [s (scan/header-signals ["id" "name"] [["1" "Ann"] ["2" "Bob"]])]
      (is (pos? (:type-disagreement s)) "a word sits on a column of numbers")
      (is (pos? (:column-consistency s)) "the rows below agree with each other")
      (is (= 1.0 (:no-bare-numbers s)))
      (is (= 1.0 (:uniqueness s)))
      (is (= 1.0 (:no-empty-cells s)))
      (is (= 1.0 (:word-like s))))
    (testing "a repeated value in row one is not a column name"
      (is (zero? (:uniqueness (scan/header-signals ["a" "a"] [["1" "2"]])))))
    (testing "and a blank in row one counts against it"
      (is (= 0.5 (:no-empty-cells (scan/header-signals ["id" ""] [["1" "2"]])))))))

(deftest surveys-a-healthy-file
  (tu/with-temp-dir [dir]
    (let [f (tu/write-file dir "people.csv" "id,name\n1,Ann\n2,Bob\n")
          s (scan/survey f)]
      (is (= 3 (:records s)))
      (is (= 2 (:fields s)))
      (is (= \, (:delimiter s)))
      (is (:header-likely? s))
      (is (:healthy? s))
      (is (false? (:cancelled? s)))
      (is (= "UTF-8" (get-in s [:encoding :label])))
      (is (pos? (:bytes s))))))

(deftest counts-data-rows-either-way
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "a.csv" "id,name\n1,Ann\n2,Bob\n"))]
      (is (= 2 (scan/data-rows s true)))
      (is (= 3 (scan/data-rows s false)))))
  (testing "a file with only a header has no data"
    (tu/with-temp-dir [dir]
      (is (= 0 (scan/data-rows (scan/survey (tu/write-file dir "a.csv" "id,name\n")) true))))))

(deftest counts-ragged-rows
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "a.csv" "a,b,c\n1,2,3\n4,5\n6,7,8,9\n"))]
      (is (= 2 (get-in s [:damage :ragged])))
      (is (not (:healthy? s))))))

(deftest counts-quote-damage
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "a.csv" "a,b\n1,x\"y\n2,ok\n"))]
      (is (= 1 (get-in s [:damage :stray-quote])))
      (is (not (:healthy? s)))))
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "a.csv" "a,b\n1,\"never closed\n"))]
      (is (= 1 (get-in s [:damage :unterminated-quote]))))))

(deftest a-quoted-newline-is-one-row-not-two
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "a.csv" "id,notes\n1,\"one\ntwo\"\n2,plain\n"))]
      (is (= 3 (:records s)))
      (is (:healthy? s)))))

(deftest surveys-a-semicolon-file
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "a.csv" "id;name\n1;Ann\n2;Bob\n"))]
      (is (= \; (:delimiter s)))
      (is (= 2 (:fields s)))
      (is (:healthy? s)))))

(deftest surveys-an-empty-file
  (tu/with-temp-dir [dir]
    (let [s (scan/survey (tu/write-file dir "empty.csv" ""))]
      (is (= 0 (:records s)))
      (is (= 0 (:fields s)))
      (is (:healthy? s))
      (is (= 0 (scan/data-rows s true))))))

(deftest reports-progress-and-can-be-cancelled
  (tu/with-temp-dir [dir]
    (let [rows (apply str "id,name\n" (for [i (range (* 3 scan/check-interval))]
                                        (str i ",name" i "\n")))
          f    (tu/write-file dir "big.csv" rows)
          seen (atom [])
          s    (scan/survey f {:on-progress #(swap! seen conj %)})]
      (is (seq @seen) "progress must be reported on a file this size")
      (is (= (inc (* 3 scan/check-interval)) (:records s)))
      (let [stopped (scan/survey f {:cancelled? (constantly true)})]
        (is (:cancelled? stopped))
        (is (< (:records stopped) (:records s)))))))
