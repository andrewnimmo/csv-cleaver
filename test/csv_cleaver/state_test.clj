(ns csv-cleaver.state-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.prefs :as prefs]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.state :as state]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File)))

(defn survey-in [dir content]
  (scan/survey (tu/write-file dir "people.csv" content)))

(defn ready-state
  "State as it stands just after a file has been surveyed."
  [dir & [content]]
  (let [survey (survey-in dir (or content "id,name\n1,Ann\n2,Bob\n3,Cy\n4,Dee\n"))]
    (state/apply-event state/initial {:event/type ::state/scan-succeeded :survey survey})))

;; ── Choosing a file ─────────────────────────────────────────────────────────

(deftest choosing-a-file-starts-a-scan
  (tu/with-temp-dir [dir]
    (let [f (tu/write-file dir "a.csv" "id\n1\n")
          {:keys [state effects]} (state/handle state/initial
                                                {:event/type ::state/file-chosen :file f})]
      (is (= :scanning (:phase state)))
      (is (= f (:file state)))
      (is (= [[:scan {:file f :delimiter nil :scan-id 1}]] effects)))))

(deftest choosing-something-unreadable-explains-itself
  (let [{:keys [state effects]} (state/handle state/initial
                                              {:event/type ::state/file-chosen
                                               :file (File. "/no/such/file.csv")})]
    (is (= :empty (:phase state)))
    (is (= :problem/unreadable (:error state)))
    (is (empty? effects))))

(deftest scan-progress-is-shown-while-it-runs
  (is (= 40000 (:scan-rows (state/apply-event state/initial
                                              {:event/type ::state/scan-progress
                                               :rows 40000})))))

(deftest a-finished-scan-sets-up-the-options
  (tu/with-temp-dir [dir]
    (let [s (ready-state dir)]
      (is (= :ready (:phase s)))
      (is (:has-header? s) "the checkbox follows what the file looks like")
      (testing "results go into a new folder named after the file, beside it —
                so they cannot mix with whatever is already in the source folder
                and a mis-chosen destination is visible rather than inferred"
        (is (= "people split" (.getName ^File (:out-dir s))))
        (is (= (.getCanonicalFile dir)
               (.getCanonicalFile (.getParentFile ^File (:out-dir s)))))))))

(deftest a-header-less-file-unticks-the-box
  (tu/with-temp-dir [dir]
    (is (not (:has-header? (ready-state dir "1,Ann\n2,Bob\n"))))))

(deftest a-failed-scan-returns-to-the-empty-state
  (let [s (state/apply-events state/initial
                              [{:event/type ::state/file-chosen :file (File. "x")}
                               {:event/type ::state/scan-failed :message "Nope."}])]
    (is (= :empty (:phase s)))
    (is (= "Nope." (:error s)))
    (is (nil? (:file s)))))

;; ── Options ─────────────────────────────────────────────────────────────────

(deftest options-record-what-was-chosen
  (is (= :bytes (:mode (state/apply-event state/initial
                                          {:event/type ::state/mode-changed :mode :bytes}))))
  (is (= "100" (:rows-text (state/apply-event state/initial
                                              {:event/type ::state/rows-changed :text "100"}))))
  (is (= "9 MB" (:size-text (state/apply-event state/initial
                                               {:event/type ::state/size-changed :text "9 MB"}))))
  (is (false? (:has-header? (state/apply-event state/initial
                                               {:event/type ::state/header-toggled :selected false}))))
  (is (false? (:include-header?
               (state/apply-event state/initial
                                  {:event/type ::state/include-header-toggled :selected false}))))
  (is (= "x{index}" (:template (state/apply-event state/initial
                                                  {:event/type ::state/template-changed
                                                   :text "x{index}"})))))

(deftest presets-fill-in-the-right-box
  (is (= "100,000" (:rows-text (state/apply-event state/initial
                                                  {:event/type ::state/preset-chosen
                                                   :value 100000}))))
  (is (= "25 MB" (:size-text (state/apply-event
                              (assoc state/initial :mode :bytes)
                              {:event/type ::state/preset-chosen :value "25 MB"}))))
  (testing "a preset is written in the window's own language, and reads back"
    (let [german (-> (state/with-language state/initial "de")
                     (state/apply-event {:event/type ::state/preset-chosen :value 100000}))]
      (is (= "100.000" (:rows-text german)))
      (is (= 100000 (state/split-value german))))))

