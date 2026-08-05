(ns csv-cleaver.app-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [csv-cleaver.app :as app]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.prefs :as prefs]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.split :as split]
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
      (is (wait-until #(some? (:result @app/*state))))
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

;; ── Persistence ─────────────────────────────────────────────────────────────

(deftest the-settings-worth-keeping-are-gathered-at-the-end
  (testing "the bug this fixes: mode, row count, size, template, the Excel cap
            and the Advanced state were all read back at startup and never once
            written, so every session began by discarding the last one"
    (let [state (assoc state/initial
                       :mode :bytes :rows-text "12,345" :size-text "9 MB"
                       :template "part-{index}" :excel-safe? false
                       :advanced-open? true :theme :dark)
          kept  (app/session-settings state nil)]
      (doseq [k [:mode :rows :size-bytes :template :excel-safe? :advanced-open? :theme]]
        (is (contains? kept k) (str k " must survive a restart")))
      (is (= :bytes (:mode kept)))
      (testing "as numbers, not as the text in the boxes: a settings file saying
                12,345 does not say which language wrote it"
        (is (= 12345 (:rows kept)))
        (is (= (* 9 1024 1024) (:size-bytes kept)))
        (is (not-any? kept [:rows-text :size-text]))))))

(deftest nothing-about-a-particular-file-is-kept
  (tu/with-temp-dir [dir]
    (let [state (assoc state/initial :file (tu/write-file dir "secret.csv" "id\n1\n")
                       :survey {:big :map} :result {:files [:a]})
          kept  (app/session-settings state nil)]
      (is (not (contains? kept :file)))
      (is (not (contains? kept :survey)))
      (is (not (contains? kept :result))))))

(deftest window-geometry-is-kept-when-there-is-a-window
  (is (not (contains? (app/session-settings state/initial nil) :window))
      "and omitted when there is not, rather than saved as nonsense"))

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

;; ── Opening in one language with settings saved in another ──────────────────

(deftest remembered-numbers-open-in-whatever-language-the-window-opens-in
  (testing "R81. They are stored as numbers, so nothing here has to know which
            language wrote them — which is the point, because a settings file
            saying 100,000 does not say."
    (doseq [[saved-lang opening] [["de" "en"] ["en" "de"] ["es" "fr"] ["ja" "es"]]]
      (let [saved {:language saved-lang :rows 100000
                   :size-bytes (* 25 1024 1024) :mode :bytes}
            state (app/startup-state state/initial saved {:locale opening})]
        (is (= opening (:language state)))
        (is (= 100000 (state/split-value (assoc state :mode :rows)))
            (str "rows saved in " saved-lang ", opened in " opening))
        (is (= (* 25 1024 1024) (state/split-value state))
            (str "size saved in " saved-lang ", opened in " opening))))))

(deftest the-numbers-are-written-the-way-the-opening-language-writes-them
  (let [saved {:language "en" :rows 100000 :size-bytes (* 25 1024 1024)}]
    (is (= "100.000" (:rows-text (app/startup-state state/initial saved {:locale "de"}))))
    (is (= "100\u202f000" (:rows-text (app/startup-state state/initial saved {:locale "fr"}))))
    (is (= "25 Mo" (:size-text (app/startup-state state/initial saved {:locale "fr"})))
        "including the unit, which French writes as Mo")))

(deftest a-settings-file-from-before-the-numbers-were-numbers-is-not-guessed-at
  (testing "R81. Earlier versions stored the box contents as text, formatted for
            whatever language the window was in — and the file does not reliably
            say which, because the text was never converted when the language
            changed. One real file said :language \"es\" with :rows-text
            \"100,000\", which read as Spanish is a hundred.

            So they are dropped. Forgetting a row count once is a far smaller
            harm than silently dividing it by a thousand."
    (let [legacy {:language "es" :rows-text "100,000" :size-text "100 MB"
                  :theme :dark :template "part-{index}"}
          state  (app/startup-state state/initial legacy {})]
      (is (= "es" (:language state)))
      (is (= 65000 (state/split-value state))
          "the row count is the default, in Spanish, not a hundred")
      (is (= :dark (:theme state)) "everything unambiguous still comes back")
      (is (= "part-{index}" (:template state))))))

;; ── Event routing that lives in app rather than state ───────────────────────
;;
;; A handful of view events are translated here — label back to value, or a
;; timestamped folder invented — before reaching the pure handler. Untested,
;; they were the only seam where a picker could silently stop working, which is
;; exactly what happened to the encoding picker.

(defn- routed
  "Run one event through app/handle-event with the dispatch loop captured.
   Returns the events it forwarded."
  [start-state event]
  (let [forwarded (atom [])]
    (reset! app/*state start-state)
    (with-redefs [app/dispatch! (fn [e] (swap! forwarded conj e))]
      (app/handle-event event))
    @forwarded))

(deftest a-picked-delimiter-label-becomes-the-character-it-stands-for
  (testing "in every language, because the labels differ in every language"
    (doseq [tag ["en" "es" "fr" "de" "zh" "ja"]]
      (let [st    (state/with-language state/initial tag)
            ctx   (state/ctx st)
            semi  (i18n/tr ctx :delimiter/semicolon)
            [event] (routed st {:event/type :csv-cleaver.view/delimiter-picked
                                :label semi})]
        (is (= :csv-cleaver.state/delimiter-override-changed (:event/type event)) tag)
        (is (= \; (:choice event)) tag)))))

(deftest a-picked-charset-label-becomes-the-charset-it-stands-for
  (doseq [tag ["en" "fr" "ja"]]
    (let [st  (state/with-language state/initial tag)
          ctx (state/ctx st)
          [event] (routed st {:event/type :csv-cleaver.view/charset-picked
                              :label (state/charset-label ctx "UTF-16LE")})]
      (is (= "UTF-16LE" (:choice event)) tag))
    (let [st  (state/with-language state/initial tag)
          ctx (state/ctx st)
          [event] (routed st {:event/type :csv-cleaver.view/charset-picked
                              :label (state/charset-label ctx state/detected-charset)})]
      (is (= state/detected-charset (:choice event))
          (str tag ": the translated Detected entry maps back to the sentinel")))))

(deftest declining-to-replace-goes-to-a-fresh-timestamped-folder
  (tu/with-temp-dir [dir]
    (let [f  (tu/write-file dir "orders.csv" "id\n1\n")
          st (assoc state/initial
                    :out-dir (io/file dir "out")
                    :survey  {:file f})
          [event] (routed st {:event/type :csv-cleaver.view/new-folder-requested})]
      (is (= :csv-cleaver.state/collision-resolved (:event/type event)))
      (is (= :new-dir (:choice event)))
      ;; The whole event in the failure message: this assertion failed once in
      ;; a full run and never since, and the report did not say what the event
      ;; actually was. If it fires again, this time it testifies.
      (is (str/starts-with? (.getName ^File (:dir event)) "orders split ")
          (str "named after the file, inside the folder the user chose — got "
               (pr-str event)))
      (is (= (io/file dir "out") (.getParentFile ^File (:dir event)))
          (pr-str event)))))

(deftest ordinary-events-flow-through-the-pure-handler
  (testing "everything that is not one of the special cases goes to
            state/handle and its effects are performed"
    (let [performed (atom [])]
      (reset! app/*state state/initial)
      (with-redefs [app/perform! (fn [e] (swap! performed conj e))]
        (app/handle-event {:event/type :csv-cleaver.state/rows-changed
                           :text "42"}))
      (is (= "42" (:rows-text @app/*state)))
      (is (= [] @performed) "rows-changed has no effects"))))

;; ── The workers' callbacks ──────────────────────────────────────────────────

(deftest a-long-scan-reports-progress-as-it-goes
  (testing "the Checking… count on the file card comes from this callback, so a
            file long enough to cross the check interval must produce at least
            one progress event before the result"
    (tu/with-temp-dir [dir]
      (let [f (io/file dir "long.csv")]
        (with-open [w (io/writer f)]
          (.write w "id\n")
          (dotimes [i 30000] (.write w (str i "\n"))))
        (let [events (atom [])]
          (app/scan-worker {:file f} #(swap! events conj %))
          (let [kinds (map :event/type @events)]
            (is (some #{:csv-cleaver.state/scan-progress} kinds)
                "at least one progress report")
            (is (= :csv-cleaver.state/scan-succeeded (last kinds)))
            (is (= 30001 (get-in (last @events) [:survey :records])))))))))

(deftest a-split-that-cannot-write-reports-failure-not-an-exception
  (tu/with-temp-dir [dir]
    (let [f      (tu/write-file dir "x.csv" "id\n1\n2\n3\n4\n")
          survey (scan/survey f)
          events (atom [])]
      (app/split-worker {:survey survey
                         ;; a folder that cannot be created: its parent is a file
                         :out-dir (io/file f "impossible")
                         :mode :rows :value 1
                         :has-header? true :include-header? true
                         :plan (split/plan {:survey survey :mode :rows :value 1
                                            :has-header? true})}
                        (constantly false)
                        #(swap! events conj %))
      (let [event (last @events)]
        (is (= :csv-cleaver.state/split-failed (:event/type event)))
        (is (some? (:message event)) "carrying something the window can say")))))

;; ── One chooser at a time ───────────────────────────────────────────────────

(deftest a-second-click-cannot-raise-a-second-dialog
  (testing "reported against the built application: every click on Browse
            stacked another native dialog on another nested event loop, each
            answered dialog started its own scan, and quitting hung until every
            dialog was dismissed in reverse. The claim is the testable half;
            window-modality is the other and belongs to the platform."
    (app/release-chooser!)
    (is (true? (app/claim-chooser!)) "the first click gets the dialog")
    (is (false? (app/claim-chooser!)) "the queued double-click gets nothing")
    (is (false? (app/claim-chooser!)) "and neither does a third")
    (app/release-chooser!)
    (is (true? (app/claim-chooser!)) "after the dialog closes, Browse works again")
    (app/release-chooser!)))

(deftest the-workers-wear-the-scan-id-they-were-given
  (testing "the stale-scan guard in state is only as good as the id the worker
            carries: a worker that drops it produces trusted, unguarded events"
    (tu/with-temp-dir [dir]
      (let [f      (tu/write-file dir "x.csv" "id\n1\n")
            events (atom [])]
        (app/scan-worker {:file f :scan-id 7} #(swap! events conj %))
        (is (seq @events))
        (is (every? #(= 7 (:scan-id %)) @events)
            "every event — progress, success, failure — carries the id")))))
