(ns csv-cleaver.samples-test
  "Checks the whole application against a folder of real CSV files, each one
   written to exhibit a particular problem, or none.

   The files in test/resources/samples are small enough to open and read, so a
   failure here can be understood by looking at the file rather than by
   deciphering a string literal buried in a test. manifest.edn records what each
   one should survey as, and every file must appear in it."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.split :as split]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File)
   (java.nio.file Files)))

(def samples-dir (io/file "test/resources/samples"))

(def manifest (edn/read-string (slurp (io/file samples-dir "manifest.edn"))))

(defn sample ^File [filename] (io/file samples-dir filename))

(defn sample-files []
  (->> (.listFiles samples-dir)
       (filter (fn [^File f] (str/ends-with? (.getName f) ".csv")))
       (sort-by (fn [^File f] (.getName f)))))

(deftest every-sample-is-described
  (testing "a sample nobody wrote an expectation for is a sample nobody checks"
    (let [on-disk    (set (map (fn [^File f] (.getName f)) (sample-files)))
          in-manifest (set (keys manifest))]
      (is (empty? (set/difference on-disk in-manifest))
          (str "undescribed samples: " (sort (set/difference on-disk in-manifest))))
      (is (empty? (set/difference in-manifest on-disk))
          (str "described but missing: " (sort (set/difference in-manifest on-disk)))))))

(deftest every-sample-surveys-as-described
  (doseq [[filename expected] (sort manifest)]
    (testing (str filename " — " (str/replace (:note expected) #"\s+" " "))
      (let [s (scan/survey (sample filename))]
        (is (= (:records expected) (:records s)) "records")
        (is (= (:fields expected) (:fields s)) "fields")
        (is (= (:delimiter expected) (:delimiter s)) "delimiter")
        (is (= (:header-likely? expected) (:header-likely? s)) "header detected")
        (is (= (:header-verdict expected) (get-in s [:header :verdict])) "header verdict")
        (is (= (:healthy? expected) (:healthy? s)) "healthy")
        (is (= (get-in expected [:encoding :label]) (get-in s [:encoding :label])) "encoding")
        (is (= (get-in expected [:encoding :basis]) (get-in s [:encoding :basis])) "basis")
        (when-let [bom (get-in expected [:encoding :bom-length])]
          (is (= bom (get-in s [:encoding :bom-length])) "byte-order mark length"))
        (when-let [damage (:damage expected)]
          (is (= damage (:damage s)) "damage counts"))))))

(deftest every-sample-splits-and-rejoins-byte-for-byte
  (testing "whatever is wrong with a file, splitting it and concatenating the
            pieces must give back exactly what went in"
    (doseq [[filename expected] (sort manifest)
            :when (pos? (long (:records expected)))]
      (testing filename
        (tu/with-temp-dir [out]
          (let [source (sample filename)
                s      (scan/survey source)
                plan   (split/plan {:survey s :mode :rows :value 1 :has-header? false})
                result (split/execute! {:survey s :out-dir out :mode :rows :value 1
                                        :has-header? false :include-header? false
                                        :plan plan})
                original (vec (Files/readAllBytes (.toPath source)))
                bom      (count (get-in s [:encoding :bom-bytes]))
                rejoined (vec (mapcat (fn [^File f]
                                        ;; Each piece carries the byte-order
                                        ;; mark the source had, so all but the
                                        ;; first must have it stripped before
                                        ;; comparing.
                                        (drop bom (vec (Files/readAllBytes (.toPath f)))))
                                      (:files result)))]
            (is (= (count original) (+ bom (count rejoined))) "byte count")
            (is (= (drop bom original) (seq rejoined)) "byte for byte")))))))

(deftest a-file-with-no-data-cannot-be-split
  (doseq [filename ["empty.csv" "header-only.csv"]]
    (testing filename
      (let [s (scan/survey (sample filename))]
        (is (= :problem/no-data
               (:problem (split/plan {:survey s :mode :rows :value 10 :has-header? true}))))))))

(deftest a-quoted-newline-never-lands-in-two-files
  (testing "the single most important guarantee this application makes"
    (tu/with-temp-dir [out]
      (let [s      (scan/survey (sample "quoted-newlines.csv"))
            result (split/execute! {:survey s :out-dir out :mode :rows :value 1
                                    :has-header? true :include-header? true
                                    :plan (split/plan {:survey s :mode :rows :value 1
                                                       :has-header? true})})]
        (is (= 3 (count (:files result))) "three data rows, three files")
        (doseq [^File f (:files result)]
          (let [text (tu/read-file f)]
            (is (even? (count (filter #{\"} text)))
                (str (.getName f) " has an unbalanced quote, so a record was torn"))))))))
