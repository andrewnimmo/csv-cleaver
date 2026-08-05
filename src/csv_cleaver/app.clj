(ns csv-cleaver.app
  "Wiring: the only namespace that knows about JavaFX objects, threads, the
   file system and the clock.

   csv-cleaver.state decides what should happen and returns effects as data;
   this namespace is the small, deliberately dull layer that carries them out.
   Everything genuinely interesting has been pushed out of here so that it can
   be tested without a display.

   Not the entry point — that is csv-cleaver.main, which can be loaded without
   a display and only reaches this namespace when a window is going to open."
  (:require
   [cljfx.api :as fx]
   [clojure.string :as str]
   [csv-cleaver.branding :as branding]
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
   (javafx.stage DirectoryChooser FileChooser FileChooser$ExtensionFilter Stage Window)))

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
  [{:keys [^File file delimiter scan-id]} dispatch]
  (try
    (let [survey (scan/survey file {:delimiter delimiter
                                    :on-progress
                                    (fn [rows]
                                      (dispatch {:event/type ::state/scan-progress
                                                 :scan-id scan-id
                                                 :rows rows}))})]
      (dispatch {:event/type ::state/scan-succeeded :scan-id scan-id :survey survey}))
    (catch Exception e
      (dispatch {:event/type ::state/scan-failed :scan-id scan-id
                 :message (friendly-message e)}))))

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
    ;; Headless, the toolkit was never started and there is nothing to shut
    ;; down. Whatever it makes of being told to exit, it must not stop the
    ;; process from ending.
    (try (Platform/exit) (catch Throwable _ nil))
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
   (cond-> (merge (select-keys state prefs/remembered)
                  ;; As numbers, never as the text in the boxes. See
                  ;; state/remembered-values.
                  (state/remembered-values state))
     ;; The tracked geometry serves when there is no stage to ask — which is
     ;; every path that ends the process without passing through our own Quit.
     (:window state) (assoc :window (:window state))
     stage           (assoc :window {:x      (.getX stage)
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

(defmethod perform! :reveal-hidden
  [_]
  (i18n/reveal-hidden!))

(defmethod perform! :compose-mail
  [_]
  (desktop/compose-mail!
   (desktop/mail-uri (branding/value :contact)
                     (str (branding/app-name) " " (branding/build-label)))))

(defmethod perform! :reveal
  [[_ dir]]
  (desktop/reveal! dir))

(defmethod perform! :save-prefs
  [[_ settings]]
  (run-async! (fn [] (prefs/save-prefs! settings))))

;; ── One chooser at a time ───────────────────────────────────────────────────
;;
;; showOpenDialog runs a nested event loop on the JavaFX thread. With a nil
;; owner the button stayed clickable, so every click stacked another dialog on
;; another nested loop — and the loops unwind strictly last-in-first-out, which
;; is why quitting with several open appeared to hang: the application cannot
;; leave until each dialog is dismissed in reverse order. Each dialog that WAS
;; answered also started its own scan, and the surveys resolved as whichever
;; scan finished last, not whichever file was chosen last.
;;
;; Two fences. Owning the dialog by the main window makes it window-modal, so
;; the button cannot be clicked while it is open. The claim guard catches what
;; modality cannot: a double-click's second press is already queued before the
;; first has shown the dialog and disabled anything.

(defonce chooser-open? (atom false))

(defn claim-chooser!
  "True exactly once until released. Both sides run on the FX thread, so this
   is bookkeeping rather than synchronisation — but it still has to be a
   compare-and-set, because the second click arrives before the first dialog's
   nested event loop has returned."
  []
  (compare-and-set! chooser-open? false true))

(defn release-chooser! []
  (reset! chooser-open? false))

(defn- with-sole-chooser!
  "Run `show!` unless a chooser is already up, releasing the claim however the
   dialog ends. A refused click is dropped silently: the existing dialog is
   window-modal, so it is already the frontmost thing the user can touch."
  [show!]
  (when (claim-chooser!)
    (try
      (show!)
      (finally (release-chooser!)))))

(defmethod perform! :choose-file
  [[_ {:keys [^File initial-dir]}]]
  (with-sole-chooser!
    (fn []
      (let [chooser (doto (FileChooser.)
                      (.setTitle "Choose a CSV file")
                      (-> .getExtensionFilters
                          (.add (FileChooser$ExtensionFilter.
                                 "Comma separated values"
                                 ^"[Ljava.lang.String;"
                                 (into-array String ["*.csv" "*.CSV" "*.tsv" "*.txt"])))))]
        (when (and initial-dir (.isDirectory initial-dir))
          (.setInitialDirectory chooser initial-dir))
        (when-let [file (.showOpenDialog chooser (primary-stage))]
          (dispatch! {:event/type ::state/file-chosen :file file}))))))

(defmethod perform! :choose-dir
  [[_ {:keys [^File initial-dir]}]]
  (with-sole-chooser!
    (fn []
      (let [chooser (doto (DirectoryChooser.)
                      (.setTitle "Choose where to save the files"))]
        (when (and initial-dir (.isDirectory initial-dir))
          (.setInitialDirectory chooser initial-dir))
        (when-let [dir (.showDialog chooser (primary-stage))]
          (dispatch! {:event/type ::state/out-dir-chosen :dir dir}))))))

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

      ;; Key transitions only matter for their modifier state; Alt is read here
      ;; because the pure layer cannot touch a KeyEvent.
      ::view/modifier-keys
      (let [e ^javafx.scene.input.KeyEvent (:fx/event event)]
        (dispatch! {:event/type ::state/alt-changed :down? (.isAltDown e)}))

      ;; The picker shows translated labels, so the label has to be mapped back
      ;; to the character it stands for.
      ::view/delimiter-picked
      (let [ctx   (state/ctx @*state)
            label (:label event)
            match (first (filter #(= label (i18n/tr ctx (:label-key %)))
                                 state/selectable-delimiters))]
        (dispatch! {:event/type ::state/delimiter-override-changed
                    :choice     (:value match)}))

      ;; Same for the encoding picker: only the "detected" entry is translated,
      ;; but the picker still hands back whatever string it is showing.
      ::view/charset-picked
      (dispatch! {:event/type ::state/charset-override-changed
                  :choice     (state/charset-for-label (state/ctx @*state)
                                                       (:label event))})

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
  "Behind a delay, and it matters. Creating a cljfx renderer starts the JavaFX
   toolkit, which under --headless is exactly what must not happen: a machine
   running the service with no display would fail at load, before anything had a
   chance to explain itself. Forced when a window is actually opened."
  (delay
    (fx/create-renderer
     :middleware (fx/wrap-map-desc view/root)
     :opts {:fx.opt/map-event-handler handle-event})))

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
        ;; select-keys, not the whole map: a settings file is a file, and an
        ;; older one carries keys this version deliberately ignores. Filtering
        ;; here as well as in prefs/load-prefs means neither has to be the only
        ;; thing standing between a stale key and the window.
        (merge (apply dissoc (select-keys saved prefs/remembered)
                      [:language :theme :rows :size-bytes]))
        (state/with-language language)
        ;; Numbers last, and only after the language is settled, so they are
        ;; written into the boxes as the language being opened in writes them.
        ;; Nothing here has to know what language wrote them, which is the whole
        ;; reason they are stored as numbers.
        (state/with-values saved)
        (assoc :theme theme))))

