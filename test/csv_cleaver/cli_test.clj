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

(deftest only-the-shape-of-a-language-code-is-checked-here
  (testing "whether we have that language cannot be known until the user's own
            translation files have been read, which happens after parsing"
    (is (= "it" (get-in (cli/parse ["--locale" "it"]) [:options :locale]))
        "a language not shipped is accepted; startup decides")
    (let [{:keys [action status message]} (cli/parse ["--locale" "klingon"])]
      (is (= :exit action) "but nonsense is still refused")
      (is (= 1 status))
      (is (re-find #"language code" message)))))

(deftest a-folder-of-extra-translations-can-be-named
  (is (= "/tmp/langs" (get-in (cli/parse ["--languages" "/tmp/langs"]) [:options :languages])))
  (is (= "/tmp/langs" (get-in (cli/parse ["-L" "/tmp/langs"]) [:options :languages]))))

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

;; ── The optional local service ──────────────────────────────────────────────

(deftest the-service-is-off-unless-asked-for
  (testing "a desktop application that quietly listens on a port is not what
            anyone installed"
    (let [{:keys [action options]} (cli/parse [])]
      (is (= :run action))
      (is (not (:api options)))
      (is (not (:headless options))))))

(deftest the-service-options-have-workable-defaults
  (let [{:keys [options]} (cli/parse ["--api"])]
    (is (true? (:api options)))
    (is (= 8377 (:api-port options)))
    (is (= :path (:api-input options)))
    (is (nil? (:api-token options))
        "none given means one is generated, which is the safer default")))

(deftest an-input-mode-is-one-of-four-words
  (doseq [word ["none" "path" "upload" "both"]]
    (let [{:keys [action options]} (cli/parse ["--api" "--api-input" word])]
      (is (= :run action) word)
      (is (= (keyword word) (:api-input options)))))
  (doseq [word ["NONE" "Both"]]
    (is (= (keyword (str/lower-case word))
           (:api-input (:options (cli/parse ["--api" "--api-input" word]))))
        "the case someone types it in is not a reason to refuse it"))
  (let [{:keys [action status message]} (cli/parse ["--api" "--api-input" "maybe"])]
    (is (= :exit action))
    (is (= 1 status))
    (is (str/includes? message "none, path, upload, both"))))

(deftest a-port-outside-the-unprivileged-range-is-refused
  (doseq [port ["80" "0" "70000" "-1"]]
    (let [{:keys [action status]} (cli/parse ["--api" "--api-port" port])]
      (is (= :exit action) port)
      (is (= 1 status) port)))
  (is (= 9000 (:api-port (:options (cli/parse ["--api" "--api-port" "9000"]))))))

(deftest headless-without-the-service-is-refused-rather-than-obeyed
  (testing "it would start nothing at all and sit there, which is the least
            useful thing the application could do"
    (let [{:keys [action status message]} (cli/parse ["--headless"])]
      (is (= :exit action))
      (is (= 1 status))
      (is (str/includes? message "--api")))))

(deftest headless-with-the-service-is-allowed
  (let [{:keys [action options]} (cli/parse ["--api" "--headless"])]
    (is (= :run action))
    (is (true? (:headless options)))))

(deftest help-explains-the-service-options-including-what-they-cost
  (let [{:keys [message]} (cli/parse ["--help"])]
    (is (str/includes? message "--api"))
    (is (str/includes? message "--api-input"))
    (is (str/includes? message "--headless"))
    (is (re-find #"(?s)any file you can read" message)
        "the help says plainly what --api-input path allows")))
