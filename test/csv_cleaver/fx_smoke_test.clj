(ns csv-cleaver.fx-smoke-test
  "Builds the real JavaFX object graph from each view description.

   The pure view tests prove the descriptions say the right thing; they cannot
   prove cljfx can turn them into widgets. A misspelled property — :text-overun
   for :text-overrun, :prompt for :prompt-text — is invisible to a test that
   only reads maps, and shows up as an exception the first time a user reaches
   that screen. These tests are the ones that catch it.

   They need a JavaFX toolkit. On a headless CI machine run them under xvfb."
  (:require
   [cljfx.api :as fx]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.app :as app]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.desktop]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.macos]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.state :as state]
   [csv-cleaver.test-util :as tu]
   [csv-cleaver.view :as view])
  (:import
   (java.io File)
   (javafx.scene Node)))

(defn materialise
  "Build `description` into live JavaFX objects on the FX thread and return the
   root instance."
  [description]
  (let [component @(fx/on-fx-thread (fx/create-component description))]
    (fx/instance component)))

(defn- ready-state [dir & [content]]
  (-> state/initial
      (state/apply-event
       {:event/type ::state/scan-succeeded
        :survey (scan/survey (tu/write-file dir "people.csv"
                                            (or content "id,name\n1,Ann\n2,Bob\n3,Cy\n")))})
      (assoc :rows-text "2")))

(deftest ^:fx every-screen-builds-real-widgets
  (tu/with-temp-dir [dir]
    (let [ready (ready-state dir)
          screens
          {"empty"        state/initial
           "dragging"     (assoc state/initial :drag-over? true)
           "error"        (assoc state/initial :error "Nope.")
           "scanning"     (assoc state/initial :phase :scanning
                                 :file (File. "big.csv") :scan-rows 40000)
           "ready"        ready
           "damaged"      (ready-state dir "a,b,c\n1,2,3\n4,5\n")
           "by size"      (assoc ready :mode :bytes)
           "bad number"   (assoc ready :rows-text "abc")
           "advanced"     (assoc ready :advanced-open? true)
           "bad pattern"  (assoc ready :advanced-open? true :template "{name}")
           "splitting"    (assoc ready :phase :splitting
                                 :progress {:rows-done 2 :files-done 1
                                            :current-name "people_0002.csv"
                                            :elapsed-ms 3000})
           "done"         (assoc ready :phase :ready
                                 :result {:files [:a :b] :elapsed-ms 6432
                                          :written [{:file (File. "people_0001.csv") :rows 2}]})
           "details open" (assoc ready :phase :ready :details-open? true
                                 :result {:files [:a] :elapsed-ms 10
                                          :written [{:file (File. "people_0001.csv") :rows 2}]})
           "cancelled"    (assoc ready :phase :ready
                                 :result {:files [] :cancelled? true :written []})
           "name clash"   (assoc ready :dialog :collisions
                                 :collisions (mapv #(File. dir (format "people_%04d.csv" %))
                                                   (range 1 7)))}]
      (doseq [[label screen] screens]
        (testing label
          (is (instance? Node (materialise (view/content screen)))))))))

