(ns csv-cleaver.app
  "Wiring: the only namespace that knows about JavaFX objects, threads, the
   file system and the clock.

   csv-cleaver.state decides what should happen and returns effects as data;
   this namespace is the small, deliberately dull layer that carries them out.
   Everything genuinely interesting has been pushed out of here so that it can
   be tested without a display."
  (:require
   [cljfx.api :as fx]
   [clojure.string :as str]
   [csv-cleaver.cli :as cli]
   [csv-cleaver.desktop :as desktop]
   [csv-cleaver.files :as files]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.prefs :as prefs]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.split :as split]
   [csv-cleaver.state :as state]
   [csv-cleaver.view :as view])
  (:import
   (atlantafx.base.theme PrimerDark PrimerLight)
   (javafx.beans.value ChangeListener)
   (java.io File)
   (java.text SimpleDateFormat)
   (java.util Date)
   (javafx.application Application Platform)
   (javafx.scene.input DragEvent TransferMode)
   (javafx.stage DirectoryChooser FileChooser FileChooser$ExtensionFilter Stage Window))
  (:gen-class))

(defonce *state (atom state/initial))

(defonce cancel-flag (atom false))

(declare dispatch!)

;; ── Background work ─────────────────────────────────────────────────────────

(defn run-async!
  "Run `f` on a daemon thread, so a split in flight can never stop the
   application from closing."
  [f]
  (doto (Thread. ^Runnable f "csv-cleaver-worker")
    (.setDaemon true)
    (.start)))

