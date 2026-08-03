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

(deftest the-window-reopens-where-it-was-left
  (is (= {:width 900.0 :height 700.0 :x 100.0 :y 50.0}
         (view/remembered-window {:width 900 :height 700 :x 100 :y 50})))
  (testing "a size saved on a monitor no longer attached, or from a corrupted
            settings file, is ignored rather than obeyed"
    (is (= {} (view/remembered-window {:width 10 :height 5})))
    (is (= {} (view/remembered-window {:width 99999 :height 99999})))
    (is (= {} (view/remembered-window {:width "wide" :height nil})))
    (is (= {} (view/remembered-window nil))))
  (testing "and a remembered size reaches the stage"
    (is (= 900.0 (:width (view/root (assoc state/initial
                                           :window {:width 900 :height 700})))))
    (is (= 720 (:width (view/root state/initial))) "or the default")))

(deftest the-menu-bar-takes-no-room-where-the-system-provides-one
  (testing "the empty ten-pixel strip under the macOS title bar: the menus move
            to the system bar but the node still reserved layout space"
    (is (false? (:managed (view/menu-bar (:i18n state/initial) true))))
    (is (true? (:managed (view/menu-bar (:i18n state/initial) false)))
        "on Windows and Linux it is the actual menu bar and must be laid out")))

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