(defn track-window-geometry!
  "Keep the stage's position and size in the state, so the session can be
   saved without asking the stage.

   This exists because of the ways the application can end that never pass
   through our own Quit: the Glass-built macOS application menu's Quit and its
   Cmd-Q, or a plain kill. Those bypass every JavaFX close handler, so the
   only reliable save is a JVM shutdown hook — and a shutdown hook cannot
   safely interrogate a dying JavaFX stage. State it can read."
  [^Stage stage]
  (let [push! (fn []
                (dispatch! {:event/type ::state/window-moved
                            :window {:x      (.getX stage)
                                     :y      (.getY stage)
                                     :width  (.getWidth stage)
                                     :height (.getHeight stage)}}))
        l     (reify ChangeListener (changed [_ _ _ _] (push!)))]
    (doseq [p [(.xProperty stage) (.yProperty stage)
               (.widthProperty stage) (.heightProperty stage)]]
      (.addListener ^javafx.beans.value.ObservableValue p l))
    (push!)))

(defonce ^:private shutdown-save-installed? (atom false))

(defn install-shutdown-save!
  "Save the session however the process ends — our Quit, the window's close
   button, the Glass application menu's Quit that never touches JavaFX
   handlers, or a plain kill. Idempotent: hooks accumulate, and a second
   window in one JVM must not mean a second save."
  []
  (when (compare-and-set! shutdown-save-installed? false true)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                       (fn []
                         (try
                           (prefs/save-prefs! (session-settings @*state nil))
                           (catch Throwable _ nil)))
                               "csv-cleaver-session-save"))
    true))

(defn start-window!
  [options]
  (reset! *state (startup-state state/initial (prefs/load-prefs) options))
  (install-shutdown-save!)
  (fx/mount-renderer *state @renderer)
  (perform! [:apply-theme (:theme @*state)])
  (fx/on-fx-thread (some-> (primary-stage) track-window-geometry!))
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