(deftest ^:fx the-stylesheet-is-on-the-classpath-and-loads
  (testing "a missing stylesheet leaves the window unstyled rather than
            failing, so assert it is genuinely found"
    (let [sheets (branding/stylesheets)]
      (is (seq sheets))
      (is (some #(re-find #"app\.css$" %) sheets))
      (is (some? (slurp (first sheets)))))))

(deftest ^:fx dragging-does-not-destroy-the-theme
  (testing "the regression that made the whole window lose its colours: cljfx
            replaces the style-class list when it changes, and JavaFX had put
            \"root\" in that same list, so the first drag deleted it"
    @(fx/on-fx-thread
      (let [idle     (:scene (view/root state/initial))
            dragging (:scene (view/root (assoc state/initial :drag-over? true)))
            classes  (fn [component]
                       (set (.getStyleClass (.getRoot ^javafx.scene.Scene
                                             (fx/instance component)))))
            c0       (fx/create-component idle)
            c1       (fx/advance-component c0 dragging)
            c2       (fx/advance-component c1 idle)]
        (is (contains? (classes c0) "root") "before the drag")
        (is (contains? (classes c1) "root") "during it")
        (is (contains? (classes c2) "root") "and after it")))))

(deftest ^:fx the-scene-accepts-the-stylesheets
  (let [scene (materialise {:fx/type     :scene
                            :stylesheets (branding/stylesheets)
                            :root        (view/content state/initial)})]
    (is (pos? (count (.getStylesheets scene))))))

(deftest ^:fx every-language-builds-real-widgets
  (testing "a translation long enough to break a layout, or a missing plural
            form, only shows up once the widgets exist"
    (tu/with-temp-dir [dir]
      (doseq [tag i18n/supported]
        (testing tag
          (let [translated (state/with-language (ready-state dir) tag)]
            (is (instance? Node (materialise (view/content translated))))
            (is (instance? Node (materialise (view/content (assoc translated :dialog :about)))))
            (is (instance? Node (materialise (view/content (assoc translated :dialog :help)))))))))))

;; ── Text that never made it into a translation ──────────────────────────────

(defn laid-out
  "A materialised screen, in a scene and through a layout pass, so that skins
   exist and every control reports the size and text it really has."
  ^javafx.scene.Parent [description]
  (let [root (materialise description)]
    @(fx/on-fx-thread
      (javafx.scene.Scene. root 1100 950)
      (doto ^javafx.scene.Parent root (.applyCss) (.layout)))
    root))

(defn every-string
  "Every string the window shows, including the contents of pickers — which is
   where the untranslated one was hiding, since a Labeled walk never sees the
   items of a ChoiceBox."
  [root]
  (let [acc (atom [])]
    (letfn [(visit [x]
              (when (instance? javafx.scene.control.Labeled x)
                (some->> (.getText ^javafx.scene.control.Labeled x) (swap! acc conj)))
              (when (instance? javafx.scene.control.TextInputControl x)
                (some->> (.getText ^javafx.scene.control.TextInputControl x)
                         (swap! acc conj)))
              (when (instance? javafx.scene.control.ChoiceBox x)
                (swap! acc into (map str (.getItems ^javafx.scene.control.ChoiceBox x)))
                (some->> (.getValue ^javafx.scene.control.ChoiceBox x) str (swap! acc conj)))
              (when (instance? javafx.scene.control.ScrollPane x)
                (some-> (.getContent ^javafx.scene.control.ScrollPane x) visit))
              (when (instance? javafx.scene.Parent x)
                (doseq [c (.getChildrenUnmodifiable ^javafx.scene.Parent x)] (visit c))))]
      (visit root))
    (distinct @acc)))

(defn may-be-identical-to-english?
  "Whether a string is allowed to read the same in every language.

   Rules rather than a list of strings. A list is where the next untranslated
   label would be quietly parked, which is the thing this test exists to find.
   Each clause is a reason something genuinely has no translation: it is not
   words, it is the name of a thing rather than a word, or it came out of the
   user's own file."
  [^File file content s]
  (or (str/blank? s)
      ;; Digits and punctuation, and the single-character icon buttons.
      (not (re-find #"\p{L}" s))
      (<= (count (str/trim s)) 1)
      ;; "UTF-8" is not an English word, and translating it would name an
      ;; encoding that does not exist.
      (some #(= s %) (rest state/selectable-charsets))
      ;; The file the user chose, and where it lives.
      (str/includes? s (.getName file))
      (str/includes? s (.getAbsolutePath (.getParentFile file)))
      ;; The output name pattern is a pattern, not prose.
      (str/includes? s naming/default-template)
      ;; Anything whose every word came out of the file being split — the
      ;; preview of the first rows is the user's data, not our wording.
      (every? #(str/includes? content %) (re-seq #"\p{L}+" s))))

(defn phrase-of?
  "Whether `s` could have been produced by one of `tag`'s own translations.

   This is the question that actually matters, and it took two wrong answers to
   get to. Looking for text that matches English flags 25 bytes in Spanish and
   Text: UTF-8 in German, both of which are exactly what those languages really
   say. What distinguishes a leak is not that the words match English but that
   they came from nowhere: no phrase in that language's own bundle can produce
   them.

   Placeholders are matched loosely, so a template ending in bytes accepts a
   rendering ending in bytes.

   Templates that are nothing but a placeholder are excluded, and that omission
   is the whole reason this docstring is long. :preset/plain is \"{0}\", which
   became the pattern .* and therefore vouched for every string in the window —
   so the test passed while three deliberately untranslated labels sat in front
   of it. It was a precise-looking test that accepted anything, and only
   breaking the code on purpose showed it up."
  [tag s]
  (let [words-of   (fn [template] (str/split (str template) #"\{\d+\}" -1))
        pattern-of (fn [template]
                     (->> (words-of template)
                          (map #(java.util.regex.Pattern/quote %))
                          (str/join ".*")
                          (str "(?s)")
                          (re-pattern)))]
    (boolean (some #(and (some (complement str/blank?) (words-of %))
                         (re-matches (pattern-of %) s))
                   (vals (:strings (i18n/context tag)))))))

(deftest ^:fx a-phrase-check-that-accepts-everything-would-hide-everything
  (testing "the guard on the guard. :preset/plain is \"{0}\", and taken as a
            pattern it matches any string at all — which silently turned the
            whole sweep into a test that could not fail."
    (is (not (phrase-of? "es" "Detected"))
        "a string no Spanish phrase can produce")
    (is (not (phrase-of? "es" "Split into")))
    (is (phrase-of? "es" "25 bytes") "Spanish really does say bytes")
    (is (phrase-of? "de" "Text: UTF-8") "and German really does say Text")))

(deftest ^:fx no-english-survives-into-a-translated-window
  (testing "the encoding picker offered \"Detected\" in every language, because
            its items were a plain vector of strings while the separator picker
            translated its own. A walk over labels never saw it: the text was in
            the items of a ChoiceBox, not in any Labeled.

            So this compares whole windows rather than checking a list of
            controls somebody remembered to add."
    (tu/with-temp-dir [dir]
      ;; Advanced open: the pickers live behind it, and the untranslated string
      ;; was inside one of them.
      (let [content "id,name\n1,Ann\n2,Bob\n3,Cy\n"
            state   (assoc (ready-state dir) :advanced-open? true)
            ^File file (get-in state [:survey :file])
            english (set (every-string (laid-out (view/content
                                                  (state/with-language state "en")))))]
        (doseq [tag (remove #{"en"} i18n/supported)]
          (testing tag
            (let [translated (state/with-language state tag)
                  leaked     (->> (every-string (laid-out (view/content translated)))
                                  (filter english)
                                  (remove #(may-be-identical-to-english? file content %))
                                  (remove #(phrase-of? tag %))
                                  (distinct)
                                  (sort))]
              (is (empty? leaked)
                  (str tag " shows English: " (pr-str leaked))))))))))

(deftest ^:fx the-english-comparison-is-not-empty
  (testing "a sweep that collects nothing finds nothing wrong with everything.
            This nearly happened: an earlier version walked children only, never
            reached inside the ScrollPane, and reported a perfectly clean window
            with no text in it at all."
    (tu/with-temp-dir [dir]
      (let [strings (every-string
                     (laid-out (view/content (assoc (ready-state dir)
                                                    :advanced-open? true))))]
        (is (< 20 (count strings))
            "the whole ready screen is more than twenty strings")
        (is (some #{"UTF-8"} strings)
            "including the items inside a picker, which is where the
             untranslated string was and where a Labeled-only walk stops")
        (is (some #{naming/default-template} strings)
            "and the name pattern, which lives behind Advanced")))))

(deftest ^:fx a-picker-keeps-its-width-when-the-language-changes
  (testing "left to size themselves, the Advanced pickers measured their widest
            item when the skin was built and never again. Building each language
            fresh does not show this — every one measures correctly on its own.
            It only appears when one live window changes language, which is what
            a user does and what this test does."
    (tu/with-temp-dir [dir]
      (let [opts    {:fx.opt/map-event-handler (fn [_])}
            state   (assoc (ready-state dir) :advanced-open? true)
            choice-widths
            (fn [root]
              (let [acc (atom [])]
                (letfn [(visit [x]
                          (when (instance? javafx.scene.control.ChoiceBox x)
                            (swap! acc conj (.getWidth ^javafx.scene.control.ChoiceBox x)))
                          (when (instance? javafx.scene.control.ScrollPane x)
                            (some-> (.getContent ^javafx.scene.control.ScrollPane x) visit))
                          (when (instance? javafx.scene.Parent x)
                            (doseq [c (.getChildrenUnmodifiable ^javafx.scene.Parent x)]
                              (visit c))))]
                  (visit root))
                @acc))]
        @(fx/on-fx-thread
          (let [start (state/with-language state "en")
                built (fx/create-component (view/content start) opts)]
            (javafx.scene.Scene. ^javafx.scene.Parent (fx/instance built) 1100 950)
            (doto ^javafx.scene.Parent (fx/instance built) (.applyCss) (.layout))
            (let [before (choice-widths (fx/instance built))]
              (is (seq before) "there are pickers to measure")
              (doseq [tag (remove #{"en"} i18n/supported)]
                (let [changed (state/with-language start tag)
                      updated (fx/advance-component built (view/content changed) opts)]
                  (doto ^javafx.scene.Parent (fx/instance updated) (.applyCss) (.layout))
                  (is (= before (choice-widths (fx/instance updated)))
                      (str "changing to " tag " changed a picker's width")))))))))))

(deftest ^:fx the-window-geometry-is-read-from-the-real-stage
  (testing "R55/R58: position and size are gathered at close from the live
            Stage. Tested against a real one — never shown — because the getters
            are exactly the kind of wiring a rename breaks silently."
    (let [stage @(fx/on-fx-thread
                  (doto (javafx.stage.Stage.)
                    (.setX 40.0) (.setY 60.0)
                    (.setWidth 800.0) (.setHeight 700.0)))
          saved ((requiring-resolve 'csv-cleaver.app/session-settings)
                 state/initial stage)]
      (is (= {:x 40.0 :y 60.0 :width 800.0 :height 700.0} (:window saved)))
      (testing "and what was saved is what the next window asks for"
        (is (= {:x 40.0 :y 60.0 :width 800.0 :height 700.0}
               (view/remembered-window (:window saved))))))))

(deftest ^:fx a-dialog-never-outgrows-the-window
  (testing "a resized window could be made smaller than the About card, which
            then overflowed it on every side. The card now caps its width and
            scrolls its middle, so however small the window, the card fits and
            Close stays reachable."
    (tu/with-temp-dir [dir]
      (doseq [dialog [:about :help]
              [w h]  [[420 320] [1000 900]]]
        (let [st   (assoc (ready-state dir) :dialog dialog)
              root (fx/instance (fx/create-component
                                 {:fx/type :stack-pane
                                  :stylesheets (branding/stylesheets)
                                  :children [(view/content st)]}
                                 {:fx.opt/map-event-handler (fn [_])}))]
          @(fx/on-fx-thread
            (javafx.scene.Scene. ^javafx.scene.Parent root (double w) (double h))
            (doto ^javafx.scene.Parent root (.applyCss) (.layout)))
          ;; layoutBounds, not boundsInLocal: the latter includes the card's
          ;; drop shadow, which is meant to bleed past the card. The first
          ;; version of this test measured the shadow and failed a fix that
          ;; was working.
          (let [card (.lookup ^javafx.scene.Parent root ".dialog-card")]
            (is (some? card) (str (name dialog) " " w "x" h))
            (is (<= (.getWidth (.getLayoutBounds card)) (- w 16))
                (str (name dialog) " card wider than a " w "px window allows"))
            (is (<= (.getHeight (.getLayoutBounds card)) (- h 16))
                (str (name dialog) " card taller than a " h "px window allows"))))))))

(deftest ^:fx the-scene-itself-listens-for-modifier-keys
  (testing "key events only reach nodes that have focus, and the root pane
            never does — tracking there was tracking nothing. The scene is
            where events from any focused child bubble to, so the handlers
            must sit on the scene. This drives a real KeyEvent through the
            real scene built by view/root."
    (let [seen  (atom [])
          scene-desc (:scene (view/root state/initial))
          scene @(fx/on-fx-thread
                  (fx/instance
                   (fx/create-component scene-desc
                                        {:fx.opt/map-event-handler
                                         (fn [e] (swap! seen conj (:event/type e)))})))]
      (is (instance? javafx.scene.Scene scene))
      @(fx/on-fx-thread
        (javafx.event.Event/fireEvent
         scene
         (javafx.scene.input.KeyEvent.
          javafx.scene.input.KeyEvent/KEY_PRESSED
          "" "" javafx.scene.input.KeyCode/ALT false false true false)))
      (is (some #{:csv-cleaver.view/modifier-keys} @seen)
          "the scene handler fired for a key event"))))

(deftest ^:fx the-about-item-really-sits-in-the-macos-application-menu
  ;; The claim this test guards was once made falsely, so it holds the ground
  ;; truth: the title is read back FROM AppKit, and the click is dispatched BY
  ;; AppKit (performActionForItemAtIndex:), not by calling our handler.
  (if-not (= :mac (csv-cleaver.desktop/os))
    (println "skipped: the macOS application menu only exists on macOS")
    (do
      (reset! app/*state state/initial)
      (testing "installed, and titled in the window's language"
        (is (true? @(fx/on-fx-thread (app/install-native-about!))))
        (is (= "About CSV Cleaver"
               @(fx/on-fx-thread (csv-cleaver.macos/about-item-title)))
            "AppKit's own answer, not our intention"))
      (testing "a language change retitles the native item"
        (reset! app/*state (state/with-language state/initial "es"))
        @(fx/on-fx-thread (app/install-native-about!))
        (is (= "Acerca de CSV Cleaver"
               @(fx/on-fx-thread (csv-cleaver.macos/about-item-title)))))
      (testing "AppKit's click dispatch opens the About overlay"
        (reset! app/*state state/initial)
        @(fx/on-fx-thread (app/install-native-about!))
        (is (true? @(fx/on-fx-thread (csv-cleaver.macos/perform-about-item!))))
        (is (= :about (:dialog @app/*state))
            "the click travelled AppKit → ObjC target → JNA → dispatch")))))