(deftest the-separator-is-always-reported
  (testing "including a comma. Naming it only when unusual left the commonest
            case as the one the user could not check, which is backwards from
            how the encoding is reported."
    (tu/with-temp-dir [dir]
      (is (text-containing (view/content (ready-state dir "id;name\n1;Ann\n2;Bob\n"))
                           #"Separated by semicolons"))
      (is (text-containing (view/content (ready-state dir)) #"Separated by commas")))))

(deftest the-first-rows-are-shown-so-the-parse-can-be-checked
  (testing "a claim about the separator and the header is worth little if the
            user cannot see what it produced"
    (tu/with-temp-dir [dir]
      (let [d (view/content (ready-state dir))]
        (is (text-containing d #"First rows"))
        (is (text-containing d #"id   ·   name"))
        (is (text-containing d #"1   ·   Ann"))))))

(deftest the-outcome-appears-above-the-options-not-instead-of-them
  (testing "the finished screen used to be a dead end: the only way on was to
            start again with another file"
    (tu/with-temp-dir [dir]
      (let [d (view/content (assoc (ready-state dir)
                                   :result {:files [:a :b] :elapsed-ms 6432 :written []}))]
        (is (text-containing d #"2 files created"))
        (is (text-containing d #"Split into") "the options are still here")
        (is (text-containing d #"rows in each file"))
        (testing "and splitting again is one press, with the extra actions beside it"
          (is (contains? (actions d) ::state/split-requested))
          (is (contains? (actions d) ::state/reveal-requested))
          (is (contains? (actions d) ::state/reset)))))))

(deftest before-a-split-the-footer-holds-only-the-primary-action
  (tu/with-temp-dir [dir]
    (let [d (view/content (ready-state dir))]
      (is (contains? (actions d) ::state/split-requested))
      (is (not (contains? (actions d) ::state/reveal-requested))
          "nothing to reveal yet"))))

(deftest the-scene-root-always-keeps-its-root-class
  (testing "every AtlantaFX colour is defined on .root. This list is recomputed
            when a drag starts, and cljfx replaces the whole style-class list
            when it changes, so omitting \"root\" destroyed it the first time
            anyone dragged a file in — after which nothing resolved, backgrounds
            vanished and text fell back to black for the rest of the session."
    (tu/with-temp-dir [dir]
      (doseq [[label st] {"idle"     state/initial
                          "dragging" (assoc state/initial :drag-over? true)
                          "ready"    (ready-state dir)
                          "dragging over an open file"
                          (assoc (ready-state dir) :drag-over? true)}]
        (testing label
          (is (contains? (set (:style-class (view/content st))) "root")))))))

(deftest headings-state-their-own-colour
  (testing "a bold label that leaves the fill to be inherited comes out black,
            which is invisible on a dark card"
    (let [styled? (fn [d] (every? #(contains? (set (:style-class %)) "label")
                                  (filter #(contains? (set (:style-class %)) "title")
                                          (nodes d))))]
      (is (styled? (view/content (assoc state/initial :dialog :about))))
      (is (styled? (view/startup-error-window {:problems ["x"]}))))))

(deftest a-file-can-be-dropped-anywhere-at-any-time
  (testing "the handlers are on the window, not on the empty-state target, so a
            second file does not have to go through the Browse dialog"
    (tu/with-temp-dir [dir]
      (doseq [[label st] {"empty" state/initial
                          "ready" (ready-state dir)}]
        (testing label
          (let [d (view/content st)]
            (is (= ::view/drag-dropped (:event/type (:on-drag-dropped d))))
            (is (= ::view/drag-over (:event/type (:on-drag-over d))))))))
    (testing "and the whole window acknowledges the drag"
      (is (contains? (set (:style-class (view/content (assoc state/initial :drag-over? true))))
                     "active")))))

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

(deftest quitting-lives-where-people-look-for-it
  (testing "in the File menu, which is both findable and impossible to brush
            past. It was in the About dialog, which met the requirement that it
            not be easy to press by accident and failed the requirement that it
            be findable at all."
    (let [d    (view/content state/initial)
          quit (first (filter #(= ::state/quit-requested (:event/type (:on-action %)))
                              (nodes d)))]
      (is (= :menu-item (:fx/type quit)) "a menu item, not a button")
      (is (= [:shortcut :q] (:accelerator quit)) "with the platform shortcut")
      (is (empty? (filter #(and (= :button (:fx/type %))
                                (= ::state/quit-requested (:event/type (:on-action %))))
                          (nodes d)))
          "and no button anywhere that could be hit on the way to Split"))))

(deftest the-menu-bar-offers-the-conventional-things
  (let [d     (view/content state/initial)
        menus (of-type d :menu)]
    (is (= ["File" "Help"] (mapv :text menus)))
    (is (:use-system-menu-bar (first (of-type d :menu-bar)))
        "so macOS puts it in the system bar where it belongs")
    (testing "Help reaches the same two overlays as the icon buttons"
      (let [items (mapcat :items menus)
            types (set (keep #(:event/type (:on-action %)) items))]
        (is (contains? types ::state/help-toggled))
        (is (contains? types ::state/about-toggled))))
    (testing "and the icon buttons remain as the quick route"
      (is (some #(and (= :button (:fx/type %))
                      (= ::state/about-toggled (:event/type (:on-action %))))
                (nodes d))))))

(deftest the-menus-are-translated-like-everything-else
  (let [german (view/content (state/with-language state/initial "de"))]
    (is (= ["Datei" "Hilfe"] (mapv :text (of-type german :menu))))
    (is (some #(= "Beenden" (:text %)) (of-type german :menu-item)))))

(deftest the-startup-error-window-explains-itself-in-english
  (testing "the translations are the thing that is broken, so none of them can
            be trusted to describe the breakage"
    (let [d (view/startup-error-window {:problems ["it → :action/cancel: contains control characters"
                                                   "pt.edn: the file could not be read"]})]
      (is (= :stage (:fx/type d)))
      (is (text-containing d #"A translation could not be used"))
      (is (text-containing d #"no data is at risk"))
      (is (text-containing d #"contains control characters"))
      (testing "and offers both ways out, so a bad file cannot brick the application"
        (is (contains? (actions d) ::view/quit-requested))
        (is (contains? (actions d) ::view/continue-in-english))))))

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
                                 :phase :ready
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
                                 :phase :ready
                                 :details-open? true
                                 :result {:files [:a] :elapsed-ms 10
                                          :written [{:file (io/file "people_0001.csv") :rows 2}
                                                    {:file (io/file "people_0002.csv") :rows 2}]}))]
      (is (= 1 (count (of-type d :text-area))))
      (is (text-containing d #"people_0001.csv  —  2 rows")))))

(deftest a-cancelled-split-is-reported-as-a-warning-not-a-success
  (tu/with-temp-dir [dir]
    (let [d (view/content (assoc (ready-state dir)
                                 :phase :ready
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
