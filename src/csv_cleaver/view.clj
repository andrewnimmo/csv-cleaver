(ns csv-cleaver.view
  "The window, as a pure function of state.

   Every function here takes a map and returns a map. Nothing is constructed,
   nothing is mutated and no JavaFX object is touched, which means the whole
   interface can be tested by calling functions and reading the data that comes
   back — no robot, no display, no toolkit.

   No English appears below either. Every phrase is looked up by key from
   resources/i18n, so translating the application never means editing this file."
  (:require
   [clojure.string :as str]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.desktop :as desktop]
   [csv-cleaver.format :as fmt]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.state :as state])
  (:import
   (java.io File)))

(defn- compact [children]
  (vec (remove nil? children)))

(defn- path-of [^File f]
  (if f (.getAbsolutePath f) ""))

;; ── Small pieces ────────────────────────────────────────────────────────────

(defn chip
  [{:keys [text kind]}]
  {:fx/type     :label
   :text        text
   :style-class (cond-> ["chip"] kind (conj (name kind)))})

(defn callout
  "A tinted block of text. `kind` is :accent, :success, :warning or :danger."
  [{:keys [kind headline body]}]
  {:fx/type     :v-box
   :style-class ["callout" (name kind)]
   :children    (compact
                 [(when headline
                    {:fx/type :label :text headline :wrap-text true
                     :style-class ["label" "headline"]})
                  (when body
                    {:fx/type :label :text body :wrap-text true
                     :style-class ["label"]})])})

(defn disclosure
  [{:keys [open? text event]}]
  {:fx/type     :hyperlink
   :style-class ["hyperlink" "disclosure"]
   :text        (str (if open? "▾ " "▸ ") text)
   :on-action   event})

(defn icon-button
  [{:keys [glyph tooltip event]}]
  {:fx/type     :button
   :style-class ["button" "flat" "icon-button"]
   :text        glyph
   :tooltip     {:fx/type :tooltip :text tooltip}
   :on-action   event})

(defn menu-bar
  "A conventional menu bar, which exists for one reason: Quit has to be
   somewhere a person would think to look for it.

   It was in the About dialog, which met the requirement that it not be easy to
   press by accident and comprehensively failed the requirement that it be
   findable — nobody opens an About box looking for the way out. A menu is where
   everyone looks, and needs two deliberate actions, so it satisfies both.

   On macOS this becomes the system menu bar at the top of the screen. The
   information and question-mark buttons stay as the quick route to the same
   two overlays."
  [ctx system-menu?]
  {:fx/type             :menu-bar
   :use-system-menu-bar true
   ;; On macOS the menus move to the bar at the top of the screen, but the node
   ;; itself stays in the layout and reserves about ten pixels of empty strip
   ;; under the title bar. Leaving it unmanaged keeps it in the scene — which is
   ;; what populates the system bar — while taking no room.
   :managed             (not system-menu?)
   :menus
   [{:fx/type :menu
     :text    (i18n/tr ctx :menu/file)
     :items   [{:fx/type     :menu-item
                :text        (i18n/tr ctx :action/quit)
                :accelerator [:shortcut :q]
                :on-action   {:event/type ::state/quit-requested}}]}
    {:fx/type :menu
     :text    (i18n/tr ctx :menu/help)
     :items   [{:fx/type   :menu-item
                :text      (i18n/tr ctx :action/help)
                :on-action {:event/type ::state/help-toggled}}
               {:fx/type :separator-menu-item}
               {:fx/type   :menu-item
                :text      (i18n/tr ctx :action/about)
                :on-action {:event/type ::state/about-toggled}}]}]})

(defn header-bar
  "The strip along the top holding the two overlay buttons — the quick route to
   what the Help menu also offers."
  [ctx]
  {:fx/type     :h-box
   :style-class ["header-bar"]
   :children
   [{:fx/type :region :h-box/hgrow :always}
    (icon-button {:glyph "?" :tooltip (i18n/tr ctx :action/help)
                  :event {:event/type ::state/help-toggled}})
    (icon-button {:glyph "i" :tooltip (i18n/tr ctx :action/about)
                  :event {:event/type ::state/about-toggled}})]})

;; ── Empty state ─────────────────────────────────────────────────────────────

(defn drop-zone
  [{:keys [drag-over?] :as st}]
  (let [ctx (state/ctx st)]
    ;; The drag handlers live on the whole window, not here — see `content`.
    {:fx/type     :v-box
     :style-class (cond-> ["drop-zone"] drag-over? (conj "active"))
     :children
     [{:fx/type :label :style-class ["headline"]
       :text    (i18n/tr ctx (if drag-over? :empty/headline-active :empty/headline))}
      {:fx/type :label :style-class ["hint"] :text (i18n/tr ctx :empty/subhead)}
      {:fx/type   :button
       :text      (i18n/tr ctx :action/browse)
       :on-action {:event/type ::state/browse-input-requested}}]}))