(defn friendly-message
  "An exception as something the person using the application can act on, and
   in their own language: a translation key where we recognise the failure, and
   the system's own words only where we do not."
  [^Exception e]
  (or (:message (ex-data e))
      (let [m (or (.getMessage e) "")]
        (cond
          (re-find #"(?i)permission denied|access is denied" m) {:key :problem/no-permission}
          (re-find #"(?i)no space left|not enough space" m)     {:key :problem/disk-full}
          (re-find #"(?i)no such file|cannot find" m)           {:key :problem/file-moved}
          (seq m)                                               {:text m}
          :else                                                 {:key :problem/generic}))))

;; ── Workers ─────────────────────────────────────────────────────────────────
;;
;; The body of each long-running effect, with the thread and the JavaFX thread
;; hop lifted out. Each takes a `dispatch` function and reports through it, so a
;; test can pass one that records into a vector and check exactly what the
;; application would have been told — no threads, no display, no waiting.

(defn scan-worker
  [{:keys [^File file delimiter]} dispatch]
  (try
    (let [survey (scan/survey file {:delimiter delimiter
                                    :on-progress
                                    (fn [rows]
                                      (dispatch {:event/type ::state/scan-progress
                                                 :rows rows}))})]
      (dispatch {:event/type ::state/scan-succeeded :survey survey}))
    (catch Exception e
      (dispatch {:event/type ::state/scan-failed :message (friendly-message e)}))))

(defn collision-worker
  [request dispatch]
  (try
    (let [existing (split/collisions request)]
      (dispatch (if (seq existing)
                  {:event/type ::state/collisions-found :files existing}
                  {:event/type ::state/start-split})))
    (catch Exception e
      (dispatch {:event/type ::state/split-failed :message (friendly-message e)}))))

(defn split-worker
  [request cancelled? dispatch]
  (try
    (let [result (split/execute!
                  (assoc request
                         :cancelled?  cancelled?
                         :on-progress (fn [progress]
                                        (dispatch {:event/type ::state/split-progress
                                                   :progress progress}))))]
      (dispatch {:event/type ::state/split-succeeded :result result}))
    (catch Exception e
      (dispatch {:event/type ::state/split-failed :message (friendly-message e)}))))

;; ── Effects ─────────────────────────────────────────────────────────────────

(defmulti perform!
  "Carry out one effect returned by csv-cleaver.state/handle."
  (fn [[kind _]] kind))

(defmethod perform! :default [_] nil)

(defmethod perform! :scan
  [[_ request]]
  (run-async! #(scan-worker request dispatch!)))

(defmethod perform! :inspect-out-dir
  [[_ dir]]
  (run-async! #(dispatch! {:event/type ::state/out-dir-inspected
                           :dir        dir
                           :info       (files/inspect-dir dir)})))

(defmethod perform! :check-collisions
  [[_ request]]
  (run-async! #(collision-worker request dispatch!)))

(defmethod perform! :split
  [[_ request]]
  (reset! cancel-flag false)
  (run-async! #(split-worker request (fn [] @cancel-flag) dispatch!)))

(defmethod perform! :cancel
  [_]
  (reset! cancel-flag true))

(def exit!
  "Ending the process, behind a var so that a test can watch the intent without
   the test run being ended along with it."
  (fn [status]
    (Platform/exit)
    (System/exit (int status))))

(defn primary-stage
  "The application's window, or nil before it exists."
  ^Stage []
  (first (filter #(instance? Stage %) (Window/getWindows))))

(defn session-settings
  "What is worth keeping for next time: the settings the user chose, and where
   they left the window.

   Gathered at the moment of closing rather than saved as each one changes.
   Typing into the rows box changes the state on every keystroke, and writing
   the settings file that often would be absurd."
  ([] (session-settings @*state (primary-stage)))
  ([state ^Stage stage]
   (cond-> (select-keys state prefs/remembered)
     stage (assoc :window {:x      (.getX stage)
                           :y      (.getY stage)
                           :width  (.getWidth stage)
                           :height (.getHeight stage)}))))

(defn save-session!
  []
  (prefs/save-prefs! (session-settings)))

(defmethod perform! :quit
  [_]
  (save-session!)
  (exit! 0))

(defmethod perform! :reveal
  [[_ dir]]
  (desktop/reveal! dir))

(defmethod perform! :save-prefs
  [[_ settings]]
  (run-async! (fn [] (prefs/save-prefs! settings))))

(defmethod perform! :choose-file
  [[_ {:keys [^File initial-dir]}]]
  (let [chooser (doto (FileChooser.)
                  (.setTitle "Choose a CSV file")
                  (-> .getExtensionFilters
                      (.add (FileChooser$ExtensionFilter.
                             "Comma separated values"
                             ^"[Ljava.lang.String;"
                             (into-array String ["*.csv" "*.CSV" "*.tsv" "*.txt"])))))]
    (when (and initial-dir (.isDirectory initial-dir))
      (.setInitialDirectory chooser initial-dir))
    (when-let [file (.showOpenDialog chooser nil)]
      (dispatch! {:event/type ::state/file-chosen :file file}))))

(defmethod perform! :choose-dir
  [[_ {:keys [^File initial-dir]}]]
  (let [chooser (doto (DirectoryChooser.)
                  (.setTitle "Choose where to save the files"))]
    (when (and initial-dir (.isDirectory initial-dir))
      (.setInitialDirectory chooser initial-dir))
    (when-let [dir (.showDialog chooser nil)]
      (dispatch! {:event/type ::state/out-dir-chosen :dir dir}))))

(defn system-dark?
  "Whether the operating system is currently set to a dark appearance. Falls
   back to light if this JavaFX build cannot say."
  []
  (try
    (= "DARK" (str (.getColorScheme (Platform/getPreferences))))
    (catch Throwable _ false)))

(defn theme-stylesheet
  [theme]
  (.getUserAgentStylesheet
   (case theme
     :dark  (PrimerDark.)
     :light (PrimerLight.)
     (if (system-dark?) (PrimerDark.) (PrimerLight.)))))

(defmethod perform! :apply-theme
  [[_ theme]]
  (fx/on-fx-thread (Application/setUserAgentStylesheet (theme-stylesheet theme))))

;; ── Events ──────────────────────────────────────────────────────────────────

(defn normalise
  "cljfx delivers a widget's new value as :fx/event. An event map that names a
   :event/value-key gets that value copied under the key the state namespace
   expects, which keeps csv-cleaver.state free of any cljfx convention."
  [event]
  (if-let [k (:event/value-key event)]
    (assoc event k (:fx/event event))
    event))

(defn timestamped-dir
  "A sibling folder to write into when the user declines to replace anything."
  ^File [^File out-dir ^File source]
  (let [stamp (.format (SimpleDateFormat. "yyyy-MM-dd HHmm") (Date.))]
    (File. out-dir (str (some-> source .getName (str/replace #"\.[^.]*$" ""))
                        " split " stamp))))

(defn- accept-drag!
  [^DragEvent event]
  (when (.hasFiles (.getDragboard event))
    (.acceptTransferModes event (into-array TransferMode [TransferMode/COPY]))
    ;; Only announce the first crossing: drag-over fires continuously while the
    ;; pointer moves, and re-rendering on every one of them makes the window
    ;; stutter.
    (when-not (:drag-over? @*state)
      (dispatch! {:event/type ::state/drag-entered})))
  (.consume event))

(defn- complete-drop!
  [^DragEvent event]
  (let [file (first (.getFiles (.getDragboard event)))]
    (.setDropCompleted event (boolean file))
    (.consume event)
    (when file
      (dispatch! {:event/type ::state/file-chosen :file file}))))

(defn handle-event
  "The single entry point for everything the window can do."
  [raw-event]
  (let [event (normalise raw-event)]
    (case (:event/type event)
      ::view/drag-over    (accept-drag! (:fx/event event))
      ::view/drag-dropped (complete-drop! (:fx/event event))
      ::view/close-requested (do (save-session!) (Platform/exit))

      ;; The picker shows translated labels, so the label has to be mapped back
      ;; to the character it stands for.
      ::view/delimiter-picked
      (let [ctx   (state/ctx @*state)
            label (:label event)
            match (first (filter #(= label (i18n/tr ctx (:label-key %)))
                                 state/selectable-delimiters))]
        (dispatch! {:event/type ::state/delimiter-override-changed
                    :choice     (:value match)}))

      ::view/new-folder-requested
      (let [{:keys [out-dir survey]} @*state]
        (dispatch! {:event/type ::state/collision-resolved
                    :choice     :new-dir
                    :dir        (timestamped-dir out-dir (:file survey))}))

      (let [{:keys [state effects]} (state/handle @*state event)]
        (reset! *state state)
        (doseq [effect effects] (perform! effect))))))

(defn dispatch!
  "Deliver an event, hopping onto the JavaFX thread when called from a worker."
  [event]
  (if (Platform/isFxApplicationThread)
    (handle-event event)
    (Platform/runLater #(handle-event event))))

;; ── Entry point ─────────────────────────────────────────────────────────────

(def renderer
  (fx/create-renderer
   :middleware (fx/wrap-map-desc view/root)
   :opts {:fx.opt/map-event-handler handle-event}))

(defn watch-system-appearance!
  "Follow the operating system's light and dark setting while the window is
   open, rather than reading it once at startup. Someone whose machine switches
   at sunset should not have to restart the application to keep up."
  []
  (fx/on-fx-thread
   (try
     (.addListener (.colorSchemeProperty (Platform/getPreferences))
                   (reify ChangeListener
                     (changed [_ _ _ _]
                       (when (= :system (:theme @*state))
                         (perform! [:apply-theme :system])))))
     (catch Throwable _ nil))))

(defn startup-state
  "The state to open with, from saved settings and the command line. Anything
   given on the command line wins, then what was saved last time, then the
   system's own language and appearance."
  [base saved options]
  (let [requested (:locale options)
        language  (or requested (:language saved) (i18n/detect-tag))
        theme     (or (:theme options) (:theme saved) :system)]
    ;; A language we do not have is not fatal, but saying nothing about it would
    ;; leave someone staring at an English window wondering why --locale it did
    ;; nothing.
    (when (and requested (nil? (i18n/normalise-tag requested)))
      (binding [*out* *err*]
        (println (str "No translation for '" requested "'. Available: "
                      (str/join ", " (i18n/available-tags))
                      ". Starting in English."))))
    (-> base
        (merge (dissoc saved :language :theme))
        (state/with-language language)
        (assoc :theme theme))))

(defn start-window!
  [options]
  (reset! *state (startup-state state/initial (prefs/load-prefs) options))
  (fx/mount-renderer *state renderer)
  (perform! [:apply-theme (:theme @*state)])
  (watch-system-appearance!))

(defn show-language-problems!
  "Refuse to start in a language we cannot vouch for, and say why.

   Continuing in English is offered as well as quitting. Making a rejected file
   fatal would mean anything dropped into that folder — by a careless editor as
   easily as by anyone else — could leave the application permanently unable to
   open, which is a worse failure than the one being guarded against."
  [problems options]
  (binding [*out* *err*]
    (println "Refused one or more translations:")
    (doseq [p problems] (println " -" p)))
  (let [*error (atom {:problems problems})
        window (atom nil)]
    (reset! window
            (fx/create-renderer
             :middleware (fx/wrap-map-desc view/startup-error-window)
             :opts {:fx.opt/map-event-handler
                    (fn [event]
                      (case (:event/type event)
                        ::view/quit-requested (exit! 2)
                        ::view/continue-in-english
                        (do (fx/unmount-renderer *error @window)
                            (i18n/forget-external!)
                            (start-window! (assoc options :locale "en")))
                        nil))}))
    (fx/mount-renderer *error @window)))

(defn -main
  [& args]
  (let [{:keys [action status message options]} (cli/parse args)]
    (if (= action :exit)
      (do (println message)
          (exit! status))
      (let [dir      (or (:languages options) (desktop/languages-dir))
            {:keys [problems]} (i18n/load-external! dir)]
        (if (seq problems)
          (show-language-problems! problems options)
          (start-window! options))))))
