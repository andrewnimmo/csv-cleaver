(ns csv-cleaver.prefs-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.prefs :as prefs]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File)))

(deftest saves-and-restores-settings
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "settings.edn")]
      (is (true? (prefs/save-prefs! file {:theme :dark :rows-text "100"})))
      (let [loaded (prefs/load-prefs file)]
        (is (= :dark (:theme loaded)))
        (is (= "100" (:rows-text loaded)))))))

(deftest a-folder-comes-back-as-a-file-not-a-string
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "settings.edn")]
      (prefs/save-prefs! file {:output-base dir})
      (is (instance? File (:output-base (prefs/load-prefs file))))
      (is (= (.getAbsolutePath dir)
             (.getAbsolutePath ^File (:output-base (prefs/load-prefs file))))))))

(deftest saving-merges-rather-than-replaces
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "settings.edn")]
      (prefs/save-prefs! file {:theme :dark})
      (prefs/save-prefs! file {:rows-text "7"})
      (let [loaded (prefs/load-prefs file)]
        (is (= :dark (:theme loaded)) "the earlier setting survives")
        (is (= "7" (:rows-text loaded)))))))

(deftest only-remembered-keys-are-kept
  (testing "nothing about a particular file is stored, so the window always
            opens ready for a new one"
    (tu/with-temp-dir [dir]
      (let [file (io/file dir "settings.edn")]
        (prefs/save-prefs! file {:theme :light :file "/secret/path.csv" :survey {:big :map}})
        (let [saved (slurp file)]
          (is (not (re-find #"secret" saved)))
          (is (not (re-find #"survey" saved))))))))

(deftest missing-settings-are-not-an-error
  (tu/with-temp-dir [dir]
    (is (= {} (prefs/load-prefs (io/file dir "never-written.edn"))))))

(deftest unreadable-settings-fall-back-to-defaults
  (testing "a corrupt file must never stop the application from starting"
    (tu/with-temp-dir [dir]
      (let [file (io/file dir "settings.edn")]
        (spit file "{{{ not edn at all")
        (is (= {} (prefs/load-prefs file)))
        (testing "and can still be written over"
          (is (true? (prefs/save-prefs! file {:theme :dark})))
          (is (= :dark (:theme (prefs/load-prefs file)))))))))

(deftest settings-that-are-not-a-map-are-ignored
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "settings.edn")]
      (spit file "[1 2 3]")
      (is (= {} (prefs/load-prefs file))))))

(deftest saving-somewhere-impossible-fails-quietly
  (is (false? (prefs/save-prefs! (File. "/proc/nope/settings.edn") {:theme :dark}))))

(deftest creates-the-folder-it-needs
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "nested" "deeper" "settings.edn")]
      (is (true? (prefs/save-prefs! file {:theme :dark})))
      (is (.isFile file)))))