(defn empty-body
  [{:keys [error] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type  :v-box
     :spacing  10
     :children (compact
                [(drop-zone st)
                 (when error (callout {:kind :danger :body (fmt/message ctx error)}))
                 {:fx/type     :label
                  :style-class ["hint"]
                  :text        (i18n/tr ctx :empty/hint)
                  :alignment   :center
                  :max-width   Double/MAX_VALUE}])}))

;; ── Scanning ────────────────────────────────────────────────────────────────

(defn scanning-body
  [{:keys [^File file scan-rows] :as st}]
  (let [ctx  (state/ctx st)
        rows (long (or scan-rows 0))]
    {:fx/type :v-box
     :spacing 10
     :children
     [{:fx/type     :v-box
       :style-class ["file-card"]
       :children
       [{:fx/type :label :style-class ["label" "file-name"] :text (if file (.getName file) "")}
        {:fx/type :label :style-class ["hint"]
         :text    (if (pos? rows)
                    (i18n/trn ctx :scanning/counted rows (i18n/number ctx rows))
                    (i18n/tr ctx :scanning/checking))}
        {:fx/type :progress-bar :max-width Double/MAX_VALUE :progress -1.0}]}]}))

;; ── File card ───────────────────────────────────────────────────────────────

(defn separated-key
  "Which phrase describes this separator on the file card."
  [delimiter]
  (case delimiter
    \; :file/separated-semicolons
    \tab :file/separated-tabs
    \| :file/separated-pipes
    :file/separated-commas))

(defn separator-name-key
  "The separator's name in running text, for the Advanced note."
  [delimiter]
  (case delimiter
    \; :delimiter/name-semicolon
    \tab :delimiter/name-tab
    \| :delimiter/name-pipe
    :delimiter/name-comma))

(defn file-chips
  [{:keys [survey has-header?] :as st}]
  (let [ctx    (state/ctx st)
        damage (fmt/damage-summary ctx survey)
        rows   (state/data-rows st)]
    (compact
     [{:fx/type chip
       :text    (i18n/trn ctx (if has-header? :file/data-rows :file/rows)
                          rows (i18n/number ctx rows))}
      {:fx/type chip :text (fmt/file-size ctx (:bytes survey))}
      {:fx/type chip :text (i18n/tr ctx :file/encoding (get-in survey [:encoding :label]))}
      ;; Always shown, including for a comma. Reporting the separator only when
      ;; it is unusual is the opposite of what the encoding chip does, and it
      ;; leaves the commonest case as the one you cannot verify.
      {:fx/type chip :text (i18n/tr ctx (separated-key (:delimiter survey)))}
      (if damage
        {:fx/type chip :kind :warn :text (:headline damage)}
        {:fx/type chip :kind :good :text (i18n/tr ctx :file/healthy)})])))

(def preview-cell-width
  "Longest cell shown in the preview. The point is to show the shape of the
   file, not its contents, and one long note would otherwise push every other
   column off the right-hand side."
  16)