(deftest presets-say-what-they-are-for
  (testing "a bare 65,000 tells a non-expert nothing"
    (is (= [65000 100000 1048576] (mapv :value state/row-presets)))
    (is (every? :label-key state/row-presets))
    (is (= :preset/excel-max (:label-key (last state/row-presets))))))

(deftest disclosures-toggle
  (is (:advanced-open? (state/apply-event state/initial {:event/type ::state/advanced-toggled})))
  (is (:details-open? (state/apply-event state/initial {:event/type ::state/details-toggled})))
  (is (not (:advanced-open? (state/apply-events state/initial
                                                (repeat 2 {:event/type ::state/advanced-toggled}))))))

(deftest changing-the-theme-applies-and-remembers-it
  (let [{:keys [state effects]} (state/handle state/initial
                                              {:event/type ::state/theme-changed :theme :dark})]
    (is (= :dark (:theme state)))
    (is (= [[:apply-theme :dark] [:save-prefs {:theme :dark}]] effects))))

(deftest browsing-asks-for-a-dialog
  (is (= [[:choose-file {:initial-dir nil}]]
         (:effects (state/handle state/initial {:event/type ::state/browse-input-requested}))))
  (is (= [[:choose-dir {:initial-dir nil}]]
         (:effects (state/handle state/initial {:event/type ::state/browse-output-requested})))))

(deftest a-chosen-folder-is-remembered
  (tu/with-temp-dir [dir]
    (let [{:keys [state effects]} (state/handle state/initial
                                                {:event/type ::state/out-dir-chosen :dir dir})]
      (is (= dir (:out-dir state)) "used exactly as picked")
      (is (= dir (:output-base state)) "and remembered as the home for the next file")
      (is (= [[:save-prefs {:output-base (.getAbsolutePath dir)}]
              [:inspect-out-dir dir]]
             effects))))
  (testing "cancelling the dialog changes nothing"
    (is (= state/initial (state/apply-event state/initial
                                            {:event/type ::state/out-dir-chosen :dir nil})))))

(deftest dragging-highlights-the-drop-zone
  (is (:drag-over? (state/apply-event state/initial {:event/type ::state/drag-entered})))
  (is (not (:drag-over? (state/apply-events state/initial
                                            [{:event/type ::state/drag-entered}
                                             {:event/type ::state/drag-exited}])))))

;; ── Derived values ──────────────────────────────────────────────────────────

(deftest reads-the-value-for-the-current-mode
  (is (= 65000 (state/split-value state/initial)))
  (is (= (* 25 1024 1024) (state/split-value (assoc state/initial :mode :bytes))))
  (is (nil? (state/split-value (assoc state/initial :rows-text "abc")))))

(deftest knows-when-it-can-split
  (tu/with-temp-dir [dir]
    (let [s (ready-state dir)]
      (is (state/ready? (assoc s :rows-text "2")))
      (testing "not before a file has been chosen"
        (is (not (state/ready? state/initial))))
      (testing "not while a scan or a split is running"
        (is (not (state/ready? (assoc s :phase :splitting)))))
      (testing "not with an unusable number"
        (is (not (state/ready? (assoc s :rows-text "nonsense")))))
      (testing "not with an unusable file name pattern"
        (is (not (state/ready? (assoc s :template "{name}"))))))))

(deftest names-the-one-thing-in-the-way
  (tu/with-temp-dir [dir]
    (let [s (ready-state dir)]
      (is (nil? (state/blocking-problem (assoc s :rows-text "2"))))
      (is (= :problem/rows-needed (state/blocking-problem (assoc s :rows-text ""))))
      (is (= :problem/size-needed
             (state/blocking-problem (assoc s :mode :bytes :size-text ""))))
      (is (= :problem/pattern-index (state/blocking-problem (assoc s :template "{name}"))))
      (testing "nothing to report before a file is chosen"
        (is (nil? (state/blocking-problem state/initial)))))))

