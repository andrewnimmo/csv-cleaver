(ns csv-cleaver.state
  "Every state change the application can undergo, as one pure function.

   `handle` takes the current state and an event map and returns the next state
   together with a list of effects to perform. It never touches the disk, never
   shows a dialog and never mentions JavaFX, which is what makes the whole
   behaviour of the application testable by calling a function and looking at
   the map that comes back.

   Effects are data — [:scan file], [:split opts] — and are carried out in
   csv-cleaver.app, which is the only namespace that knows how."
  (:require
   [clojure.string :as str]
   [csv-cleaver.format :as fmt]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.split :as split])
  (:import
   (java.io File)
   (java.nio.charset Charset)))

(def default-rows "65,000")
(def default-size "25 MB")

(def row-presets
  "Shortcuts for the row count, each labelled with why anyone would pick it.
   Bare numbers told the user nothing, and the old 1,000,000 was a round number
   standing in for Excel's actual limit of 1,048,576 without saying so."
  [{:value 65000                 :label-key :preset/excel-old}
   {:value 100000                :label-key :preset/plain}
   {:value split/excel-row-limit :label-key :preset/excel-max}])

(def size-presets
  [{:value "5 MB"   :label-key :preset/plain}
   {:value "25 MB"  :label-key :preset/plain}
   {:value "100 MB" :label-key :preset/plain}])

(def detected-charset "Detected")

(def selectable-charsets
  "Offered behind Advanced. Deliberately short: these are the encodings CSV
   files in the wild actually arrive in, and a longer list would invite someone
   to pick at random."
  [detected-charset "UTF-8" "windows-1252" "ISO-8859-1" "UTF-16LE" "UTF-16BE"])

(def initial
  "The window before anything has happened. English until the application
   replaces it with the detected or requested language at startup, so that
   tests get one predictable language without setting anything up."
  {:language        i18n/fallback-tag
   :i18n            (i18n/context i18n/fallback-tag)
   :phase           :empty
   :file            nil
   :survey          nil
   :scan-rows       0
   :out-dir         nil
   :mode            :rows
   :rows-text       default-rows
   :size-text       default-size
   :has-header?      true
   :header-answered? false
   :include-header? true
   :template        naming/default-template
   :charset-override detected-charset
   :delimiter-override nil
   :excel-safe?     true
   :advanced-open?  false
   :details-open?   false
   :theme           :system
   :progress        {:rows-done 0 :files-done 0 :current-name nil}
   :result          nil
   :error           nil
   :dialog          nil
   :collisions      []
   :drag-over?      false})

;; ── Derived values ──────────────────────────────────────────────────────────
;; Computed from state rather than stored in it, so they can never go stale.

(defn ctx
  "The language context this window is running in."
  [state]
  (:i18n state))

(defn with-language
  "Switch the window to `tag`, falling back to English for anything we have no
   translation for."
  [state tag]
  (let [tag (or (i18n/normalise-tag tag) i18n/fallback-tag)]
    (assoc state :language tag :i18n (i18n/context tag))))

(defn split-value
  "The row count or byte size the user has asked for, or nil when what they have
   typed is not yet a usable number. Read in the display language, so 65.000
   means sixty-five thousand to a German user."
  [{:keys [mode rows-text size-text] :as state}]
  (if (= mode :rows)
    (fmt/parse-count (ctx state) rows-text)
    (fmt/parse-size (ctx state) size-text)))

(defn current-plan
  "What pressing Split would do, or nil before a file has been surveyed."
  [{:keys [survey mode has-header? include-header? excel-safe? out-dir] :as state}]
  (when survey
    (split/plan {:survey          survey
                 :mode            mode
                 :value           (split-value state)
                 :has-header?     has-header?
                 :include-header? include-header?
                 :excel-safe?     excel-safe?
                 :out-dir         out-dir})))

(defn ready?
  "Whether Split can be pressed: a surveyed file, a usable number, a workable
   plan and a legal file name pattern."
  [{:keys [phase template] :as state}]
  ;; Note for the next person: this cannot use some->. Threading through
  ;; :problem short-circuits on nil, which is precisely the value that means
  ;; there is no problem, so the button would never enable.
  (boolean (and (= phase :ready)
                (split-value state)
                (nil? (naming/template-problem template))
                (nil? (:problem (current-plan state))))))

(defn header-uncertain?
  "Whether to ask the user about the first row rather than guess at it.

   Only when the evidence is genuinely balanced, and only until they answer.
   A confident detection never interrupts anyone."
  [{:keys [survey header-answered?]}]
  (boolean (and survey
                (not header-answered?)
                (= :unsure (get-in survey [:header :verdict])))))

