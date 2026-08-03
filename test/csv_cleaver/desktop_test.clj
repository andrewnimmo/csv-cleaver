(ns csv-cleaver.desktop-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.desktop :as desktop]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File)))

(deftest recognises-each-operating-system
  (is (= :mac (desktop/os "Mac OS X")))
  (is (= :mac (desktop/os "macOS")))
  (is (= :windows (desktop/os "Windows 11")))
  (is (= :linux (desktop/os "Linux")))
  (is (= :linux (desktop/os "FreeBSD")) "anything unfamiliar behaves like Linux")
  (is (keyword? (desktop/os))))

(deftest uses-each-platforms-own-word-for-it
  (testing "a key rather than text, because each platform's word for it also
            has to be translated"
    (is (= :action/reveal-mac (desktop/reveal-label-key :mac)))
    (is (= :action/reveal-windows (desktop/reveal-label-key :windows)))
    (is (= :action/reveal-other (desktop/reveal-label-key :linux)))
    (is (keyword? (desktop/reveal-label-key)))))

(deftest builds-the-right-command-per-platform
  (let [dir (File. "/tmp/x")]
    (is (= ["open" "/tmp/x"] (desktop/reveal-command :mac dir)))
    (is (= ["explorer" "/tmp/x"] (desktop/reveal-command :windows dir)))
    (is (= ["xdg-open" "/tmp/x"] (desktop/reveal-command :linux dir)))
    (is (nil? (desktop/reveal-command :unknown dir)))))

(deftest revealing-nothing-does-nothing
  (is (nil? (desktop/reveal! nil)))
  (is (nil? (desktop/reveal! (File. "/no/such/folder")))
      "a missing folder is not worth an error dialog"))

(deftest revealing-runs-the-platform-command
  (tu/with-temp-dir [dir]
    (let [ran (atom nil)]
      (is (true? (desktop/reveal! dir :mac (fn [cmd _] (reset! ran cmd) true))))
      (is (= ["open" (.getAbsolutePath dir)] @ran)))))

(deftest revealing-falls-back-when-there-is-no-command
  (tu/with-temp-dir [dir]
    (let [ran (atom :not-called)]
      (is (true? (desktop/reveal! dir :unknown (fn [cmd _] (reset! ran cmd) true))))
      (is (nil? @ran) "no command means java.awt.Desktop is asked instead"))))

(deftest a-file-manager-that-will-not-start-is-not-an-error
  (testing "the completion panel already says where the files are, so a failure
            to open the folder is not worth interrupting anyone over"
    (tu/with-temp-dir [dir]
      (is (false? (desktop/reveal! dir :mac (fn [_ _] (throw (RuntimeException. "no")))))))))

(deftest settings-live-where-each-platform-expects
  (testing "macOS"
    (is (= "/Users/x/Library/Application Support/CSV Cleaver/settings.edn"
           (.getPath ^File (desktop/prefs-file :mac "/Users/x")))))
  (testing "Linux honours XDG when set, and falls back to ~/.config"
    (is (re-find #"csv-cleaver/settings\.edn"
                 (.getPath ^File (desktop/prefs-file :linux "/home/x")))))
  (testing "Windows"
    (is (re-find #"CSV Cleaver"
                 (.getPath ^File (desktop/prefs-file :windows "C:\\Users\\x")))))
  (is (instance? File (desktop/prefs-file))))