(deftest counts-data-rows
  (tu/with-temp-dir [dir]
    (let [s (ready-state dir)]
      (is (= 4 (state/data-rows s)))
      (is (= 5 (state/data-rows (assoc s :has-header? false))))))
  (is (= 0 (state/data-rows state/initial))))

(deftest overriding-the-encoding-changes-how-the-file-is-read
  (tu/with-temp-dir [dir]
    (let [s (ready-state dir)]
      (is (= "UTF-8" (:label (state/effective-encoding s))))
      (let [overridden (state/apply-event s {:event/type ::state/charset-override-changed
                                             :choice "windows-1252"})]
        (is (= "windows-1252" (:label (state/effective-encoding overridden))))
        (is (= "windows-1252" (get-in (state/split-request overridden)
                                      [:survey :encoding :label]))))
      (testing "choosing Detected again puts it back"
        (is (= "UTF-8" (:label (state/effective-encoding
                                (assoc s :charset-override state/detected-charset)))))))))

;; ── Splitting ───────────────────────────────────────────────────────────────

(deftest splitting-checks-the-folder-before-writing-anything
  (testing "the guarantee that nothing can be destroyed without permission"
    (tu/with-temp-dir [dir]
      (let [s (assoc (ready-state dir) :rows-text "2")
            {:keys [state effects]} (state/handle s {:event/type ::state/split-requested})]
        (is (= :ready (:phase state)) "no split starts yet")
        (is (= 1 (count effects)))
        (is (= :check-collisions (ffirst effects)))))))

(deftest splitting-is-refused-when-the-settings-do-not-work
  (tu/with-temp-dir [dir]
    (let [s (assoc (ready-state dir) :rows-text "")
          {:keys [state effects]} (state/handle s {:event/type ::state/split-requested})]
      (is (= s state))
      (is (empty? effects)))))

(deftest a-clear-folder-goes-straight-to-splitting
  (tu/with-temp-dir [dir]
    (let [s (assoc (ready-state dir) :rows-text "2")
          {:keys [state effects]} (state/handle s {:event/type ::state/start-split})]
      (is (= :splitting (:phase state)))
      (is (= :split (ffirst effects)))
      (is (= 2 (:value (second (first effects))))))))

(deftest a-clash-raises-the-dialog-and-writes-nothing
  (tu/with-temp-dir [dir]
    (let [existing [(io/file dir "people_0001.csv")]
          s (state/apply-event (ready-state dir)
                               {:event/type ::state/collisions-found :files existing})]
      (is (= :collisions (:dialog s)))
      (is (= existing (:collisions s)))
      (is (= :ready (:phase s)) "still not splitting"))))

(deftest the-dialog-offers-three-ways-out
  (tu/with-temp-dir [dir]
    (let [clashed (-> (ready-state dir)
                      (assoc :rows-text "2")
                      (state/apply-event {:event/type ::state/collisions-found
                                          :files [(io/file dir "people_0001.csv")]}))]
      (testing "cancel puts everything back and starts nothing"
        (let [{:keys [state effects]} (state/handle clashed
                                                    {:event/type ::state/collision-resolved
                                                     :choice :cancel})]
          (is (nil? (:dialog state)))
          (is (= :ready (:phase state)))
          (is (empty? effects))))

      (testing "replacing goes ahead in the same folder"
        (let [{:keys [state effects]} (state/handle clashed
                                                    {:event/type ::state/collision-resolved
                                                     :choice :replace})]
          (is (= :splitting (:phase state)))
          (is (= :split (ffirst effects)))))

      (testing "a new folder goes ahead somewhere else"
        (let [fresh (io/file dir "somewhere else")
              {:keys [state effects]} (state/handle clashed
                                                    {:event/type ::state/collision-resolved
                                                     :choice :new-dir :dir fresh})]
          (is (= :splitting (:phase state)))
          (is (= fresh (:out-dir state)))
          (is (= fresh (:out-dir (second (first effects))))))))))

(deftest progress-and-completion-are-recorded
  (let [running (assoc state/initial :phase :splitting)
        p       {:rows-done 500 :files-done 2 :current-name "a_0003.csv"}]
    (is (= p (:progress (state/apply-event running {:event/type ::state/split-progress
                                                    :progress p}))))
    (let [done (state/apply-event running {:event/type ::state/split-succeeded
                                           :result {:files [:a] :elapsed-ms 10}})]
      (is (= :ready (:phase done)) "back to the options, with the outcome above them")
      (is (= [:a] (get-in done [:result :files]))))))

