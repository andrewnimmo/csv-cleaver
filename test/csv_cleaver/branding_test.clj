(ns csv-cleaver.branding-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util Base64)))

(deftest reads-the-shipped-branding
  (is (= "CSV Cleaver" (branding/app-name)))
  (is (re-matches #"\d+\.\d+\.\d+" (branding/version)))
  (is (= "dev.nimmo.csvcleaver" (branding/value :bundle-id))))

(deftest rebranding-needs-no-code-change
  (tu/with-temp-dir [dir]
    (let [file (io/file dir "branding.edn")]
      (spit file (pr-str {:name "Acme Splitter" :version "9.9.9" :accent "#c2410c"}))
      (let [config (branding/read-config file)]
        (is (= "Acme Splitter" (:name config)))
        (is (= "9.9.9" (:version config)))
        (testing "and anything left out keeps its default"
          (is (= "dev.nimmo.csvcleaver" (:bundle-id config))))))))

(deftest a-broken-branding-file-does-not-stop-the-application
  (testing "a wrong-looking window beats one that will not open"
    (tu/with-temp-dir [dir]
      (let [file (io/file dir "branding.edn")]
        (spit file "{{{ not edn")
        (is (= "CSV Cleaver" (:name (branding/read-config file)))))
      (is (= "CSV Cleaver" (:name (branding/read-config nil))))
      (testing "and neither does one holding the wrong kind of thing"
        (let [file (io/file dir "list.edn")]
          (spit file "[1 2 3]")
          (is (= "CSV Cleaver" (:name (branding/read-config file)))))))))

(deftest an-accent-colour-becomes-a-stylesheet
  (is (nil? (branding/accent-css nil)))
  (is (nil? (branding/accent-css "")))
  (let [uri (branding/accent-css "#c2410c")]
    (is (str/starts-with? uri "data:text/css;base64,"))
    (let [css (String. (.decode (Base64/getDecoder)
                                (subs uri (count "data:text/css;base64,")))
                       StandardCharsets/UTF_8)]
      (is (str/includes? css "#c2410c"))
      (is (str/includes? css "-color-accent-emphasis")))))

(deftest the-window-loads-its-stylesheets-in-order
  (let [sheets (branding/stylesheets)]
    (is (seq sheets))
    (is (some #(str/includes? % "app.css") sheets))
    (testing "every entry is something JavaFX can actually load"
      (is (every? #(or (str/starts-with? % "data:") (str/starts-with? % "file:")
                       (str/starts-with? % "jar:"))
                  sheets)))))

(deftest a-branding-file-is-read-defensively
  (testing "the file is data someone edits by hand; anything wrong with it
            means the defaults, never a crash at startup"
    (tu/with-temp-dir [dir]
      (let [good (io/file dir "good.edn")
            bad  (io/file dir "bad.edn")
            vect (io/file dir "list.edn")]
        (spit good "{:name \"Acme Splitter\"}")
        (spit bad  "{:name")
        (spit vect "[1 2 3]")
        (is (= "Acme Splitter" (:name (branding/read-config (.toURL good))))
            "a good file wins over the default")
        (is (string? (:name (branding/read-config (.toURL bad))))
            "an unfinished one falls back")
        (is (string? (:name (branding/read-config (.toURL vect))))
            "EDN that is not a map falls back")
        (is (string? (:name (branding/read-config nil)))
            "and no file at all is the plainest fallback")))))
