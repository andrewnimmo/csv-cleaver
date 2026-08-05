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
  ;; The expected path is the File's own absolute form, not a POSIX literal:
  ;; on Windows java.io.File renders "/tmp/x" as "D:\tmp\x", and the first
  ;; CI run on a Windows machine failed exactly here. What matters is which
  ;; program is named and that the path is passed through verbatim.
  (let [dir  (File. "/tmp/x")
        path (.getAbsolutePath dir)]
    (is (= ["open" path] (desktop/reveal-command :mac dir)))
    (is (= ["explorer" path] (desktop/reveal-command :windows dir)))
    (is (= ["xdg-open" path] (desktop/reveal-command :linux dir)))
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
    ;; File equality, not a string literal: both sides then wear whatever
    ;; separators the host OS uses.
    (is (= (java.io.File. "/Users/x/Library/Application Support/CSV Cleaver/settings.edn")
           (desktop/prefs-file :mac "/Users/x"))))
  (testing "Linux honours XDG when set, and falls back to ~/.config"
    (is (re-find #"csv-cleaver[/\\]settings\.edn"
                 (.getPath ^File (desktop/prefs-file :linux "/home/x")))))
  (testing "Windows"
    (is (re-find #"CSV Cleaver"
                 (.getPath ^File (desktop/prefs-file :windows "C:\\Users\\x")))))
  (is (instance? File (desktop/prefs-file))))

(deftest the-languages-folder-sits-beside-the-settings
  (testing "one place to look, whatever the platform: it.edn goes next to
            settings.edn, and the path derives from prefs-file rather than
            being spelled out twice"
    (doseq [os [:mac :windows :linux]]
      (is (= (.getParentFile (desktop/prefs-file os "/home/x"))
             (.getParentFile (desktop/languages-dir os "/home/x")))
          (str os))
      (is (= "languages" (.getName (desktop/languages-dir os "/home/x")))))))

(deftest nothing-goes-to-the-trash-that-should-not
  (testing "the guards short-circuit before java.awt.Desktop is ever asked, so
            these are safe to run anywhere and the answer must be a plain false
            — not nil, not an exception"
    (is (false? (desktop/move-to-trash! nil)))
    (is (false? (desktop/move-to-trash! (java.io.File. "/no/such/file/anywhere.csv"))))))

(deftest trash-support-is-a-boolean-not-an-exception
  (testing "some Linux desktops cannot trash at all; asking must never throw"
    (is (contains? #{true false} (desktop/trash-supported?)))))

(deftest a-mail-uri-carries-the-address-and-an-encoded-subject
  (testing "the subject includes the version, which is what a support message
            needs and nobody types correctly. Expected form written from RFC
            6068 — spaces and parentheses percent-encoded — not from running
            the code."
    (let [uri (desktop/mail-uri "contact+csv.cleaver@nimmo.dev"
                                "CSV Cleaver 2.0.0 (a1b2c3d)")]
      (is (= "mailto" (.getScheme uri)))
      (is (= "mailto:contact+csv.cleaver@nimmo.dev?subject=CSV%20Cleaver%202.0.0%20(a1b2c3d)"
             (.toASCIIString uri))))))

(deftest composing-mail-is-injectable-and-gives-up-quietly
  (let [sent (atom nil)]
    (is (true? (desktop/compose-mail! (desktop/mail-uri "a@b.c" "s")
                                      (fn [u] (reset! sent u)))))
    (is (= "mailto" (.getScheme ^java.net.URI @sent)))
    (is (false? (desktop/compose-mail! nil (fn [_] (throw (Exception. "no")))))
        "nil goes nowhere")
    (is (false? (desktop/compose-mail! (desktop/mail-uri "a@b.c" "s")
                                       (fn [_] (throw (Exception. "no client")))))
        "a mail client that will not open is not an error worth a dialog")))