(deftest a-failed-split-says-so-without-losing-the-file
  (let [s (state/apply-event (assoc state/initial :phase :splitting :file :f)
                             {:event/type ::state/split-failed
                              :message {:key :problem/disk-full}})]
    (is (= :ready (:phase s)) "back to the options, not back to square one")
    (is (= {:key :problem/disk-full} (:error s)))
    (is (= :f (:file s)))))

(deftest the-language-can-be-changed-and-is-remembered
  (let [{:keys [state effects]} (state/handle state/initial
                                              {:event/type ::state/language-changed
                                               :tag "de"})]
    (is (= "de" (:language state)))
    (is (= "de" (:tag (state/ctx state))))
    (is (= [[:save-prefs {:language "de"}] [:sync-native-about]] effects)))
  (testing "the picker sends a language's own name, not its tag"
    (is (= "fr" (:language (state/apply-event state/initial
                                              {:event/type ::state/language-changed
                                               :language-name "Français"})))))
  (testing "and an unknown language quietly stays English"
    (is (= "en" (:language (state/apply-event state/initial
                                              {:event/type ::state/language-changed
                                               :tag "klingon"}))))))

(deftest quitting-is-an-effect-not-an-immediate-exit
  (testing "so the decision to end the process is data, and testable"
    (let [{:keys [state effects]} (state/handle state/initial
                                                {:event/type ::state/quit-requested})]
      (is (= state/initial state) "nothing about the window changes")
      (is (= [[:quit]] effects)))))

(deftest the-overlays-open-and-close
  (is (= :about (:dialog (state/apply-event state/initial
                                            {:event/type ::state/about-toggled}))))
  (is (= :help (:dialog (state/apply-event state/initial
                                           {:event/type ::state/help-toggled}))))
  (testing "pressing the same button again closes it"
    (is (nil? (:dialog (state/apply-events state/initial
                                           (repeat 2 {:event/type ::state/about-toggled}))))))
  (is (nil? (:dialog (state/apply-events state/initial
                                         [{:event/type ::state/about-toggled}
                                          {:event/type ::state/dialog-closed}])))))

(deftest cancelling-asks-the-worker-to-stop
  (is (= [[:cancel]] (:effects (state/handle (assoc state/initial :phase :splitting)
                                             {:event/type ::state/cancel-requested})))))

(deftest revealing-opens-the-output-folder
  (tu/with-temp-dir [dir]
    (is (= [[:reveal dir]] (:effects (state/handle (assoc state/initial :out-dir dir)
                                                   {:event/type ::state/reveal-requested}))))))

(deftest starting-again-keeps-the-settings-but-drops-the-file
  (tu/with-temp-dir [dir]
    (let [done  (-> (ready-state dir)
                    (assoc :rows-text "999" :template "x{index}" :mode :bytes)
                    (state/apply-event {:event/type ::state/split-succeeded
                                        :result {:files [:a] :elapsed-ms 1}}))
          again (state/apply-event done {:event/type ::state/reset})]
      (is (= :empty (:phase again)))
      (is (nil? (:file again)))
      (is (nil? (:result again)))
      (testing "what the user configured survives, so they need not set it twice"
        (is (= "999" (:rows-text again)))
        (is (= "x{index}" (:template again)))
        (is (= :bytes (:mode again)))
        (is (= "people split" (.getName ^File (:out-dir again))))))))

(deftest an-unknown-event-changes-nothing
  (is (= state/initial (state/apply-event state/initial {:event/type ::nonsense}))))

(deftest the-default-template-is-usable
  (is (nil? (naming/template-problem (:template state/initial)))))

(deftest changing-language-rewrites-the-numbers-the-user-typed
  (testing "R80, reported against the built application: the text changed language
            but the numbers did not. Worse than it looks — the row count is read
            back in the window's current language, so an untouched English
            65,000 in a German window means sixty-five, and the split would
            produce a thousand times as many files as asked for."
    (let [en state/initial
          de (state/with-language en "de")
          fr (state/with-language en "fr")]
      (is (= "65,000" (:rows-text en)))
      (is (= "65.000" (:rows-text de)) "written as German writes it")
      (is (= 65000 (state/split-value en)))
      (is (= 65000 (state/split-value de)) "and still means what it meant")
      (is (= 65000 (state/split-value fr))))))

