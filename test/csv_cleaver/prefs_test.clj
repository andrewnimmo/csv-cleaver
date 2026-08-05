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
      (is (true? (prefs/save-prefs! file {:theme :dark :rows 100})))
      (let [loaded (prefs/load-prefs file)]
        (is (= :dark (:theme loaded)))
        (is (= 100 (:rows loaded)))))))

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
      (prefs/save-prefs! file {:rows 7})
      (let [loaded (prefs/load-prefs file)]
        (is (= :dark (:theme loaded)) "the earlier setting survives")
        (is (= 7 (:rows loaded)))))))

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
  ;; A path under an existing regular file: impossible on every OS, unlike
  ;; "/proc/…", which a Windows runner happily creates as D:\proc\….
  (tu/with-temp-dir [dir]
    (let [wall (io/file dir "wall.txt")]
      (spit wall "x")
      (is (false? (prefs/save-prefs! (io/file wall "nope" "settings.edn")
                                     {:theme :dark}))))))

(deftest creates-the-folder-it-needs
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "nested" "deeper" "settings.edn")]
      (is (true? (prefs/save-prefs! file {:theme :dark})))
      (is (.isFile file)))))

(deftest settings-from-an-older-version-are-dropped-not-guessed-at
  (testing "R81. :rows-text and :size-text held a number formatted for whatever
            language the window was in, and the file does not reliably say
            which. They are ignored rather than read as the wrong language."
    (is (empty? (filter prefs/abandoned prefs/remembered))
        "a key cannot be both remembered and abandoned")
    (tu/with-temp-dir [dir]
      (let [file (io/file dir "settings.edn")]
        (spit file (pr-str {:language "es" :rows-text "100,000"
                            :size-text "100 MB" :theme :dark}))
        (let [loaded (prefs/load-prefs file)]
          (is (= :dark (:theme loaded)) "what is unambiguous still comes back")
          (is (= "es" (:language loaded)))
          (is (not-any? loaded prefs/abandoned)))))))

(deftest the-default-location-is-the-platforms-own
  (testing "the zero-argument arities go through desktop/prefs-file, so a test
            can point them somewhere harmless and the application cannot write
            into the real settings of whoever runs the suite"
    (tu/with-temp-dir [dir]
      (let [file (io/file dir "settings.edn")]
        (with-redefs [csv-cleaver.desktop/prefs-file (fn [] file)]
          (is (true? (prefs/save-prefs! {:theme :dark})))
          (is (= :dark (:theme (prefs/load-prefs))))
          (is (.isFile file) "and it really went where redirected"))))))