(defn blocking-problem
  "The one thing stopping the user pressing Split, as a translation key or a
   message map, or nil. The view turns it into a sentence."
  [{:keys [phase template] :as state}]
  (when (= phase :ready)
    (or (naming/template-problem template)
        (when-not (split-value state)
          (if (= (:mode state) :rows)
            :problem/rows-needed
            :problem/size-needed))
        (:problem (current-plan state)))))

;; ── Event handling ──────────────────────────────────────────────────────────

(defn- with-effects
  ([state] {:state state :effects []})
  ([state & effects] {:state state :effects (vec (remove nil? effects))}))

(defmulti handle
  "Apply `event` to `state`. Returns {:state next-state :effects [...]}."
  (fn [_state event] (:event/type event)))

(defmethod handle :default
  [state _event]
  (with-effects state))

(defmethod handle ::file-chosen
  [state {:keys [^File file]}]
  (if (and file (.isFile file))
    (with-effects (assoc state
                         :phase :scanning
                         :file file
                         :survey nil
                         :result nil
                         :error nil
                         :scan-rows 0
                         :drag-over? false
                         :out-dir (or (:out-dir state) nil))
      [:scan {:file file :delimiter (:delimiter-override state)}])
    (with-effects (assoc state
                         :error :problem/unreadable
                         :drag-over? false))))

(defmethod handle ::scan-progress
  [state {:keys [rows]}]
  (with-effects (assoc state :scan-rows rows)))

(defmethod handle ::scan-succeeded
  [state {:keys [survey]}]
  (let [file (:file survey)]
    (with-effects
      (assoc state
             :phase :ready
             :survey survey
             ;; The checkbox follows what the file actually looks like. A user
             ;; who disagrees can still change it; most never have to.
             :has-header? (:header-likely? survey)
             :header-answered? false
             :out-dir (or (:out-dir state)
                          (when file (.getParentFile (.getAbsoluteFile ^File file))))))))

(defmethod handle ::scan-failed
  [state {:keys [message]}]
  (with-effects (assoc state :phase :empty :file nil :survey nil :error message)))

(defmethod handle ::mode-changed
  [state {:keys [mode]}]
  (with-effects (assoc state :mode mode)))

(defmethod handle ::rows-changed
  [state {:keys [text]}]
  (with-effects (assoc state :rows-text text)))

(defmethod handle ::size-changed
  [state {:keys [text]}]
  (with-effects (assoc state :size-text text)))

(defmethod handle ::preset-chosen
  [state {:keys [value]}]
  (with-effects (if (= (:mode state) :rows)
                  (assoc state :rows-text (i18n/number (ctx state) value))
                  (assoc state :size-text (str value)))))

(defmethod handle ::excel-safe-toggled
  [state {:keys [selected]}]
  (with-effects (assoc state :excel-safe? selected)))

(defmethod handle ::help-toggled
  [state _]
  (with-effects (assoc state :dialog (when-not (= :help (:dialog state)) :help))))

(defmethod handle ::header-toggled
  [state {:keys [selected]}]
  ;; Touching the checkbox is itself an answer, so the question stops asking.
  (with-effects (assoc state :has-header? selected :header-answered? true)))

(defmethod handle ::header-answered
  [state {:keys [has-header?]}]
  (with-effects (assoc state :has-header? has-header? :header-answered? true)))

(defmethod handle ::include-header-toggled
  [state {:keys [selected]}]
  (with-effects (assoc state :include-header? selected)))

(defmethod handle ::template-changed
  [state {:keys [text]}]
  (with-effects (assoc state :template text)))

(defmethod handle ::language-changed
  [state {:keys [tag language-name]}]
  (let [tag (or tag (i18n/tag-for-name language-name))]
    (with-effects (with-language state tag) [:save-prefs {:language tag}])))

(defmethod handle ::about-toggled
  [state _]
  (with-effects (assoc state :dialog (when-not (= :about (:dialog state)) :about))))

(defmethod handle ::quit-requested
  [state _]
  (with-effects state [:quit]))

(defmethod handle ::dialog-closed
  [state _]
  (with-effects (assoc state :dialog nil)))

(def selectable-delimiters
  "Offered behind Advanced when the detected separator is wrong. The first
   entry means \"whatever was detected\"."
  [{:value nil  :label-key :delimiter/detected}
   {:value \,   :label-key :delimiter/comma}
   {:value \;   :label-key :delimiter/semicolon}
   {:value \tab :label-key :delimiter/tab}
   {:value \|   :label-key :delimiter/pipe}])

(defmethod handle ::delimiter-override-changed
  [state {:keys [choice]}]
  ;; The row and field counts were worked out with the old separator, so the
  ;; file has to be read again rather than the numbers adjusted.
  (let [next-state (assoc state :delimiter-override choice)]
    (if-let [file (:file state)]
      (with-effects (assoc next-state :phase :scanning :scan-rows 0)
        [:scan {:file file :delimiter choice}])
      (with-effects next-state))))