(deftest changing-language-back-and-forth-does-not-drift
  (let [wander (reduce state/with-language state/initial
                       ["de" "fr" "ja" "zh" "es" "en"])]
    (is (= "65,000" (:rows-text wander)))
    (is (= "25 MB" (:size-text wander)))
    (is (= 65000 (state/split-value wander)))))

(deftest changing-language-leaves-half-typed-input-alone
  (testing "someone mid-keystroke owns what is in the box"
    (let [state (-> (assoc state/initial :rows-text "65,")
                    (state/with-language "de"))]
      (is (= "65," (:rows-text state))))))

(deftest a-size-keeps-its-unit-across-a-language-change
  (testing "the unit the user chose is kept, and translated. Going through a
            byte count and picking a unit afresh would turn their 1.5 GB into
            1,536 MB — the same size, and not what they wrote."
    (let [german (-> (assoc state/initial :mode :bytes :size-text "1.5 GB")
                     (state/with-language "de"))
          french (-> (assoc state/initial :mode :bytes :size-text "1.5 GB")
                     (state/with-language "fr"))]
      (is (= "1,5 GB" (:size-text german)))
      (is (= "1,5 Go" (:size-text french)) "French writes gigabytes as Go")
      (is (= (long (* 1.5 1024 1024 1024)) (state/split-value german)))
      (is (= (long (* 1.5 1024 1024 1024)) (state/split-value french))))))

;; ── The class of bug, not just the instance ─────────────────────────────────
;;
;; R80 was found by using the application rather than by a test, because every
;; test here checked a function in isolation and each function was correct.
;; What was missing was the invariant that connects two of them: numbers are
;; written in the interface language (R29) and read back in the interface
;; language (R31), so anything holding a number as text has to be rewritten when
;; that language changes. The tests below are about the class — a setting that
;; holds a formatted value — rather than about the two that were wrong.

(def language-sensitive-settings
  "Remembered settings that hold a number written the way some language writes
   it. Empty, and it should stay that way: :rows and :size-bytes are numbers
   precisely so that no stored setting has to remember which language wrote it."
  #{})

(def language-free-settings
  "Remembered settings a language change must leave exactly alone. `:language`
   is excluded below for the obvious reason."
  #{:theme :template :mode :advanced-open? :excel-safe? :output-base :window
    :rows :size-bytes})

(deftest every-remembered-setting-is-classified
  (testing "this test exists to fail when a setting is added without anyone
            deciding whether a language change affects it. That decision going
            unmade by default is how R80 happened."
    (is (= (set prefs/remembered)
           (conj (into language-sensitive-settings language-free-settings) :language))
        (str "classify the new setting above as language-sensitive or not: "
             (pr-str (remove (conj (into language-sensitive-settings
                                         language-free-settings) :language)
                             prefs/remembered))))))

(deftest a-language-change-alters-nothing-it-should-not
  (let [seeded (merge state/initial
                      {:template       "{name}-{index}"
                       :mode           :bytes
                       :excel-safe?    false
                       :advanced-open? true
                       :output-base    (io/file "/tmp/wherever")
                       :theme          :dark
                       :window         {:x 10.0 :y 20.0 :width 900.0 :height 700.0}})
        after  (state/with-language seeded "ja")]
    (doseq [k language-free-settings]
      (is (= (get seeded k) (get after k))
          (str k " must not change when the language does")))))

(deftest every-language-sensitive-setting-keeps-its-meaning
  (testing "across every pair of languages, in both directions"
    (let [tags ["en" "de" "fr" "es" "zh" "ja"]]
      (doseq [from tags to tags]
        (let [start (state/with-language state/initial from)
              moved (state/with-language start to)]
          (is (= (state/split-value start) (state/split-value moved))
              (str "rows: " from " → " to))
          (is (= (state/split-value (assoc start :mode :bytes))
                 (state/split-value (assoc moved :mode :bytes)))
              (str "size: " from " → " to)))))))

