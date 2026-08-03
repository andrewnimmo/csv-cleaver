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
  [ctx]
  {:fx/type             :menu-bar
   :use-system-menu-bar true
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
    {:fx/type         :v-box
     :style-class     (cond-> ["drop-zone"] drag-over? (conj "active"))
     :on-drag-over    {:event/type ::drag-over}
     :on-drag-exited  {:event/type ::state/drag-exited}
     :on-drag-dropped {:event/type ::drag-dropped}
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
       [{:fx/type :label :style-class ["file-name"] :text (if file (.getName file) "")}
        {:fx/type :label :style-class ["hint"]
         :text    (if (pos? rows)
                    (i18n/trn ctx :scanning/counted rows (i18n/number ctx rows))
                    (i18n/tr ctx :scanning/checking))}
        {:fx/type :progress-bar :max-width Double/MAX_VALUE :progress -1.0}]}]}))

;; ── File card ───────────────────────────────────────────────────────────────

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
      (when-let [delimiter-key (case (:delimiter survey)
                                 \; :file/separated-semicolons
                                 \tab :file/separated-tabs
                                 \| :file/separated-pipes
                                 nil)]
        {:fx/type chip :text (i18n/tr ctx delimiter-key)})
      (if damage
        {:fx/type chip :kind :warn :text (:headline damage)}
        {:fx/type chip :kind :good :text (i18n/tr ctx :file/healthy)})])))

(defn header-question
  "Shown only when the detection genuinely cannot tell whether the first row
   names the columns.

   The row itself is displayed, because someone who has never heard the word
   \"header\" can still recognise id · name · city as labels the moment they see
   them. Asking in the abstract would get a shrug; asking about what is on the
   screen gets an answer."
  [{:keys [survey] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type     :v-box
     :style-class ["callout" "warning"]
     :spacing     6
     :children
     [{:fx/type :label :style-class ["label"] :wrap-text true
       :text    (i18n/tr ctx :header/question)}
      {:fx/type :label :style-class ["file-list"] :max-width Double/MAX_VALUE
       :text    (str (i18n/tr ctx :header/first-row) ":  "
                     (str/join "   ·   " (:first-row survey)))}
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
          [{:fx/type :label :style-class ["file-name"] :text (.getName file)}
           {:fx/type      :label
            :style-class  ["hint"]
            :text-overrun :leading-ellipsis
            :text         (path-of (.getParentFile (.getAbsoluteFile file)))}]}
         {:fx/type   :button
          :text      (i18n/tr ctx :action/change)
          :on-action {:event/type ::state/browse-input-requested}}]}
       {:fx/type :flow-pane :hgap 6 :vgap 6 :children (file-chips st)}
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
         {:fx/type :label :style-class ["hint"] :text (naming-example st)})])}))

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
          :text    (i18n/tr ctx :advanced/delimiter-hint)}]}

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

(defn ready-body
  [{:keys [advanced-open? error] :as st}]
  (let [ctx (state/ctx st)]
    {:fx/type :v-box
     :children
     (compact
      [(file-card st)
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

(defn done-body
  [{:keys [result survey details-open?] :as st}]
  (let [ctx    (state/ctx st)
        damage (fmt/damage-summary ctx survey)]
    {:fx/type :v-box
     :spacing 10
     :children
     (compact
      [(callout {:kind     (if (:cancelled? result) :warning :success)
                 :headline (fmt/completion-sentence ctx result)
                 :body     (:detail damage)})
       (disclosure {:open? details-open?
                    :text  (i18n/tr ctx :done/details)
                    :event {:event/type ::state/details-toggled}})
       (when details-open?
         {:fx/type        :text-area
          :style-class    ["text-area" "details-log"]
          :editable       false
          :pref-row-count 8
          :text           (details-log st)})])}))

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
  [{:keys [collisions] :as st}]
  (let [ctx   (state/ctx st)
        shown (take 4 collisions)
        extra (- (count collisions) (count shown))
        total (count collisions)]
    (overlay
     [{:fx/type :label :style-class ["title"] :wrap-text true
       :text    (i18n/trn ctx :clash/title total (i18n/number ctx total))}
      {:fx/type :label :wrap-text true :text (i18n/tr ctx :clash/body)}
      {:fx/type :label :style-class ["file-list"] :max-width Double/MAX_VALUE
       :text    (str/join "\n"
                          (concat (map (fn [^File f] (.getName f)) shown)
                                  (when (pos? extra)
                                    [(i18n/tr ctx :clash/more (i18n/number ctx extra))])))}
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
        {:fx/type     :button
         :style-class ["button" "accent"]
         :text        (i18n/tr ctx :action/new-folder)
         :on-action   {:event/type ::new-folder-requested}}]}])))

(defn about-dialog
  [{:keys [theme] :as st}]
  (let [ctx       (state/ctx st)
        languages (i18n/languages)]
    (overlay
     [{:fx/type :label :style-class ["title"]
       :text    (i18n/tr ctx :about/title (branding/app-name))}
      {:fx/type :label :style-class ["hint"] :wrap-text true
       :text    (branding/value :tagline)}
      {:fx/type :label :text (i18n/tr ctx :about/version (branding/version))}
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
     :style-class ["window-body"]
     :spacing     10
     :children
     [{:fx/type :label :style-class ["title"]
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
     [{:fx/type :label :style-class ["title"] :text (i18n/tr ctx :help/title)}
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
  [{:keys [phase] :as st}]
  (let [ctx    (state/ctx st)
        spacer {:fx/type :region :h-box/hgrow :always}]
    {:fx/type     :h-box
     :style-class ["footer-bar"]
     :alignment   :center-right
     :children
     (case phase
       (:empty :scanning) [spacer]

       :ready [spacer
               {:fx/type     :button
                :style-class ["button" "accent"]
                :text        (i18n/tr ctx :action/split)
                :disable     (not (state/ready? st))
                :on-action   {:event/type ::state/split-requested}}]

       :splitting [spacer
                   {:fx/type   :button
                    :text      (i18n/tr ctx :action/cancel)
                    :on-action {:event/type ::state/cancel-requested}}]

       :done [spacer
              {:fx/type   :button
               :text      (i18n/tr ctx :action/split-again)
               :on-action {:event/type ::state/reset}}
              {:fx/type     :button
               :style-class ["button" "accent"]
               :text        (i18n/tr ctx (desktop/reveal-label-key))
               :on-action   {:event/type ::state/reveal-requested}}])}))

;; ── Root ────────────────────────────────────────────────────────────────────

(defn body
  [{:keys [phase] :as st}]
  (case phase
    :empty     (empty-body st)
    :scanning  (scanning-body st)
    :ready     (ready-body st)
    :splitting (splitting-body st)
    :done      (done-body st)))

(defn content
  [{:keys [dialog] :as st}]
  {:fx/type :stack-pane
   :children
   (compact
    [{:fx/type :v-box
      :children
      [(menu-bar (state/ctx st))
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

(defn root
  [st]
  {:fx/type          :stage
   :title            (branding/app-name)
   :showing          true
   :width            720
   :height           660
   :min-width        520
   :min-height       460
   :on-close-request {:event/type ::close-requested}
   :scene            {:fx/type     :scene
                      :stylesheets (branding/stylesheets)
                      :root        (content st)}})
