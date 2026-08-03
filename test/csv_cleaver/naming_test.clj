(ns csv-cleaver.naming-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.test-util :as tu]))

(deftest pads-wide-enough-to-sort
  (testing "the old fixed four digits stopped sorting past 9,999"
    (is (= 4 (naming/pad-width 1)))
    (is (= 4 (naming/pad-width 9999)))
    (is (= 5 (naming/pad-width 10000)))
    (is (= 6 (naming/pad-width 123456)))))

(deftest renders-a-name
  (is (= "sales_0001.csv"
         (naming/render "{name}_{index}" {:base "sales" :index 1 :width 4 :extension "csv"})))
  (is (= "sales_00042.csv"
         (naming/render "{name}_{index}" {:base "sales" :index 42 :width 5 :extension "csv"})))
  (is (= "part-0007-of-sales.csv"
         (naming/render "part-{index}-of-{name}"
                        {:base "sales" :index 7 :width 4 :extension "csv"})))
  (testing "the width defaults rather than throwing"
    (is (= "sales_0003.csv"
           (naming/render "{name}_{index}" {:base "sales" :index 3 :extension "csv"})))))

(deftest rejects-unusable-patterns
  (testing "a translation key rather than a sentence: this namespace has no
            idea what language the window is in"
    (is (nil? (naming/template-problem "{name}_{index}")))
    (is (nil? (naming/template-problem "{index}")))
    (is (= :problem/pattern-empty (naming/template-problem "")))
    (is (= :problem/pattern-empty (naming/template-problem "   ")))
    (testing "without an index every file would be given the same name"
      (is (= :problem/pattern-index (naming/template-problem "{name}"))))
    (testing "characters no file system will accept"
      (is (= :problem/pattern-chars (naming/template-problem "a/b{index}")))
      (is (= :problem/pattern-chars (naming/template-problem "{index}?"))))))

(deftest lists-every-output-name
  (is (= ["s_0001.csv" "s_0002.csv" "s_0003.csv"]
         (naming/output-names {:template "{name}_{index}" :base "s"
                               :extension "csv" :file-count 3})))
  (testing "a big split widens every index, not just the ones past 9,999"
    (let [names (naming/output-names {:template naming/default-template :base "s"
                                      :extension "csv" :file-count 12000})]
      (is (= "s_00001.csv" (first names)))
      (is (= "s_12000.csv" (last names)))))
  (testing "the template is optional"
    (is (= ["s_0001.csv"] (naming/output-names {:base "s" :extension "csv" :file-count 1})))))

(deftest matches-any-index-this-pattern-could-produce
  (let [p (naming/output-pattern {:template "{name}_{index}" :base "sales" :extension "csv"})]
    (is (re-matches p "sales_0001.csv"))
    (is (re-matches p "sales_999999.csv"))
    (is (re-matches p "SALES_0001.CSV") "file systems here are case insensitive")
    (is (not (re-matches p "sales_x.csv")))
    (is (not (re-matches p "other_0001.csv")))
    (is (not (re-matches p "sales_0001.txt"))))
  (testing "a base name containing regex characters is taken literally"
    (let [p (naming/output-pattern {:template "{name}_{index}"
                                    :base "a.b(c)" :extension "csv"})]
      (is (re-matches p "a.b(c)_0001.csv"))
      (is (not (re-matches p "axbxcx_0001.csv"))))))

(deftest finds-files-that-would-be-replaced
  (tu/with-temp-dir [dir]
    (tu/write-file dir "sales_0001.csv" "x")
    (tu/write-file dir "sales_0002.csv" "x")
    (tu/write-file dir "sales_notes.csv" "x")
    (tu/write-file dir "other_0001.csv" "x")
    (let [found (naming/existing-outputs dir {:template naming/default-template
                                              :base "sales" :extension "csv"})]
      (is (= ["sales_0001.csv" "sales_0002.csv"] (tu/names found))))))

(deftest finds-leftovers-from-a-larger-earlier-split
  (testing "files 20 to 30 from a previous run would otherwise sit in the
            folder looking like part of the new results"
    (tu/with-temp-dir [dir]
      (doseq [i (range 1 31)]
        (tu/write-file dir (format "sales_%04d.csv" i) "x"))
      (is (= 30 (count (naming/existing-outputs dir {:template naming/default-template
                                                     :base "sales" :extension "csv"})))))))

(deftest an-empty-folder-has-no-collisions
  (tu/with-temp-dir [dir]
    (is (= [] (naming/existing-outputs dir {:template naming/default-template
                                            :base "sales" :extension "csv"})))))

(deftest a-missing-folder-has-no-collisions
  (tu/with-temp-dir [dir]
    (is (= [] (naming/existing-outputs (io/file dir "not-yet")
                                       {:template naming/default-template
                                        :base "sales" :extension "csv"})))))

(deftest directories-are-not-collisions
  (tu/with-temp-dir [dir]
    (.mkdirs (io/file dir "sales_0001.csv"))
    (is (= [] (naming/existing-outputs dir {:template naming/default-template
                                            :base "sales" :extension "csv"})))))