(deftest a-number-too-large-does-not-stop-the-language-changing
  (testing "the report: with a thousand digits in the row box, choosing another
            language did nothing at all — the switch threw, and the window kept
            the language it had while the picker showed the new one."
    (let [big     (apply str (repeat 1000 "1"))
          started (assoc state/initial :rows-text big)]
      (doseq [tag ["de" "fr" "es" "ja" "zh"]]
        (let [changed (state/with-language started tag)]
          (is (= tag (:language changed)) (str "must reach " tag))
          (is (= big (:rows-text changed)) "and leave the unusable text alone")))
      (testing "through the event the picker actually sends"
        (let [changed (state/apply-event started
                                         {:event/type ::state/language-changed
                                          :language-name "Español"})]
          (is (= "es" (:language changed))))))))

(deftest a-number-too-large-says-so-rather-than-asking-for-one
  (testing "the box was not empty. Telling someone to enter a number they have
            already entered explains nothing about why Split is disabled."
    (let [big (apply str (repeat 1000 "1"))]
      (is (= :problem/number-too-large
             (state/blocking-problem (assoc state/initial :phase :ready :rows-text big))))
      (is (= :problem/number-too-large
             (state/blocking-problem (assoc state/initial :phase :ready :mode :bytes
                                            :size-text big))))
      (is (= :problem/rows-needed
             (state/blocking-problem (assoc state/initial :phase :ready :rows-text "")))
          "an empty box is still an empty box")
      (is (not (state/ready? (assoc state/initial :phase :ready :rows-text big)))
          "and Split stays disabled either way"))))

;; ── Handlers found uncovered by the audit ────────────────────────────────────
;;
;; Each of these existed with no test at all, which means each could have been
;; broken by any refactor without a single failure. Found by reading the
;; coverage report file by file rather than trusting the total.

(deftest toggling-excel-safety-changes-exactly-that
  (let [on  (state/apply-event state/initial
                               {:event/type ::state/excel-safe-toggled :selected false})]
    (is (false? (:excel-safe? on)))
    (is (= (dissoc state/initial :excel-safe?) (dissoc on :excel-safe?))
        "and nothing else")))

(deftest answering-the-header-question-stops-it-being-asked-again
  (testing "R33-adjacent: the question is asked once, and the answer is kept
            apart from the detection so neither overwrites the other"
    (let [asked (state/apply-event state/initial
                                   {:event/type ::state/header-answered
                                    :has-header? false})]
      (is (false? (:has-header? asked)))
      (is (true? (:header-answered? asked)))
      (is (not (state/header-uncertain?
                (assoc asked :survey {:header {:verdict :unsure}})))
          "an answered question is never re-asked, whatever the evidence"))))

(deftest choosing-a-delimiter-re-surveys-the-file
  (testing "everything downstream of the delimiter — counts, fields, damage —
            depends on it, so an override cannot patch the survey; it has to
            re-read the file with the new delimiter"
    (tu/with-temp-dir [dir]
      (let [f (tu/write-file dir "x.csv" "a;b\n1;2\n")
            {:keys [state effects]}
            (state/handle (assoc state/initial :file f :phase :ready)
                          {:event/type ::state/delimiter-override-changed
                           :choice \;})]
        (is (= \; (:delimiter-override state)))
        (is (= :scanning (:phase state)))
        (is (= [[:scan {:file f :delimiter \; :scan-id 1}]] effects)
            "as an effect carrying the scan's own id, so a stale reply can be told apart"))))
  (testing "with no file chosen there is nothing to re-survey"
    (let [{:keys [state effects]}
          (state/handle state/initial
                        {:event/type ::state/delimiter-override-changed :choice \,})]
      (is (= \, (:delimiter-override state)))
      (is (empty? effects)))))

(deftest a-report-about-an-abandoned-folder-is-ignored
  (testing "the inspection runs on another thread; by the time it answers, the
            user may have chosen a different folder, and a stale answer must
            not overwrite the current one"
    (tu/with-temp-dir [dir]
      (let [current (assoc state/initial :out-dir (io/file dir "chosen"))
            stale   (state/apply-event current
                                       {:event/type ::state/out-dir-inspected
                                        :dir  (io/file dir "abandoned")
                                        :info {:exists? true :csv-count 9}})
            fresh   (state/apply-event current
                                       {:event/type ::state/out-dir-inspected
                                        :dir  (io/file dir "chosen")
                                        :info {:exists? true :csv-count 2}})]
        (is (nil? (:out-dir-info stale)) "the stale reply changed nothing")
        (is (= 2 (:csv-count (:out-dir-info fresh))))))))