(defmethod handle ::charset-override-changed
  [state {:keys [choice]}]
  (with-effects (assoc state :charset-override (or choice detected-charset))))

(defmethod handle ::advanced-toggled
  [state _]
  (with-effects (update state :advanced-open? not)))

(defmethod handle ::details-toggled
  [state _]
  (with-effects (update state :details-open? not)))

(defmethod handle ::theme-changed
  [state {:keys [theme]}]
  (with-effects (assoc state :theme theme) [:apply-theme theme] [:save-prefs {:theme theme}]))

(defmethod handle ::browse-input-requested
  [state _]
  (with-effects state [:choose-file {:initial-dir (:out-dir state)}]))

(defmethod handle ::browse-output-requested
  [state _]
  (with-effects state [:choose-dir {:initial-dir (:out-dir state)}]))

(defmethod handle ::out-dir-chosen
  [state {:keys [^File dir]}]
  (if dir
    (with-effects (assoc state :out-dir dir) [:save-prefs {:out-dir (.getAbsolutePath dir)}])
    (with-effects state)))

(defmethod handle ::drag-entered
  [state _]
  (with-effects (assoc state :drag-over? true)))

(defmethod handle ::drag-exited
  [state _]
  (with-effects (assoc state :drag-over? false)))

;; ── Splitting ───────────────────────────────────────────────────────────────

(defn effective-encoding
  "How the file will actually be read: what was detected, unless the user has
   overridden it behind Advanced. A byte-order mark found during detection is
   still skipped on read and reproduced on write, whatever the override — the
   mark is a fact about the file, not a guess."
  [{:keys [survey charset-override]}]
  (let [detected (:encoding survey)]
    (if (or (str/blank? (str charset-override))
            (= charset-override detected-charset))
      detected
      (assoc detected
             :charset (Charset/forName charset-override)
             :label   charset-override))))

(defn split-request
  "The map the split effect needs, assembled from state."
  [{:keys [survey out-dir mode has-header? include-header? template excel-safe?]
    :as   state}]
  {:excel-safe?     excel-safe?
   :survey          (assoc survey :encoding (effective-encoding state))
   :out-dir         out-dir
   :mode            mode
   :value           (split-value state)
   :has-header?     has-header?
   :include-header? include-header?
   :template        template
   :plan            (current-plan state)})

(defmethod handle ::split-requested
  [state _]
  (if-not (ready? state)
    (with-effects state)
    ;; Nothing is written until the folder has been checked. This is the
    ;; guarantee that the application cannot destroy a file without being told
    ;; it may.
    (with-effects state [:check-collisions (split-request state)])))

(defmethod handle ::collisions-found
  [state {:keys [files]}]
  (with-effects (assoc state :dialog :collisions :collisions (vec files))))

(defmethod handle ::start-split
  [state _]
  (with-effects (assoc state
                       :phase :splitting
                       :dialog nil
                       :collisions []
                       :result nil
                       :error nil
                       :progress {:rows-done 0 :files-done 0 :current-name nil})
    [:split (split-request state)]))

(defmethod handle ::collision-resolved
  [state {:keys [choice ^File dir]}]
  (case choice
    :cancel  (with-effects (assoc state :dialog nil :collisions []))
    :replace (handle (assoc state :dialog nil :collisions []) {:event/type ::start-split})
    :new-dir (-> (assoc state :dialog nil :collisions [] :out-dir dir)
                 (handle {:event/type ::start-split}))))

(defmethod handle ::split-progress
  [state {:keys [progress]}]
  (with-effects (assoc state :progress progress)))

(defmethod handle ::split-succeeded
  [state {:keys [result]}]
  ;; Back to the options, not on to a screen of their own. The outcome appears
  ;; above the settings that produced it, so adjusting one and going again is a
  ;; single press rather than a journey back through the file chooser.
  (with-effects (assoc state :phase :ready :result result)))

(defmethod handle ::split-failed
  [state {:keys [message]}]
  (with-effects (assoc state :phase :ready :error message)))

(defmethod handle ::cancel-requested
  [state _]
  (with-effects state [:cancel]))

(defmethod handle ::reveal-requested
  [state _]
  (with-effects state [:reveal (:out-dir state)]))

(defmethod handle ::reset
  [state _]
  (with-effects (merge state
                       (select-keys initial [:phase :file :survey :result :error
                                             :progress :scan-rows :details-open?
                                             :dialog :collisions :header-answered?]))))

(defn apply-event
  "Run one event against `state`, returning only the next state. Effects are
   discarded — for tests that care about state alone."
  [state event]
  (:state (handle state event)))

(defn apply-events
  "Thread several events through `state` in order."
  [state events]
  (reduce apply-event state events))

(defn data-rows
  "Rows of data in the chosen file, or 0 before one has been surveyed."
  ^long [{:keys [survey has-header?]}]
  (if survey (scan/data-rows survey has-header?) 0))
