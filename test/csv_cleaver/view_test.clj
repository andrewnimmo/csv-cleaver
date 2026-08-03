(ns csv-cleaver.view-test
  "The view functions return plain maps, so the whole interface can be checked
   by reading data. No display, no toolkit, no robot."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.state :as state]
   [csv-cleaver.test-util :as tu]
   [csv-cleaver.view :as view]))

;; ── Walking a description ───────────────────────────────────────────────────

(defn nodes
  "Every map in a description tree."
  [desc]
  (filter map? (tree-seq coll? seq desc)))

(defn texts
  "Every piece of text the user would see."
  [desc]
  (->> (nodes desc) (keep :text) (map str)))

(defn text-containing
  [desc re]
  (first (filter #(re-find re %) (texts desc))))

(defn of-type
  [desc fx-type]
  (filter #(= fx-type (:fx/type %)) (nodes desc)))

(defn style-classes
  [desc]
  (set (mapcat #(map str (:style-class %)) (nodes desc))))

(defn actions
  "Every event a click could raise."
  [desc]
  (->> (nodes desc) (keep :on-action) (map :event/type) set))

;; ── Fixtures ────────────────────────────────────────────────────────────────

(defn ready-state
  [dir & [content]]
  (-> state/initial
      (state/apply-event
       {:event/type ::state/scan-succeeded
        :survey (scan/survey (tu/write-file dir "people.csv"
                                            (or content "id,name\n1,Ann\n2,Bob\n3,Cy\n4,Dee\n")))})
      (assoc :rows-text "2")))

;; ── Root ────────────────────────────────────────────────────────────────────

(deftest the-window-has-a-title-and-a-scene
  (let [r (view/root state/initial)]
    (is (= :stage (:fx/type r)))
    (is (= "CSV Cleaver" (:title r)))
    (is (= :scene (get-in r [:scene :fx/type])))
    (is (seq (get-in r [:scene :stylesheets])))
    (testing "it can be made smaller than it opens, unlike the old window whose
              minimum height equalled its height"
      (is (< (:min-height r) (:height r)))
      (is (< (:min-width r) (:width r))))))

;; ── Empty ───────────────────────────────────────────────────────────────────

(deftest the-empty-window-invites-a-file
  (let [d (view/content state/initial)]
    (is (text-containing d #"Drop a CSV file here"))
    (is (text-containing d #"Browse"))
    (is (text-containing d #"Splitting options appear once you pick a file"))
    (is (contains? (actions d) ::state/browse-input-requested))))

(deftest dragging-over-the-window-says-so
  (let [d (view/content (assoc state/initial :drag-over? true))]
    (is (text-containing d #"Let go to open it"))
    (is (contains? (style-classes d) "active"))))

(deftest an-error-is-shown-on-the-empty-window
  (is (text-containing (view/content (assoc state/initial :error "Nope."))
                       #"Nope.")))

;; ── Scanning ────────────────────────────────────────────────────────────────

(deftest scanning-shows-what-it-is-doing
  (let [d (view/content (assoc state/initial :phase :scanning
                               :file (io/file "big.csv") :scan-rows 40000))]
    (is (text-containing d #"big.csv"))
    (is (text-containing d #"40,000 rows so far"))
    (is (= -1.0 (:progress (first (of-type d :progress-bar))))
        "an indeterminate bar, because the total is not yet known"))
  (testing "before any rows are counted"
    (is (text-containing (view/content (assoc state/initial :phase :scanning
                                              :file (io/file "a.csv")))
                         #"Checking the file…"))))

;; ── Ready ───────────────────────────────────────────────────────────────────

(deftest the-file-card-summarises-the-file
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir))]
      (is (text-containing d #"people.csv"))
      (is (text-containing d #"4 data rows"))
      (is (text-containing d #"Text: UTF-8"))
      (is (text-containing d #"Looks healthy")))))

(deftest a-damaged-file-says-so-on-the-card-not-in-a-dialog
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir "a,b,c\n1,2,3\n4,5\n"))]
      (is (text-containing d #"1 row looks damaged"))
      (is (text-containing d #"nothing is lost"))
      (is (not (text-containing d #"Looks healthy"))))))

(deftest an-unusual-delimiter-is-pointed-out
  (tu/with-temp-dir [dir]
    (is (text-containing (view/content (ready-state dir "id;name\n1;Ann\n2;Bob\n"))
                         #"Separated by semicolons"))
    (testing "commas are unremarkable and go unmentioned"
      (is (not (text-containing (view/content (ready-state dir)) #"Separated by"))))))

(deftest the-plan-is-spelled-out-before-anything-happens
  (tu/with-temp-dir [dir]
    (is (text-containing (view/content (ready-state dir)) #"This makes 2 files of 2 rows each"))))

(deftest a-bad-number-replaces-the-plan-with-the-reason
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir) :rows-text "abc"))]
      (is (text-containing d #"Enter how many rows"))
      (is (contains? (style-classes d) "danger")))))

(deftest both-splitting-modes-are-offered
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir))
          toggles (of-type d :toggle-button)]
      (is (= 2 (count toggles)))
      (is (= [true false] (mapv :selected toggles)))
      (testing "switching mode changes the box and its presets"
        (let [by-size (view/content (assoc (ready-state dir) :mode :bytes))]
          (is (text-containing by-size #"in each file"))
          (is (text-containing by-size #"25 MB")))))))

(deftest the-header-checkboxes-read-as-plain-english
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir))]
      (is (text-containing d #"My file has a header row"))
      (is (text-containing d #"Repeat that header in every file"))))
  (testing "repeating a header is meaningless without one, so it is disabled"
    (tu/with-temp-dir [dir]
      (let [boxes (of-type (view/content (assoc (ready-state dir) :has-header? false))
                           :check-box)]
        (is (true? (:disable (second boxes))))))))

(deftest the-output-folder-and-resulting-names-are-shown
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir))]
      (is (text-containing d #"people_0001.csv, people_0002.csv"))
      (is (contains? (actions d) ::state/browse-output-requested)))))

(deftest advanced-is-closed-until-asked-for
  (tu/with-temp-dir [dir]
    (let [closed (view/content (ready-state dir))
          open   (view/content (assoc (ready-state dir) :advanced-open? true))]
      (is (not (text-containing closed #"File name pattern")))
      (is (text-containing open #"File name pattern"))
      (is (text-containing open #"Text encoding"))
      (is (text-containing open #"Detected UTF-8")))))

(deftest a-broken-pattern-explains-itself-and-hides-the-example
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir) :advanced-open? true :template "{name}"))]
      (is (text-containing d #"needs \{index\}"))
      (is (not (text-containing d #"and so on"))))))

(deftest split-is-offered-only-when-it-would-work
  (tu/with-temp-dir [dir]
    (let [ok  (first (filter #(= "Split file" (:text %)) (nodes (view/content (ready-state dir)))))
          bad (first (filter #(= "Split file" (:text %))
                             (nodes (view/content (assoc (ready-state dir) :rows-text "")))))]
      (is (false? (:disable ok)))
      (is (true? (:disable bad))))))

;; ── Splitting ───────────────────────────────────────────────────────────────

(deftest splitting-reports-progress-in-a-sentence
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir)
                                 :phase :splitting
                                 :progress {:rows-done 2 :files-done 1 :current-name "people_0002.csv"}))]
      (is (text-containing d #"Splitting people.csv"))
      (is (text-containing d #"File 2 · 2 of 4 rows"))
      (is (= 0.5 (:progress (first (of-type d :progress-bar)))))
      (is (contains? (actions d) ::state/cancel-requested))
      (is (not (contains? (actions d) ::state/split-requested))
          "there is no way to start a second split on top of this one"))))

(deftest an-estimate-appears-once-there-is-evidence-for-one
  (let [ctx (:i18n state/initial)]
    (is (nil? (view/eta-text ctx {:rows-done 0 :elapsed-ms 0} 100)))
    (is (nil? (view/eta-text ctx {:rows-done 10 :elapsed-ms 200} 100))
        "too early to guess")
    (is (str/starts-with? (view/eta-text ctx {:rows-done 50 :elapsed-ms 5000} 100) "about "))
    (is (nil? (view/eta-text ctx {:rows-done 100 :elapsed-ms 5000} 100))
        "nothing left to wait for")))

(deftest the-whole-window-changes-language
  (testing "nothing in the view is written in English; it all comes from the
            translation files"
    (let [german (state/with-language state/initial "de")]
      (is (text-containing (view/content german) #"CSV-Datei hierher ziehen"))
      (is (not (text-containing (view/content german) #"Drop a CSV file here"))))))

(deftest the-overlays-are-reachable-and-render
  (let [d (view/content state/initial)]
    (is (contains? (actions d) ::state/about-toggled))
    (is (contains? (actions d) ::state/help-toggled)))
  (testing "About holds the version, the language picker and the theme choice"
    (let [d (view/content (assoc state/initial :dialog :about))]
      (is (text-containing d #"Version"))
      (is (text-containing d #"Apache"))
      (is (text-containing d #"Match the system"))
      (is (= 1 (count (of-type d :choice-box))))
      (is (contains? (actions d) ::state/theme-changed))))
  (testing "Help answers the questions a non-expert actually asks"
    (let [d (view/content (assoc state/initial :dialog :help))]
      (is (text-containing d #"What is a header row"))
      (is (text-containing d #"1,048,576"))
      (is (contains? (actions d) ::state/dialog-closed)))))

(deftest presets-explain-themselves
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir))]
      (is (text-containing d #"Common sizes"))
      (is (text-containing d #"65,000 — old Excel limit"))
      (is (text-containing d #"1,048,576 — Excel maximum")))))

(deftest splitting-by-size-warns-when-it-caps-the-rows
  (testing "the conundrum the size mode creates: the user never chose a row
            count, so nothing would otherwise stop a file exceeding Excel"
    (tu/with-temp-dir [dir]
      (let [d (view/content (assoc (ready-state dir) :mode :bytes :size-text "500 MB"))]
        (is (text-containing d #"1,048,576 rows"))))))

;; ── Finished ────────────────────────────────────────────────────────────────

(deftest finishing-says-what-happened-and-offers-the-folder
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir)
                                 :phase :done
                                 :result {:files [:a :b] :elapsed-ms 6432
                                          :written [{:file (io/file "a_0001.csv") :rows 2}]}))]
      (is (text-containing d #"2 files created in 6.4 seconds"))
      (is (contains? (actions d) ::state/reveal-requested))
      (is (contains? (actions d) ::state/reset))
      (is (contains? (style-classes d) "success"))
      (testing "the log is one click away, not filling the window"
        (is (empty? (of-type d :text-area)))
        (is (text-containing d #"Show details"))))))

(deftest the-details-log-lists-every-file-when-opened
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir)
                                 :phase :done
                                 :details-open? true
                                 :result {:files [:a] :elapsed-ms 10
                                          :written [{:file (io/file "people_0001.csv") :rows 2}
                                                    {:file (io/file "people_0002.csv") :rows 2}]}))]
      (is (= 1 (count (of-type d :text-area))))
      (is (text-containing d #"people_0001.csv  —  2 rows")))))

(deftest a-cancelled-split-is-reported-as-a-warning-not-a-success
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir)
                                 :phase :done
                                 :result {:files [:a] :cancelled? true :written []}))]
      (is (text-containing d #"Stopped"))
      (is (contains? (style-classes d) "warning")))))

;; ── Name clash ──────────────────────────────────────────────────────────────

(deftest the-clash-dialog-leads-with-the-safe-choice
  (tu/with-temp-dir [dir]
    (let [files (mapv #(io/file dir (format "people_%04d.csv" %)) (range 1 7))
          d     (view/content (assoc (ready-state dir) :dialog :collisions :collisions files))]
      (is (text-containing d #"6 files would be replaced"))
      (is (text-containing d #"Nothing has been written yet"))
      (testing "the list is truncated rather than filling the screen"
        (is (text-containing d #"and 2 more")))
      (testing "replacing is offered, but it is the dangerous-looking one"
        (is (contains? (style-classes d) "danger")))
      (is (contains? (actions d) ::view/new-folder-requested))
      (is (contains? (actions d) ::state/collision-resolved)))))

(deftest one-clashing-file-is-described-in-the-singular
  (tu/with-temp-dir [dir]
    (is (text-containing (view/content (assoc (ready-state dir)
                                              :dialog :collisions
                                              :collisions [(io/file dir "people_0001.csv")]))
                         #"One file would be replaced"))))