(defn align-columns
  "Pad the cells of each row so the columns line up under one another.

   Ragged spacing makes the reader count separators to work out which value sits
   under which heading, which defeats the purpose of showing the rows at all.
   Whitespace inside a cell is collapsed, and over-long cells are shortened."
  ([rows] (align-columns rows preview-cell-width))
  ([rows limit]
   (let [clip   (fn [cell]
                  (let [s (str/trim (str/replace (str cell) #"\s+" " "))]
                    (if (> (count s) limit) (str (subs s 0 (dec limit)) "…") s)))
         rows   (mapv #(mapv clip %) rows)
         widths (when (seq rows)
                  (vec (for [i (range (apply max (map count rows)))]
                         (apply max (map #(count (nth % i "")) rows)))))]
     (mapv (fn [row]
             ;; Trailing separators are stripped as well as trailing spaces: a
             ;; row with fewer cells than its neighbours would otherwise end in
             ;; a dangling "·" pointing at nothing.
             (-> (str/join " · "
                           (map-indexed (fn [i w]
                                          (let [cell (nth row i "")]
                                            (apply str cell
                                                   (repeat (- w (count cell)) \space))))
                                        widths))
                 (str/replace #"[\s·]+$" "")))
           rows))))

(defn preview-rows
  "The first rows as they were parsed.

   This is what makes the separator and the header decision checkable rather
   than merely asserted: seeing id · name · city above 1 · Ann · Leeds tells you
   both that the columns were found correctly and which row is which."
  [{:keys [survey] :as st}]
  (let [ctx  (state/ctx st)
        rows (:preview survey)]
    (when (seq rows)
      {:fx/type :v-box
       :spacing 3
       :children
       [{:fx/type :label :style-class ["hint"] :text (i18n/tr ctx :file/preview)}
        ;; One block, not one per row: separate boxes for consecutive lines of
        ;; the same file read as unrelated things.
        {:fx/type      :label
         :style-class  ["file-list"]
         :max-width    Double/MAX_VALUE
         :text-overrun :ellipsis
         :text         (str/join "\n" (align-columns rows))}]})))

(defn header-question
  "Shown only when the detection genuinely cannot tell whether the first row
   names the columns.

   The row itself is displayed, because someone who has never heard the word
   \"header\" can still recognise id · name · city as labels the moment they see
   them. Asking in the abstract would get a shrug; asking about what is on the
   screen gets an answer."
  [st]
  (let [ctx (state/ctx st)]
    {:fx/type     :v-box
     :style-class ["callout" "warning"]
     :spacing     6
     :children
     ;; The rows themselves are shown just above by preview-rows, so the
     ;; question does not repeat them.
     [{:fx/type :label :style-class ["label"] :wrap-text true
       :text    (i18n/tr ctx :header/question)}
      {:fx/type   :h-box
       :spacing   8
       :children
       [{:fx/type   :button
         :text      (i18n/tr ctx :header/these-are-names)
         :on-action {:event/type ::state/header-answered :has-header? true}}
        {:fx/type   :button
         :text      (i18n/tr ctx :header/this-is-data)
         :on-action {:event/type ::state/header-answered :has-header? false}}]}]}))

(defn file-card
  [{:keys [survey] :as st}]
  (let [ctx        (state/ctx st)
        ^File file (:file survey)
        damage     (fmt/damage-summary ctx survey)]
    {:fx/type     :v-box
     :style-class ["file-card"]
     :children
     (compact
      [{:fx/type   :h-box
        :spacing   10
        :alignment :center-left
        :children
        [{:fx/type     :v-box
          :h-box/hgrow :always
          :children
          [{:fx/type :label :style-class ["label" "file-name"] :text (.getName file)}
           {:fx/type      :label
            :style-class  ["hint"]
            :text-overrun :leading-ellipsis
            :text         (path-of (.getParentFile (.getAbsoluteFile file)))}]}
         {:fx/type   :button
          :text      (i18n/tr ctx :action/change)
          :on-action {:event/type ::state/browse-input-requested}}]}
       {:fx/type :flow-pane :hgap 6 :vgap 6 :children (file-chips st)}
       (preview-rows st)
       (when damage
         {:fx/type :label :style-class ["hint"] :wrap-text true :text (:detail damage)})
       (when (state/header-uncertain? st) (header-question st))])}))

;; ── Options ─────────────────────────────────────────────────────────────────

(defn mode-toggle
  [{:keys [mode] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type :h-box
     :children
     [{:fx/type     :toggle-button
       :style-class ["toggle-button" "left-pill"]
       :text        (i18n/tr ctx :options/by-rows)
       :selected    (= mode :rows)
       :on-action   {:event/type ::state/mode-changed :mode :rows}}
      {:fx/type     :toggle-button
       :style-class ["toggle-button" "right-pill"]
       :text        (i18n/tr ctx :options/by-size)
       :selected    (= mode :bytes)
       :on-action   {:event/type ::state/mode-changed :mode :bytes}}]}))

(defn preset-row
  "The row-count shortcuts, each saying what it is for. A bare 65,000 tells a
   non-expert nothing; 65,000 — old Excel limit tells them whether they want it."
  [{:keys [mode] :as st}]
  (let [ctx     (state/ctx st)
        rows?   (= mode :rows)
        presets (if rows? state/row-presets state/size-presets)]
    {:fx/type   :h-box
     :spacing   6
     :alignment :center-left
     :children
     (into [{:fx/type :label :style-class ["hint"]
             :text    (str (i18n/tr ctx :options/common-sizes) ":")}]
           (for [{:keys [value label-key]} presets]
             {:fx/type     :button
              :style-class ["button" "small"]
              :text        (i18n/tr ctx label-key
                                    (if rows? (i18n/number ctx value) (str value)))
              :on-action   {:event/type ::state/preset-chosen :value value}}))}))

(defn value-row
  [{:keys [mode rows-text size-text] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type   :h-box
     :spacing   8
     :alignment :center-left
     :children
     [{:fx/type         :text-field
       :pref-width      120
       :text            (if (= mode :rows) rows-text size-text)
       :on-text-changed (if (= mode :rows)
                          {:event/type ::state/rows-changed :event/value-key :text}
                          {:event/type ::state/size-changed :event/value-key :text})}
      {:fx/type :label
       :text    (i18n/tr ctx (if (= mode :rows) :options/rows-suffix :options/size-suffix))}]}))

(defn plan-callout
  [st]
  (let [ctx     (state/ctx st)
        problem (state/blocking-problem st)]
    (if problem
      (callout {:kind :danger :body (fmt/message ctx problem)})
      (let [plan (state/current-plan st)]
        (callout {:kind     (if (:warning plan) :warning :accent)
                  :body     (fmt/plan-sentence ctx plan)
                  :headline (fmt/message ctx (:warning plan))})))))

(defn header-options
  [{:keys [has-header? include-header?] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type :v-box
     :spacing 8
     :children
     [{:fx/type             :check-box
       :text                (i18n/tr ctx :options/has-header)
       :selected            (boolean has-header?)
       :on-selected-changed {:event/type ::state/header-toggled :event/value-key :selected}}
      {:fx/type             :check-box
       :text                (i18n/tr ctx :options/repeat-header)
       :selected            (boolean include-header?)
       :disable             (not has-header?)
       :v-box/margin        {:left 24}
       :on-selected-changed {:event/type ::state/include-header-toggled
                             :event/value-key :selected}}]}))

;; ── Output location ─────────────────────────────────────────────────────────

(defn naming-example
  [{:keys [survey template] :as st}]
  (let [ctx   (state/ctx st)
        base  (some-> ^File (:file survey) .getName (str/replace #"\.[^.]*$" ""))
        names (naming/output-names {:template template :base base
                                    :extension "csv" :file-count 2})]
    (i18n/tr ctx :output/named (first names) (second names))))

(defn output-row
  [{:keys [out-dir template] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type :v-box
     :spacing 6
     :children
     (compact
      [{:fx/type   :h-box
        :spacing   10
        :alignment :center-left
        :children
        [{:fx/type      :label
          :h-box/hgrow  :always
          :max-width    Double/MAX_VALUE
          :style-class  ["path-label"]
          :text-overrun :leading-ellipsis
          :text         (path-of out-dir)}
         {:fx/type   :button
          :text      (i18n/tr ctx :action/change)
          :on-action {:event/type ::state/browse-output-requested}}]}
       (when (nil? (naming/template-problem template))
         {:fx/type :label :style-class ["hint"] :text (naming-example st)})

       ;; What is already at the destination, said before Split is pressed
       ;; rather than at the moment something is about to be replaced. A folder
       ;; chosen by mistake shows up here, while it is still harmless.
       (let [{:keys [exists? csv-count]} (:out-dir-info st)]
         (cond
           (and exists? (pos? (long (or csv-count 0))))
           {:fx/type     :label
            :style-class ["hint" "caution"]
            :wrap-text   true
            :text        (i18n/trn ctx :output/existing-csv csv-count
                                   (i18n/number ctx csv-count))}

           (and (some? (:out-dir-info st)) (not exists?))
           {:fx/type :label :style-class ["hint"] :wrap-text true
            :text    (i18n/tr ctx :output/new-folder)}

           :else nil))])}))

;; ── Advanced ────────────────────────────────────────────────────────────────

(defn advanced-panel
  [{:keys [template survey charset-override excel-safe?] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type :v-box
     :spacing 12
     :children
     (compact
      [{:fx/type :v-box
        :spacing 4
        ;; compact, not a bare vector: a `when` that does not fire leaves a nil
        ;; child, and cljfx has no lifecycle for nil. The pure view tests cannot
        ;; see this — only materialising the widgets does.
        :children
        (compact
         [{:fx/type :label :text (i18n/tr ctx :advanced/pattern-label)}
          {:fx/type         :text-field
           :text            (str template)
           :on-text-changed {:event/type ::state/template-changed :event/value-key :text}}
          {:fx/type :label :style-class ["hint"] :wrap-text true
           :text    (i18n/tr ctx :advanced/pattern-hint)}
          (when-let [problem (naming/template-problem template)]
            {:fx/type :label :style-class ["hint"] :wrap-text true
             :text    (fmt/message ctx problem)})])}

       {:fx/type :v-box
        :spacing 4
        :children
        [{:fx/type             :check-box
          :text                (i18n/tr ctx :advanced/excel-safe)
          :selected            (boolean excel-safe?)
          :on-selected-changed {:event/type ::state/excel-safe-toggled
                                :event/value-key :selected}}
         {:fx/type :label :style-class ["hint"] :wrap-text true
          :text    (i18n/tr ctx :advanced/excel-safe-hint)}]}

       {:fx/type :v-box
        :spacing 4
        :children
        [{:fx/type :label :text (i18n/tr ctx :advanced/delimiter-label)}
         {:fx/type          :choice-box
          :value            (i18n/tr ctx (:label-key (or (first (filter #(= (:delimiter-override st) (:value %))
                                                                        state/selectable-delimiters))
                                                         (first state/selectable-delimiters))))
          :items            (mapv #(i18n/tr ctx (:label-key %)) state/selectable-delimiters)
          :on-value-changed {:event/type ::delimiter-picked :event/value-key :label}}
         {:fx/type :label :style-class ["hint"] :wrap-text true
          :text    (str (i18n/tr ctx :advanced/delimiter-detected
                                 (i18n/tr ctx (separator-name-key (:delimiter survey))))
                        " " (i18n/tr ctx :advanced/delimiter-hint))}]}

       {:fx/type :v-box
        :spacing 4
        :children
        [{:fx/type :label :text (i18n/tr ctx :advanced/encoding-label)}
         {:fx/type          :choice-box
          :value            (or charset-override state/detected-charset)
          :items            state/selectable-charsets
          :on-value-changed {:event/type ::state/charset-override-changed
                             :event/value-key :choice}}
         {:fx/type :label :style-class ["hint"] :wrap-text true
          :text    (i18n/tr ctx :advanced/encoding-hint
                            (get-in survey [:encoding :label]))}]}])}))

;; ── Ready ───────────────────────────────────────────────────────────────────

;; Defined below with the rest of the result wording, but shown at the top of
;; the ready screen.
(declare result-panel)

(defn ready-body
  [{:keys [advanced-open? error] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type :v-box
     :children
     (compact
      [(result-panel st)
       (file-card st)
       (when error (callout {:kind :danger :body (fmt/message ctx error)}))
       {:fx/type :label :style-class ["section-label"] :text (i18n/tr ctx :options/split-into)}
       (mode-toggle st)
       {:fx/type :v-box :v-box/margin {:top 10} :spacing 8
        :children [(value-row st) (preset-row st) (plan-callout st)]}
       {:fx/type :v-box :v-box/margin {:top 14} :children [(header-options st)]}
       {:fx/type :label :style-class ["section-label"] :text (i18n/tr ctx :output/save-to)}
       (output-row st)
       (disclosure {:open? advanced-open?
                    :text  (str (i18n/tr ctx :advanced/label) " "
                                (i18n/tr ctx :advanced/summary))
                    :event {:event/type ::state/advanced-toggled}})
       (when advanced-open? (advanced-panel st))])}))

;; ── Splitting ───────────────────────────────────────────────────────────────

(defn eta-text
  "How much longer this will take, from how much has been done so far. Returns
   nil until there is enough evidence to be worth saying."
  [ctx {:keys [rows-done elapsed-ms]} total-rows]
  (let [done    (long (or rows-done 0))
        elapsed (long (or elapsed-ms 0))
        total   (long (or total-rows 0))]
    (when (and (pos? done) (> elapsed 1500) (> total done))
      (let [remaining (- total done)
            per-row   (/ (double elapsed) done)]
        (i18n/tr ctx :splitting/eta (fmt/duration ctx (long (* remaining per-row))))))))

(defn splitting-body
  [{:keys [survey progress] :as st}]
  (let [ctx   (state/ctx st)
        total (state/data-rows st)
        done  (long (:rows-done progress 0))]
    {:fx/type :v-box
     :spacing 10
     :children
     (compact
      [{:fx/type :label
        :text    (i18n/tr ctx :splitting/headline
                          (some-> ^File (:file survey) .getName))}
       {:fx/type   :progress-bar
        :max-width Double/MAX_VALUE
        :progress  (if (pos? total) (min 1.0 (/ (double done) total)) -1.0)}
       {:fx/type :h-box
        :children
        (compact
         [{:fx/type     :label
           :h-box/hgrow :always
           :max-width   Double/MAX_VALUE
           :style-class ["hint"]
           :text        (fmt/progress-sentence ctx progress total)}
          (when-let [eta (eta-text ctx progress total)]
            {:fx/type :label :style-class ["hint"] :text eta})])}])}))

;; ── Finished ────────────────────────────────────────────────────────────────

(defn details-log
  [{:keys [result] :as st}]
  (let [ctx (state/ctx st)]
    (str/join "\n"
              (for [{:keys [^File file rows]} (:written result)]
                (i18n/tr ctx :done/log-line (.getName file) (i18n/number ctx rows))))))

(defn result-panel
  "What the last split did, shown above the options rather than on a screen of
   its own.

   There used to be a separate finished screen, which was a dead end: the only
   way on was to start again with another file, so adjusting the row count and
   re-running meant re-choosing the same file. Keeping the options in place and
   putting the outcome above them removes a screen and the dead end together."
  [{:keys [result details-open?] :as st}]
  (let [ctx     (state/ctx st)
        trashed (count (:trashed result))
        left    (count (:left-behind result))]
    (when result
      {:fx/type      :v-box
       :spacing      6
       ;; Matches the rhythm the section labels set. Without it the details
       ;; panel sits almost flush against the file card below.
       :v-box/margin {:bottom 14}
       :children
       (compact
        [(callout {:kind     (if (:cancelled? result) :warning :success)
                   :headline (fmt/completion-sentence ctx result)
                   :body     (str/join
                              " "
                              (cond-> []
                                (pos? trashed)
                                (conj (i18n/trn ctx :done/trashed trashed
                                                (i18n/number ctx trashed)))
                                (pos? left)
                                (conj (i18n/trn ctx :done/left-behind left
                                                (i18n/number ctx left)))))})
         (disclosure {:open? details-open?
                      :text  (i18n/tr ctx :done/details)
                      :event {:event/type ::state/details-toggled}})
         (when details-open?
           {:fx/type        :text-area
            :style-class    ["text-area" "details-log"]
            :editable       false
            :pref-row-count 8
            :text           (details-log st)})])})))

;; ── Overlays ────────────────────────────────────────────────────────────────

(defn overlay
  "The dim backdrop and centred card shared by every dialog."
  [children]
  {:fx/type     :stack-pane
   :style-class ["overlay"]
   :children
   [{:fx/type              :v-box
     :style-class          ["dialog-card"]
     :max-height           Double/NEGATIVE_INFINITY
     :stack-pane/alignment :center
     :children             (compact children)}]})

(defn collision-dialog
  [{:keys [collisions out-dir] :as st}]
  (let [ctx   (state/ctx st)
        shown (take 4 collisions)
        extra (- (count collisions) (count shown))
        total (count collisions)]
    (overlay
     [{:fx/type :label :style-class ["label" "title"] :wrap-text true
       :text    (i18n/trn ctx :clash/title total (i18n/number ctx total))}
      ;; The folder, spelled out. If the wrong one was chosen this is where it
      ;; becomes obvious, and it is the last moment at which that costs nothing.
      {:fx/type :label :style-class ["hint"] :wrap-text true
       :text    (i18n/tr ctx :clash/folder (path-of out-dir))}
      {:fx/type :label :wrap-text true :text (i18n/tr ctx :clash/body)}
      {:fx/type :label :style-class ["file-list"] :max-width Double/MAX_VALUE
       :text    (str/join "\n"
                          (concat (map (fn [^File f] (.getName f)) shown)
                                  (when (pos? extra)
                                    [(i18n/tr ctx :clash/more (i18n/number ctx extra))])))}
      ;; Exactly what "Replace them" will do, including to files not listed.
      {:fx/type :label :style-class ["hint"] :wrap-text true
       :text    (i18n/tr ctx (if (desktop/trash-supported?)
                               :clash/replace-note
                               :clash/replace-note-no-trash))}
      {:fx/type   :h-box
       :spacing   8
       :alignment :center-right
       :children
       [{:fx/type   :button
         :text      (i18n/tr ctx :action/cancel)
         :on-action {:event/type ::state/collision-resolved :choice :cancel}}
        {:fx/type     :button
         :style-class ["button" "danger"]
         :text        (i18n/tr ctx :action/replace)
         :on-action   {:event/type ::state/collision-resolved :choice :replace}}
        ;; The safe way out is the accented one, and the one keyboard focus
        ;; lands on. Nobody should reach a deletion by pressing Return.
        {:fx/type     :button
         :style-class ["button" "accent"]
         :default-button true
         :text        (i18n/tr ctx :action/new-folder)
         :on-action   {:event/type ::new-folder-requested}}]}])))

(defn about-dialog
  [{:keys [theme] :as st}]
  (let [ctx       (state/ctx st)
        languages (i18n/languages)]
    (overlay
     [{:fx/type :label :style-class ["label" "title"]
       :text    (i18n/tr ctx :about/title (branding/app-name))}
      ;; A rebranded application keeps whatever tagline its owner set, in their
      ;; words. The default one is ours, so it is translated like the rest.
      {:fx/type :label :style-class ["hint"] :wrap-text true
       :text    (or (branding/value :tagline) (i18n/tr ctx :about/tagline))}
      {:fx/type :label :text (i18n/tr ctx :about/version (branding/build-label))}
      {:fx/type :label :style-class ["hint"] :wrap-text true :text (i18n/tr ctx :about/licence)}
      {:fx/type :label :style-class ["hint"] :wrap-text true :text (i18n/tr ctx :about/notices)}
      {:fx/type :separator}

      {:fx/type :label :text (i18n/tr ctx :about/language)}
      {:fx/type          :choice-box
       :value            (:language ctx)
       :items            (mapv :name languages)
       :on-value-changed {:event/type ::state/language-changed
                          :event/value-key :language-name}}
      (when-not (:reviewed? ctx)
        {:fx/type :label :style-class ["hint"] :wrap-text true
         :text    (i18n/tr ctx :about/unreviewed)})

      {:fx/type :label :text (i18n/tr ctx :about/theme)}
      {:fx/type :h-box
       :children
       (for [[value theme-key style] [[:system :about/theme-system "left-pill"]
                                      [:light :about/theme-light "center-pill"]
                                      [:dark :about/theme-dark "right-pill"]]]
         {:fx/type     :toggle-button
          :style-class ["toggle-button" style]
          :text        (i18n/tr ctx theme-key)
          :selected    (= theme value)
          :on-action   {:event/type ::state/theme-changed :theme value}})}

      ;; Quit is not here. It was, and that was wrong: an About box is not
      ;; where anyone looks for the way out. It lives in the File menu.
      {:fx/type   :h-box
       :alignment :center-right
       :children  [{:fx/type     :button
                    :style-class ["button" "accent"]
                    :text        (i18n/tr ctx :action/close)
                    :on-action   {:event/type ::state/dialog-closed}}]}])))

(defn startup-error-window
  "Shown instead of the main window when a translation the user supplied cannot
   be trusted.

   Deliberately in English and deliberately not translated: the translations are
   the very thing that is wrong, so none of them can be relied upon to explain
   it. Continuing in English is offered as well as quitting, so that a bad file
   dropped into the folder cannot leave the application permanently unusable."
  [{:keys [problems]}]
  {:fx/type          :stage
   :title            (str (branding/app-name) " — translation problem")
   :showing          true
   :width            620
   :height           420
   :on-close-request {:event/type ::quit-requested}
   :scene
   {:fx/type     :scene
    :stylesheets (branding/stylesheets)
    :root
    {:fx/type     :v-box
     :style-class ["root" "window-body"]
     :spacing     10
     :children
     [{:fx/type :label :style-class ["label" "title"]
       :text    "A translation could not be used"}
      {:fx/type :label :wrap-text true
       :text    (str "One or more of the translation files in your languages "
                     "folder was refused. Nothing has been changed and no data "
                     "is at risk — the application simply will not show wording "
                     "it cannot vouch for.")}
      {:fx/type      :text-area
       :v-box/vgrow  :always
       :editable     false
       :style-class  ["text-area" "details-log"]
       :text         (str/join "\n\n" problems)}
      {:fx/type   :h-box
       :spacing   8
       :alignment :center-left
       :children
       [{:fx/type   :button
         :text      "Continue in English"
         :on-action {:event/type ::continue-in-english}}
        {:fx/type :region :h-box/hgrow :always}
        {:fx/type     :button
         :style-class ["button" "accent"]
         :text        "Quit"
         :on-action   {:event/type ::quit-requested}}]}]}}})

(def help-topics
  "Questions a non-expert actually asks, in the order they come up."
  [[:help/q-header :help/a-header]
   [:help/q-quoted :help/a-quoted]
   [:help/q-damaged :help/a-damaged]
   [:help/q-encoding :help/a-encoding]
   [:help/q-excel :help/a-excel]
   [:help/q-replace :help/a-replace]])

(defn help-dialog
  [st]
  (let [ctx (state/ctx st)]
    (overlay
     [{:fx/type :label :style-class ["label" "title"] :text (i18n/tr ctx :help/title)}
      {:fx/type        :scroll-pane
       :fit-to-width   true
       :pref-height    360
       :style-class    ["scroll-pane" "edge-to-edge"]
       :content        {:fx/type  :v-box
                        :spacing  14
                        :children (vec (for [[q a] help-topics]
                                         {:fx/type :v-box
                                          :spacing 3
                                          :children
                                          [{:fx/type :label :style-class ["label" "headline"]
                                            :wrap-text true :text (i18n/tr ctx q)}
                                           {:fx/type :label :style-class ["hint"]
                                            :wrap-text true :text (i18n/tr ctx a)}]}))}}
      {:fx/type   :h-box
       :alignment :center-right
       :children  [{:fx/type     :button
                    :style-class ["button" "accent"]
                    :text        (i18n/tr ctx :action/close)
                    :on-action   {:event/type ::state/dialog-closed}}]}])))

;; ── Footer ──────────────────────────────────────────────────────────────────

(defn footer
  [{:keys [phase result] :as st}]
  (let [ctx    (state/ctx st)
        spacer {:fx/type :region :h-box/hgrow :always}]
    {:fx/type     :h-box
     :style-class ["footer-bar"]
     :alignment   :center-right
     :spacing     8
     :children
     (case phase
       (:empty :scanning) [spacer]

       ;; After a split the options are still here, so Split file stays the
       ;; primary action and re-running is one press. The extra two only appear
       ;; once there is a result to reveal or to move on from.
       :ready (compact
               [spacer
                (when result
                  {:fx/type   :button
                   :text      (i18n/tr ctx :action/split-again)
                   :on-action {:event/type ::state/reset}})
                (when result
                  {:fx/type   :button
                   :text      (i18n/tr ctx (desktop/reveal-label-key))
                   :on-action {:event/type ::state/reveal-requested}})
                {:fx/type     :button
                 :style-class ["button" "accent"]
                 :text        (i18n/tr ctx :action/split)
                 :disable     (not (state/ready? st))
                 :on-action   {:event/type ::state/split-requested}}])

       :splitting [spacer
                   {:fx/type   :button
                    :text      (i18n/tr ctx :action/cancel)
                    :on-action {:event/type ::state/cancel-requested}}])}))

;; ── Root ────────────────────────────────────────────────────────────────────

(defn body
  [{:keys [phase] :as st}]
  (case phase
    :empty     (empty-body st)
    :scanning  (scanning-body st)
    :ready     (ready-body st)
    :splitting (splitting-body st)))

(defn content
  "The window's contents.

   Dropping a file is handled here rather than on the drop zone, so a new file
   can be dropped anywhere at any time — including when one is already open,
   which previously forced the user through the Browse dialog instead. No new
   control was needed; a restriction was removed."
  [{:keys [dialog drag-over?] :as st}]
  {:fx/type :stack-pane
   ;; "root" is listed explicitly and must stay listed. JavaFX adds it to
   ;; whatever node becomes the scene root, and every AtlantaFX colour —
   ;; -color-bg-default, -color-fg-default, the accents — is defined on .root.
   ;; Because this vector is recomputed when a drag starts, cljfx replaces the
   ;; whole style-class list, and leaving "root" out of it destroyed the class
   ;; the first time anyone dragged a file onto the window: every lookup then
   ;; failed, backgrounds disappeared and text fell back to black, for the rest
   ;; of the session.
   :style-class     (cond-> ["root" "drag-target"] drag-over? (conj "active"))
   :on-drag-over    {:event/type ::drag-over}
   :on-drag-exited  {:event/type ::state/drag-exited}
   :on-drag-dropped {:event/type ::drag-dropped}
   :children
   (compact
    [{:fx/type :v-box
      :children
      [(menu-bar (state/ctx st) (= :mac (desktop/os)))
       {:fx/type      :scroll-pane
        :v-box/vgrow  :always
        :fit-to-width true
        :style-class  ["scroll-pane" "edge-to-edge"]
        :content      {:fx/type     :v-box
                       :style-class ["window-body"]
                       :children    [(header-bar (state/ctx st)) (body st)]}}
       (footer st)]}
     (case dialog
       :collisions (collision-dialog st)
       :about      (about-dialog st)
       :help       (help-dialog st)
       nil)])})

(def default-window {:width 720 :height 660})

(defn remembered-window
  "Where the window was left last time, if that is still a sensible place for
   it. A size saved on a monitor that is no longer attached, or a nonsense one
   from a corrupted settings file, is ignored rather than obeyed."
  [{:keys [width height x y]}]
  (let [sane? (fn [v lo hi] (and (number? v) (<= lo v hi)))]
    (cond-> {}
      (and (sane? width 400 20000) (sane? height 300 20000))
      (assoc :width (double width) :height (double height))

      (and (sane? x -20000 20000) (sane? y -20000 20000))
      (assoc :x (double x) :y (double y)))))

(defn root
  [st]
  (merge
   {:fx/type          :stage
    :title            (branding/app-name)
    :showing          true
    :min-width        520
    :min-height       460
    :on-close-request {:event/type ::close-requested}}
   default-window
   (remembered-window (:window st))
   {:scene {:fx/type     :scene
            :stylesheets (branding/stylesheets)
            :root        (content st)}}))
