(ns csv-cleaver.cli-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.cli :as cli]))

(deftest no-arguments-just-runs
  (let [{:keys [action options]} (cli/parse [])]
    (is (= :run action))
    (is (nil? (:locale options)))
    (is (nil? (:theme options)))))

(deftest a-language-can-be-forced
  (testing "the point of this option: seeing the window in Japanese without
            changing the machine's own language"
    (is (= "ja" (get-in (cli/parse ["--locale" "ja"]) [:options :locale])))
    (is (= "ja" (get-in (cli/parse ["--locale=ja"]) [:options :locale])))
    (is (= "ja" (get-in (cli/parse ["-l" "ja"]) [:options :locale])))))

(deftest an-unknown-language-is-refused-with-the-list
  (let [{:keys [action status message]} (cli/parse ["--locale" "klingon"])]
    (is (= :exit action))
    (is (= 1 status))
    (is (re-find #"Unknown language" message))
    (is (re-find #"en, es, fr, de, zh, ja" message))))

(deftest the-theme-can-be-forced
  (is (= :dark (get-in (cli/parse ["--theme" "dark"]) [:options :theme])))
  (is (= :light (get-in (cli/parse ["-t" "light"]) [:options :theme])))
  (is (= :system (get-in (cli/parse ["--theme" "system"]) [:options :theme])))
  (testing "auto is the word people reach for, and means the same as system"
    (is (= :system (get-in (cli/parse ["--theme" "auto"]) [:options :theme]))))
  (testing "and case does not matter"
    (is (= :dark (get-in (cli/parse ["--theme" "DARK"]) [:options :theme])))))

(deftest an-unknown-theme-is-refused
  (let [{:keys [action status message]} (cli/parse ["--theme" "neon"])]
    (is (= :exit action))
    (is (= 1 status))
    (is (re-find #"auto, light, dark" message))))

(deftest help-explains-every-option
  (let [{:keys [action status message]} (cli/parse ["--help"])]
    (is (= :exit action))
    (is (zero? status))
    (is (re-find #"--locale" message))
    (is (re-find #"--theme" message))
    (is (re-find #"--version" message))
    (is (re-find #"Usage:" message))))

(deftest version-is-reported
  (let [{:keys [action status message]} (cli/parse ["-V"])]
    (is (= :exit action))
    (is (zero? status))
    (is (re-find #"\d+\.\d+\.\d+" message))))

(deftest an-unknown-option-is-refused-rather-than-ignored
  (let [{:keys [action status message]} (cli/parse ["--nonsense"])]
    (is (= :exit action))
    (is (= 1 status))
    (is (str/includes? message "Unknown option"))))

(deftest parsing-never-exits-by-itself
  (testing "so it can be tested, and so a caller decides what to do"
    (is (map? (cli/parse ["--help"])))
    (is (map? (cli/parse ["--nonsense"])))))
