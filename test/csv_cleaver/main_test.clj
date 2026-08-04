(ns csv-cleaver.main-test
  "The entry point. Small, and with one property worth defending in a test."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.main :as main]))

(defn- load-time-dependencies
  "What a namespace pulls in as it loads: its :require and :import clauses,
   as one string. The docstring is left out — it is allowed to discuss cljfx,
   and indeed it explains why none of this may reach it."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource path)))]
    (->> (read r)
         (filter #(and (seq? %) (#{:require :import :use} (first %))))
         (pr-str))))

(deftest the-entry-point-can-be-loaded-without-a-display
  (testing "loading cljfx starts the JavaFX toolkit as a side effect of loading
            it. If this namespace reached cljfx at load time, --headless would
            fail on a machine with no display before anything had the chance to
            say why — and it would fail during class loading, which is the
            hardest kind of failure to explain to someone.

            Checked by reading the source rather than by watching threads: by
            the time this test runs, other tests have started the toolkit
            already, so there would be nothing left to observe."
    (let [form (load-time-dependencies "csv_cleaver/main.clj")]
      (is (not (str/includes? form "cljfx"))
          "csv-cleaver.main must not require cljfx, directly or by alias")
      (is (not (str/includes? form "csv-cleaver.view"))
          "nor anything that does")
      (is (not (str/includes? form "csv-cleaver.app"))
          "the window namespace is reached through requiring-resolve, at the
           moment a window is going to open")
      (is (not (str/includes? form "javafx"))
          "and no JavaFX class is imported"))))

(deftest the-window-namespace-is-reachable-when-it-is-needed
  (testing "requiring-resolve is only safe if the names are right, and a typo
            would not show up until someone ran the application"
    (is (some? (requiring-resolve 'csv-cleaver.app/start-window!)))
    (is (some? (requiring-resolve 'csv-cleaver.app/show-language-problems!)))))

(deftest a-short-token-is-warned-about-but-not-refused
  (testing "it is the user's machine and their decision, but they should be
            making it knowingly"
    (is (some? (main/weak-token-warning "short")))
    (is (str/includes? (main/weak-token-warning "short") "5 characters"))
    (is (nil? (main/weak-token-warning
               (apply str (repeat main/minimum-token-length "x"))))
        "exactly at the threshold is enough")
    (is (nil? (main/weak-token-warning nil))
        "no token given means one is generated, which is nothing to warn about")))

;; ── Starting the service ────────────────────────────────────────────────────

(deftest the-service-is-started-and-reported
  (let [output (java.io.StringWriter.)
        service (binding [*out* output] (main/start-api! {:api-port 0
                                                          :api-input :none
                                                          :api-token "a-token-long-enough"}))]
    (try
      (is (pos? (:port service)) "a port of 0 means one was chosen for us")
      (is (= "a-token-long-enough" (:token service)))
      (let [said (str output)]
        (is (str/includes? said "127.0.0.1") "the banner says where it is")
        (is (str/includes? said "a-token-long-enough") "and what the token is"))
      (finally ((:stop! service))))))

(deftest a-port-already-in-use-is-explained-rather-than-a-stack-trace
  (let [service (binding [*out* (java.io.StringWriter.)]
                  (main/start-api! {:api-port 0 :api-input :none
                                    :api-token "a-token-long-enough"}))
        exits   (atom [])
        errors  (java.io.StringWriter.)]
    (try
      (with-redefs [main/exit! (fn [status] (swap! exits conj status))]
        (binding [*err* errors *out* (java.io.StringWriter.)]
          (main/start-api! {:api-port (:port service) :api-input :none
                            :api-token "a-token-long-enough"})))
      (is (= [1] @exits) "it stops rather than opening a window without the service")
      (is (str/includes? (str errors) "already in use"))
      (is (str/includes? (str errors) "--api-port") "and says how to fix it")
      (finally ((:stop! service))))))

(deftest the-path-that-warns-about-a-token-is-the-path-that-starts-the-service
  (let [errors (java.io.StringWriter.)
        service (binding [*err* errors *out* (java.io.StringWriter.)]
                  (main/start-api! {:api-port 0 :api-input :none :api-token "tiny"}))]
    (try
      (is (str/includes? (str errors) "Anything holding it can drive"))
      (is (some? service) "warned about, not refused: it is the user's machine")
      (finally ((:stop! service))))))

;; ── What -main decides ──────────────────────────────────────────────────────

(defn- run-main
  "Drive -main with the window, the service and exiting all replaced by
   recorders, so every branch can be taken without opening anything."
  [args]
  (let [record (atom {:exits [] :parked false :api nil})]
    (with-redefs [main/exit!      (fn [status] (swap! record update :exits conj status))
                  main/start-api! (fn [options] (swap! record assoc :api options) nil)
                  main/park!      (fn [] (swap! record assoc :parked true))]
      (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
        (apply main/-main args)))
    @record))

(deftest help-and-version-print-and-stop
  (is (= [0] (:exits (run-main ["--help"]))))
  (is (= [0] (:exits (run-main ["--version"]))))
  (is (= [1] (:exits (run-main ["--nonsense"])))))

(deftest headless-starts-the-service-and-opens-nothing
  (let [record (run-main ["--api" "--headless" "--languages" "/nonexistent-languages"])]
    (is (some? (:api record)) "the service was started")
    (is (true? (:parked record)) "and nothing else was")
    (is (empty? (:exits record)))))

(deftest the-service-can-be-asked-for-alongside-the-window
  (testing "the two are independent: --api does not imply --headless, and the
            window opens as usual with the service running beside it"
    (let [record (atom {})
          opened (requiring-resolve (quote csv-cleaver.app/start-window!))]
      (with-redefs-fn {#'main/exit!      (fn [_] nil)
                       #'main/start-api! (fn [options] (swap! record assoc :api options) nil)
                       #'main/park!      (fn [] (swap! record assoc :parked true))
                       opened            (fn [options] (swap! record assoc :window options))}
        (fn []
          (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
            (main/-main "--api" "--languages" "/nonexistent-languages"))))
      (is (some? (:api @record)) "the service was started")
      (is (some? (:window @record)) "and so was the window")
      (is (nil? (:parked @record)) "and it did not park, because there is a window"))))

(deftest without-the-service-only-the-window-opens
  (let [record (atom {})
        opened (requiring-resolve (quote csv-cleaver.app/start-window!))]
    (with-redefs-fn {#'main/exit!      (fn [_] nil)
                     #'main/start-api! (fn [_] (is false "no --api was given") nil)
                     #'main/park!      (fn [] (is false "no --headless was given"))
                     opened            (fn [options] (swap! record assoc :window options))}
      (fn []
        (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
          (main/-main "--languages" "/nonexistent-languages"))))
    (is (some? (:window @record)))))

(deftest a-rejected-translation-headless-is-reported-and-ignored
  (testing "there is no window to explain it in, and the service answers in
            English anyway. Refusing to start would be the worse failure."
    (let [dir (java.io.File/createTempFile "langs" "")]
      (.delete dir)
      (.mkdirs dir)
      (spit (java.io.File. dir "xx.edn") "{:strings {:action/close #=(println 1)}}")
      (let [errors (java.io.StringWriter.)
            record (atom {})]
        (with-redefs [main/exit!      (fn [status] (swap! record assoc :exit status))
                      main/start-api! (fn [_] nil)
                      main/park!      (fn [] (swap! record assoc :parked true))]
          (binding [*err* errors *out* (java.io.StringWriter.)]
            (main/-main "--api" "--headless" "--languages" (.getAbsolutePath dir))))
        (is (str/includes? (str errors) "Ignored"))
        (is (true? (:parked @record)) "and it carried on")
        (is (nil? (:exit @record)))))))
