(ns csv-cleaver.app-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [csv-cleaver.app :as app]
   [csv-cleaver.prefs :as prefs]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.state :as state]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File IOException)))

(use-fixtures :each
  (fn [run]
    (reset! app/*state state/initial)
    (reset! app/cancel-flag false)
    (run)
    (reset! app/*state state/initial)))

(deftest widget-values-arrive-under-the-key-the-state-expects
  (is (= {:event/type ::x :event/value-key :text :fx/event "65,000" :text "65,000"}
         (app/normalise {:event/type ::x :event/value-key :text :fx/event "65,000"})))
  (testing "an event that names no key is passed through untouched"
    (is (= {:event/type ::x} (app/normalise {:event/type ::x})))))

(deftest exceptions-become-something-a-person-can-act-on
  (testing "a translation key, so the failure is reported in the user's own
            language rather than the operating system's"
    (is (= {:key :problem/no-permission}
           (app/friendly-message (IOException. "Permission denied"))))
    (is (= {:key :problem/no-permission}
           (app/friendly-message (IOException. "Access is denied"))))
    (is (= {:key :problem/disk-full}
           (app/friendly-message (IOException. "No space left on device"))))
    (is (= {:key :problem/file-moved}
           (app/friendly-message (IOException. "No such file or directory")))))
  (testing "a failure we already described keeps its own wording"
    (is (= {:key :problem/folder-create :args ["/nope"]}
           (app/friendly-message
            (ex-info "x" {:message {:key :problem/folder-create :args ["/nope"]}})))))
  (testing "anything else keeps the system's own words rather than inventing any"
    (is (= {:text "Something specific went wrong."}
           (app/friendly-message (Exception. "Something specific went wrong.")))))
  (testing "and an exception with nothing to say still says something"
    (is (= {:key :problem/generic} (app/friendly-message (Exception.))))))

(deftest a-new-folder-is-named-after-the-file-and-the-time
  (tu/with-temp-dir [dir]
    (let [made (app/timestamped-dir dir (File. "sales.csv"))]
      (is (re-find #"^sales split \d{4}-\d{2}-\d{2} \d{4}$" (.getName made)))
      (is (= (.getAbsolutePath dir) (.getAbsolutePath (.getParentFile made)))))))

(deftest events-move-the-application-along
  (tu/with-temp-dir [dir]
    (let [file (tu/write-file dir "a.csv" "id\n1\n2\n")]
      (app/handle-event {:event/type ::state/file-chosen :file file})
      (is (= :scanning (:phase @app/*state)))
      (is (= file (:file @app/*state))))))

(deftest a-widget-value-reaches-the-state
  (app/handle-event {:event/type ::state/rows-changed :event/value-key :text :fx/event "250"})
  (is (= "250" (:rows-text @app/*state))))

(deftest cancelling-raises-the-flag-the-worker-watches
  (is (false? @app/cancel-flag))
  (app/handle-event {:event/type ::state/cancel-requested})
  (is (true? @app/cancel-flag)))

(deftest an-unknown-effect-is-ignored-rather-than-thrown
  (is (nil? (app/perform! [:no-such-effect {}]))))

(deftest revealing-a-folder-that-is-not-there-is-harmless
  (is (nil? (app/perform! [:reveal nil]))))

(deftest each-theme-resolves-to-a-stylesheet
  (is (string? (app/theme-stylesheet :light)))
  (is (string? (app/theme-stylesheet :dark)))
  (is (string? (app/theme-stylesheet :system)))
  (is (not= (app/theme-stylesheet :light) (app/theme-stylesheet :dark))))

(deftest the-system-appearance-is-readable-or-assumed-light
  (is (boolean? (app/system-dark?))))

;; ── Threading and effect plumbing ───────────────────────────────────────────

(defn- wait-until
  "Poll `pred` for up to two seconds. Returns whether it came true."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest work-happens-on-a-daemon-thread
  (testing "so a split in flight can never stop the application from closing"
    (let [where (promise)
          t     (app/run-async! #(deliver where (Thread/currentThread)))]
      (is (some? (deref where 2000 nil)) "the work must actually run")
      (is (.isDaemon ^Thread t))
      (is (= "csv-cleaver-worker" (.getName ^Thread t)))
      (is (not= (Thread/currentThread) @where)))))

(deftest saving-settings-does-not-block-the-interface
  (let [saved (atom nil)]
    (with-redefs [prefs/save-prefs! (fn [m] (reset! saved m) true)]
      (app/perform! [:save-prefs {:theme :dark}])
      (is (wait-until #(some? @saved)))
      (is (= {:theme :dark} @saved)))))

(deftest ^:fx scanning-through-the-effect-reaches-the-ready-state
  (tu/with-temp-dir [dir]
    (let [file (tu/write-file dir "a.csv" "id,name\n1,Ann\n2,Bob\n")]
      (app/perform! [:scan {:file file}])
      (is (wait-until #(= :ready (:phase @app/*state))))
      (is (= 3 (:records (:survey @app/*state)))))))

(deftest ^:fx a-clear-folder-runs-the-whole-way-through-to-a-result
  (tu/with-temp-dir [dir]
    (let [file (tu/write-file dir "people.csv" "id\n1\n2\n3\n")]
      (app/perform! [:scan {:file file}])
      (is (wait-until #(= :ready (:phase @app/*state))))
      (swap! app/*state assoc :rows-text "1" :out-dir dir)
      (app/handle-event {:event/type ::state/split-requested})
      (is (wait-until #(= :done (:phase @app/*state))))
      (is (= 3 (count (get-in @app/*state [:result :files])))))))

(deftest ^:fx a-folder-with-clashing-names-raises-the-dialog-instead
  (tu/with-temp-dir [dir]
    (let [file (tu/write-file dir "people.csv" "id\n1\n2\n")]
      (tu/write-file dir "people_0001.csv" "old")
      (app/perform! [:scan {:file file}])
      (is (wait-until #(= :ready (:phase @app/*state))))
      (swap! app/*state assoc :rows-text "1" :out-dir dir)
      (app/handle-event {:event/type ::state/split-requested})
      (is (wait-until #(= :collisions (:dialog @app/*state))))
      (is (= :ready (:phase @app/*state)) "and nothing was written")
      (is (= "old" (slurp (io/file dir "people_0001.csv")))))))

(deftest ^:fx applying-a-theme-does-not-throw
  (testing "each theme is set on the JavaFX thread; forcing the result surfaces
            anything that went wrong there"
    (doseq [theme [:dark :light :system]]
      (is (nil? @(app/perform! [:apply-theme theme])) (str theme)))))

(deftest ^:fx events-can-be-raised-from-a-worker-thread
  (let [done (promise)]
    (app/run-async! (fn []
                      (app/dispatch! {:event/type ::state/rows-changed
                                      :text "4242"})
                      (deliver done true)))
    (is (true? (deref done 2000 false)))
    (is (wait-until #(= "4242" (:rows-text @app/*state))))))

;; ── Workers ─────────────────────────────────────────────────────────────────

(defn- capture
  "A dispatch function that records what it was told, and the record itself."
  []
  (let [seen (atom [])]
    [seen (fn [event] (swap! seen conj event))]))

(defn- types [seen] (mapv :event/type @seen))

(defn- survey-of [dir content]
  (scan/survey (tu/write-file dir "people.csv" content)))

(deftest scanning-reports-what-it-found
  (tu/with-temp-dir [dir]
    (let [[seen dispatch] (capture)]
      (app/scan-worker {:file (tu/write-file dir "a.csv" "id,name\n1,Ann\n")} dispatch)
      (is (= [::state/scan-succeeded] (types seen)))
      (is (= 2 (:records (:survey (last @seen))))))))

(deftest scanning-something-unreadable-explains-why
  (let [[seen dispatch] (capture)]
    (app/scan-worker {:file (File. "/no/such/file.csv")} dispatch)
    (is (= [::state/scan-failed] (types seen)))
    (is (= {:key :problem/file-moved} (:message (last @seen))))))

(deftest a-clear-folder-goes-straight-to-splitting
  (tu/with-temp-dir [dir]
    (let [[seen dispatch] (capture)]
      (app/collision-worker {:survey (survey-of dir "id\n1\n") :out-dir dir} dispatch)
      (is (= [::state/start-split] (types seen))))))

(deftest a-folder-with-clashing-names-stops-and-asks
  (tu/with-temp-dir [dir]
    (tu/write-file dir "people_0001.csv" "old")
    (let [[seen dispatch] (capture)]
      (app/collision-worker {:survey (survey-of dir "id\n1\n") :out-dir dir} dispatch)
      (is (= [::state/collisions-found] (types seen)))
      (is (= ["people_0001.csv"] (tu/names (:files (last @seen))))))))

(deftest splitting-reports-its-result
  (tu/with-temp-dir [dir]
    (let [[seen dispatch] (capture)
          survey (survey-of dir "id\n1\n2\n3\n")]
      (app/split-worker {:survey survey :out-dir dir :mode :rows :value 1
                         :has-header? true :include-header? true
                         :plan {:file-count 3}}
                        (constantly false) dispatch)
      (is (= ::state/split-succeeded (:event/type (last @seen))))
      (is (= 3 (count (:files (:result (last @seen))))))
      (testing "each new output file is reported as it opens, so the window can
                say which one it is on"
        (is (= 3 (count (filter #(= ::state/split-progress (:event/type %)) @seen))))))))

(deftest splitting-that-is-stopped-reports-a-cancelled-result
  (tu/with-temp-dir [dir]
    (let [[seen dispatch] (capture)
          rows   (apply str "id\n" (for [i (range 20000)] (str i "\n")))
          survey (survey-of dir rows)]
      (app/split-worker {:survey survey :out-dir dir :mode :rows :value 1000
                         :has-header? true :include-header? true
                         :plan {:file-count 20}}
                        (constantly true) dispatch)
      (is (= ::state/split-succeeded (:event/type (last @seen))))
      (is (:cancelled? (:result (last @seen)))))))

(deftest splitting-somewhere-impossible-reports-a-plain-message
  (tu/with-temp-dir [dir]
    (let [[seen dispatch] (capture)]
      (app/split-worker {:survey (survey-of dir "id\n1\n")
                         :out-dir (File. "/proc/definitely/not/here")
                         :mode :rows :value 1 :has-header? true
                         :plan {:file-count 1}}
                        (constantly false) dispatch)
      (is (= [::state/split-failed] (types seen)))
      (is (= :problem/folder-create (:key (:message (last @seen))))))))
