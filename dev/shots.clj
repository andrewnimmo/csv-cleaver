(ns shots
  "Renders every screen to a PNG, in both themes, without anyone having to look
   at a running window.

   Run with:  clj -M:shots

   This snapshots the real scene graph with the real AtlantaFX stylesheet
   applied, so what comes out is what a user would see — useful for reviewing a
   design change in a pull request, and it works on a headless CI machine where
   nobody can take a screenshot at all."
  (:require
   [cljfx.api :as fx]
   [clojure.java.io :as io]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.state :as state]
   [csv-cleaver.text :as text]
   [csv-cleaver.view :as view])
  (:import
   (atlantafx.base.theme PrimerDark PrimerLight)
   (java.awt.image BufferedImage)
   (java.io File)
   (javafx.application Application Platform)
   (javafx.scene Scene)
   (javafx.scene.image WritableImage WritablePixelFormat)
   (javafx.stage Stage)
   (javax.imageio ImageIO)))

(def width 720)
(def height 660)
(def out-dir (io/file "target/shots"))

(defn- write-png!
  [image ^File file]
  (let [w  (int (.getWidth image))
        h  (int (.getHeight image))
        bi (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        px (int-array (* w h))]
    (.getPixels (.getPixelReader image) 0 0 w h
                (WritablePixelFormat/getIntArgbInstance)
                px 0 w)
    (.setRGB bi 0 0 w h px 0 w)
    (io/make-parents file)
    (ImageIO/write bi "png" file)))

(defn- snapshot!
  "Render one state and write it out. The stage is shown fully transparent and
   parked off to one side, because CSS and layout only run for a scene that
   belongs to a shown window — and nobody wants windows flashing past."
  [label theme app-state]
  (let [node   (fx/instance (fx/create-component (view/content app-state)))
        scene  (Scene. node width height)
        stage  (doto (Stage.)
                 (.setScene scene)
                 (.setOpacity 0.0)
                 (.setX -30000.0)
                 (.show))]
    (Application/setUserAgentStylesheet
     (.getUserAgentStylesheet (if (= theme :dark) (PrimerDark.) (PrimerLight.))))
    (.addAll (.getStylesheets scene) ^java.util.Collection (branding/stylesheets))
    (.applyCss node)
    (.layout node)
    (let [file (io/file out-dir (str label "-" (name theme) ".png"))
          ;; The one-argument form is synchronous and returns the image; the
          ;; two-argument form takes a callback and returns nothing. A typed
          ;; local is how you pick the right one, since nil cannot carry a hint.
          ^WritableImage into-a-new-image nil]
      (write-png! (.snapshot scene into-a-new-image) file)
      (println "wrote" (.getPath file)))
    (.hide stage)))

(defn- sample-survey [dir filename content]
  (let [f (io/file dir filename)]
    (io/make-parents f)
    (spit f content)
    (scan/survey f)))

(defn screens
  [dir]
  (let [healthy (sample-survey dir "customers-2024.csv"
                               (apply str "id,name,city,total\n"
                                      (for [i (range 1 1204)]
                                        (str i ",Person " i ",Town,12.50\n"))))
        damaged (sample-survey dir "exports.csv" "a,b,c\n1,2,3\n4,5\n6,7,8\n")
        ready   (-> state/initial
                    (state/apply-event {:event/type ::state/scan-succeeded :survey healthy})
                    (assoc :rows-text "65,000" :out-dir dir))
        ready   (assoc ready :rows-text "500")]
    [["1-empty"     state/initial]
     ["2-ready"     ready]
     ["3-damaged"   (-> state/initial
                        (state/apply-event {:event/type ::state/scan-succeeded :survey damaged})
                        (assoc :rows-text "1" :out-dir dir))]
     ["4-advanced"  (assoc ready :advanced-open? true)]
     ["5-splitting" (assoc ready :phase :splitting
                           :progress {:rows-done 758 :files-done 1
                                      :current-name "customers-2024_0002.csv"
                                      :elapsed-ms 4200})]
     ["6-done"      (assoc ready :phase :ready :details-open? true
                           :result {:files [:a :b :c] :elapsed-ms 6432
                                    :written [{:file (io/file "customers-2024_0001.csv") :rows 500}
                                              {:file (io/file "customers-2024_0002.csv") :rows 500}
                                              {:file (io/file "customers-2024_0003.csv") :rows 203}]})]
     ["7-clash"     (assoc ready :dialog :collisions
                           :collisions (mapv #(io/file dir (text/fmt "customers-2024_%04d.csv" %))
                                             (range 1 7)))]
     ["8-about"     (assoc ready :dialog :about)]
     ["9-help"      (assoc ready :dialog :help)]
     ["10-header-question"
      (let [ambiguous (sample-survey dir "surnames.csv" "surname\nAnderson\nBrown\nClark\nDavies\n")]
        (-> state/initial
            (state/apply-event {:event/type ::state/scan-succeeded :survey ambiguous})
            (assoc :rows-text "2" :out-dir dir)))]]))

(defn -main [& _]
  (let [dir (io/file "target/shot-data")]
    @(fx/on-fx-thread
      (doseq [theme [:light :dark]
              [label app-state] (screens dir)]
        (snapshot! label theme app-state)))
    (println "\nWrote" (count (.listFiles out-dir)) "images to" (.getPath out-dir))
    (Platform/exit)
    (shutdown-agents)
    (System/exit 0)))