(deftest an-unknown-charset-label-falls-back-to-detection
  (testing "the safe answer, not nil: nil would become a charset lookup crash
            later, and detection is what every other unrecognised state means"
    (doseq [tag ["en" "es" "fr" "de" "zh" "ja"]]
      (let [c (i18n/context tag)]
        (is (= state/detected-charset (state/charset-for-label c "no such label")))
        (testing "and every real label round-trips"
          (doseq [charset state/selectable-charsets]
            (is (= charset (state/charset-for-label
                            c (state/charset-label c charset)))
                (str tag " / " charset))))))))

(deftest a-superseded-scan-cannot-install-its-survey
  (testing "two scans in flight — two file dialogs both answered, or a re-scan
            racing the original — used to resolve as whichever finished last
            wins the window, which is not necessarily the file the user chose
            last. Every scan now carries the id it was started with."
    (tu/with-temp-dir [dir]
      (let [a       (tu/write-file dir "a.csv" "id\n1\n")
            b       (tu/write-file dir "b.csv" "id\n1\n2\n")
            ;; The user picks a, then picks b before a's scan finishes.
            after-a (state/apply-event state/initial
                                       {:event/type ::state/file-chosen :file a})
            after-b (state/apply-event after-a
                                       {:event/type ::state/file-chosen :file b})
            ;; a's scan now finishes, wearing its stale id.
            settled (state/apply-event after-b
                                       {:event/type ::state/scan-succeeded
                                        :scan-id    (:scan-id after-a)
                                        :survey     {:file a :records 2}})]
        (is (= 2 (:scan-id after-b)) "each choice takes a fresh id")
        (is (= :scanning (:phase settled)) "still waiting for b")
        (is (nil? (:survey settled)) "a's survey went nowhere")
        (testing "and b's own reply is honoured"
          (let [done (state/apply-event settled
                                        {:event/type ::state/scan-succeeded
                                         :scan-id    (:scan-id after-b)
                                         :survey     {:file b :records 3}})]
            (is (= :ready (:phase done)))
            (is (= b (get-in done [:survey :file])))))
        (testing "stale progress and stale failure are ignored the same way"
          (is (= after-b (dissoc (state/apply-event after-b
                                                    {:event/type ::state/scan-progress
                                                     :scan-id 1 :rows 999})
                                 :effects)))
          (is (= :scanning (:phase (state/apply-event after-b
                                                      {:event/type ::state/scan-failed
                                                       :scan-id 1
                                                       :message :problem/generic})))
              "a stale failure does not blank the window either"))))))

(deftest clicking-the-contact-address-asks-for-a-mail-composer
  (let [{:keys [state effects]}
        (state/handle state/initial {:event/type ::state/contact-clicked})]
    (is (= [[:compose-mail]] effects))
    (is (= state/initial state) "and changes nothing else")))

(deftest alt-and-about-open-the-hidden-languages
  (testing "an effect, not a direct call, so the pure layer stays pure"
    (let [alt-down (state/apply-event state/initial
                                      {:event/type ::state/alt-changed :down? true})]
      (is (true? (:alt-down? alt-down)))
      (is (= [[:reveal-hidden]]
             (:effects (state/handle alt-down {:event/type ::state/about-toggled})))
          "Alt held while opening About reveals them")
      (is (empty? (:effects (state/handle state/initial
                                          {:event/type ::state/about-toggled})))
          "without Alt, About is just About")
      (is (empty? (:effects (state/handle (assoc alt-down :dialog :about)
                                          {:event/type ::state/about-toggled})))
          "closing About reveals nothing, whatever the keys are doing"))))

(deftest changing-language-refreshes-the-native-about-title
  (let [{:keys [effects]} (state/handle state/initial
                                        {:event/type ::state/language-changed
                                         :language-name "Español"})]
    (is (some #{[:sync-native-about]} effects)
        "the macOS application menu's About must follow the window's language")))
