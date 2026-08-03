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
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.i18n :as i18n]
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
