(ns csv-cleaver.i18n
  "Language, and everything else that depends on where the user is.

   No translated text appears anywhere in this project's source. Every string
   lives in resources/i18n/<tag>.edn, which means a translator can correct a
   phrase, or add a language, without opening a Clojure file — and a rebuild is
   the only step between their edit and a working application.

   A `context` map is threaded through the pure code in place of a global. It
   carries the chosen strings and the java.util.Locale, so the same functions
   that render the window also decide whether a thousands separator is a comma
   or a full stop.

   Numbers shown to the user follow the display language: a German user reads
   1.204.338. Numbers that end up in file names do not — those stay ASCII so
   that a file manager can still sort them. That distinction is the whole
   reason the formatting helpers take a context rather than reading the JVM's
   default locale, which is what produced 1.204.338 in an English window."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.text NumberFormat)
   (java.util Locale)))

(def supported
  "Language tags shipped with the application, in the order they are offered."
  ["en" "es" "fr" "de" "it" "pt" "zh" "ja"])

(def fallback-tag "en")

;; ── Translations supplied by the user ───────────────────────────────────────
;;
;; A folder beside the settings file may hold further translations, so a
;; language can be added without rebuilding. Anything read from there is
;; untrusted and validated before it is allowed anywhere near the window.
;;
;; What such a file can and cannot do is worth being precise about. It cannot
;; execute anything: clojure.edn/read-string evaluates nothing and honours no
;; reader tags, unlike clojure.core/read-string. What it can do is put words on
;; the screen, and words on the screen can mislead — which is why an external
;; file may only replace phrases the application already has, never invent new
;; ones, and why the About box says where a translation came from.

(def max-bundle-bytes
  "A translation is a few tens of kilobytes. Anything far larger is not one."
  262144)

(def max-string-length
  "Longest phrase accepted. The longest shipped one is a help answer of a few
   hundred characters."
  2000)

(def forbidden-characters
  "Control characters other than newline and tab, and the bidirectional
   overrides that can make displayed text read in the opposite order to the
   characters actually stored — a known way to make a label say one thing and
   mean another."
  #"[\p{Cntrl}&&[^\n\t]]|[‪-‮⁦-⁩]")

(defonce ^:private external
  (atom {:dir nil :bundles {} :problems []}))

(defn bundle-resource
  "The EDN resource for `tag`, or nil when there is no such translation."
  [tag]
  (io/resource (str "i18n/" tag ".edn")))

(defn- read-shipped
  [tag]
  (try
    (when-let [url (bundle-resource tag)]
      (let [parsed (edn/read-string (slurp url))]
        (when (map? parsed) parsed)))
    (catch Exception _ nil)))

(defn read-bundle
  "Read one translation.

   Where a file supplied by the user has the same tag as a shipped one, the two
   are layered rather than swapped: the supplied phrases win, and the shipped
   ones fill in the rest. Correcting a single German word should not discard the
   other two hundred."
  [tag]
  (let [shipped  (read-shipped tag)
        supplied (get-in @external [:bundles tag])]
    (cond
      (nil? supplied) shipped
      (nil? shipped)  supplied
      :else           {:meta    (merge (:meta shipped) (:meta supplied))
                       :strings (merge (:strings shipped) (:strings supplied))})))

(def hidden-tags
  "Shipped but not offered: the Easter eggs. tlh is Klingon's real ISO 639-3
   code; Vulcan has no ISO code, so vuh follows the fan convention. Both
   bundles are deliberately partial — English shows through underneath, as it
   does for any missing phrase — which is also why they are exempt from the
   completeness tests that every visible language must pass."
  ["tlh" "vuh"])

(defonce ^:private revealed
  ;; Whether the hidden languages are on offer this session. Flipped by
  ;; --hidden-languages on the command line, or by holding Alt/Option while
  ;; opening the About dialog.
  (atom false))

(defn reveal-hidden! [] (reset! revealed true))
(defn conceal-hidden!
  "For tests, which must not leak a revealed state into each other."
  [] (reset! revealed false))
(defn hidden-revealed? [] @revealed)

(defn available-tags
  "Every language that can be chosen: those shipped, any valid ones the user
   has supplied, and — once revealed — the Easter eggs."
  []
  (vec (distinct (concat supported
                         (when @revealed hidden-tags)
                         (sort (keys (:bundles @external)))))))

(defn- phrase-strings
  "Every string an entry could put on screen."
  [v]
  (cond (string? v) [v]
        (map? v)    (filter string? (vals v))
        :else       []))

(defn- placeholders [v]
  (reduce into #{} (map #(set (re-seq #"\{\d+\}" %)) (phrase-strings v))))

(defn validate-bundle
  "Everything wrong with `bundle`, as readable sentences. An empty result means
   it is safe to show to someone.

   `reference` is the English bundle. An external translation may only replace
   wording the application already has — it can never introduce a phrase, which
   is what stops a file dropped into the folder from inventing a prompt that
   asks for something the application would never ask for."
  [tag bundle reference]
  (let [known (set (keys (:strings reference)))]
    (cond
      (not (map? bundle))
      [(str tag ": this is not a translation file.")]

      (not (map? (:strings bundle)))
      [(str tag ": there is no :strings section, so there is nothing to use.")]

      :else
      (let [strings (:strings bundle)
            unknown (sort (remove known (keys strings)))]
        (cond-> []
          (str/blank? (str (get-in bundle [:meta :name])))
          (conj (str tag ": :meta :name is missing, so the language has nothing "
                     "to be called in the language picker."))

          (seq unknown)
          (conj (str tag ": contains " (count unknown) " phrase(s) the application "
                     "does not use (" (str/join ", " (map str (take 5 unknown)))
                     (when (> (count unknown) 5) ", …")
                     "). A translation may only replace existing wording."))

          :always
          (into
           (for [[k v] (sort-by (comp str key) strings)
                 :when (known k)
                 problem
                 (concat
                  (when-not (or (string? v) (map? v))
                    ["is neither a phrase nor a set of singular and plural forms"])
                  (when (and (map? v) (not (contains? v :other)))
                    ["has no :other form, so there is nothing to show for a plural"])
                  (when (some #(> (count %) max-string-length) (phrase-strings v))
                    [(str "is longer than " max-string-length " characters")])
                  (when (some #(re-find forbidden-characters %) (phrase-strings v))
                    ["contains control or text-direction characters, which can make
                      displayed text read differently from what is stored"])
                  (let [expected (placeholders (get (:strings reference) k))
                        actual   (placeholders v)]
                    (when (not= expected actual)
                      [(str "should use "
                            (if (seq expected) (str/join " " (sort expected)) "no placeholders")
                            " but uses "
                            (if (seq actual) (str/join " " (sort actual)) "none"))])))]
             (str tag " → " k ": " (str/replace problem #"\s+" " ")))))))))

(defn- tag-from-filename
  "The language tag a file name stands for, or nil when the name is not one.
   Restricted to plain letters, so nothing can reach outside the folder or
   masquerade as a path."
  [^String filename]
  (let [stem (str/lower-case (str/replace filename #"\.edn$" ""))]
    (when (re-matches #"[a-z]{2,3}" stem) stem)))

(defn load-external!
  "Read every .edn in `dir` as a translation, keep the valid ones, and report
   the rest. Returns {:loaded [tags] :problems [sentences]}.

   Called once at startup. Problems are returned rather than thrown so that the
   caller can decide what to do about them — which, for this application, is to
   refuse to start in that language and say why."
  [dir]
  (let [dir       (when dir (io/file (str dir)))
        reference (read-shipped fallback-tag)
        files     (when (and dir (.isDirectory dir))
                    (->> (.listFiles dir)
                         (filter (fn [^File f] (.isFile f)))
                         (filter (fn [^File f] (str/ends-with? (.getName f) ".edn")))
                         (sort-by (fn [^File f] (.getName f)))))
        outcomes
        (for [^File f files]
          (let [name (.getName f)
                tag  (tag-from-filename name)]
            (cond
              (nil? tag)
              {:problems [(str name ": the file name should be a language code "
                               "such as it.edn.")]}

              (> (.length f) max-bundle-bytes)
              {:problems [(str name ": the file is larger than "
                               (quot max-bundle-bytes 1024)
                               " KB, which no translation is.")]}

              :else
              (let [parsed (try (edn/read-string (slurp f))
                                (catch Exception e
                                  {::unreadable (or (.getMessage e) "unreadable")}))]
                (if (::unreadable parsed)
                  {:problems [(str name ": the file could not be read — "
                                   (::unreadable parsed))]}
                  (let [problems (validate-bundle tag parsed reference)]
                    (if (seq problems)
                      {:problems problems}
                      {:loaded tag :bundle parsed})))))))
        loaded   (into {} (keep (fn [o] (when (:loaded o) [(:loaded o) (:bundle o)])) outcomes))
        problems (vec (mapcat :problems outcomes))]
    (reset! external {:dir dir :bundles loaded :problems problems})
    {:loaded (vec (sort (keys loaded))) :problems problems}))

(defn external-problems
  "What was wrong with the translations the user supplied, if anything."
  []
  (:problems @external))

(defn forget-external!
  "Drop everything loaded from outside. For tests."
  []
  (reset! external {:dir nil :bundles {} :problems []}))

(defn detect-tag
  "The language to start in: the system's, when we have it, otherwise English."
  ([] (detect-tag (Locale/getDefault)))
  ([^Locale locale]
   (let [language (str/lower-case (str (.getLanguage locale)))]
     (if (some #{language} supported) language fallback-tag))))

(defn normalise-tag
  "Accept what a person might type on the command line — en, EN, en-GB, zh_CN —
   and return an available tag, or nil when we have no translation for it.
   A language the user has supplied counts, so --locale it works for one."
  [tag]
  (when tag
    (let [language (-> (str tag) (str/replace "_" "-") (str/split #"-") first
                       str/lower-case)]
      (some #{language} (available-tags)))))

(defn context
  "Everything language-dependent, in one map.

   English is merged underneath the chosen language, so a key a translator has
   not reached yet shows in English rather than as a blank or a raw keyword."
  ([] (context (detect-tag)))
  ([tag]
   (let [tag      (or (normalise-tag tag) fallback-tag)
         english  (read-bundle fallback-tag)
         chosen   (if (= tag fallback-tag) english (read-bundle tag))]
     {:tag       tag
      :locale    (Locale/forLanguageTag tag)
      :language  (get-in chosen [:meta :name] tag)
      :reviewed? (boolean (get-in chosen [:meta :reviewed?]))
      :strings   (merge (:strings english) (:strings chosen))})))

(defn languages
  "Every available language as {:tag :name :reviewed? :external?}, for the
   picker and the About box. External translations are labelled as such: a
   phrase the application did not ship should say so."
  []
  (mapv (fn [tag]
          (let [bundle (read-bundle tag)]
            {:tag       tag
             :name      (get-in bundle [:meta :name] tag)
             :reviewed? (boolean (get-in bundle [:meta :reviewed?]))
             :external? (contains? (:bundles @external) tag)}))
        (available-tags)))

(defn tag-for-name
  "The language tag whose name is `name`, for turning a picker selection back
   into something the rest of the application understands."
  [name]
  (some #(when (= name (:name %)) (:tag %)) (languages)))

;; ── Looking up a phrase ─────────────────────────────────────────────────────

(defn interpolate
  "Replace {0}, {1} … in `template` with `args`.

   Deliberately not java.text.MessageFormat, which gives an apostrophe a
   special meaning — and French text is full of apostrophes, so every other
   string would need doubling and a translator would eventually forget."
  [template args]
  (reduce (fn [text [index value]]
            (str/replace text (str "{" index "}") (str value)))
          (str template)
          (map-indexed vector args)))

(defn plural-category
  "Which of :one or :other applies to `n` in this language.

   Only the shipped languages are covered, and only where they differ: French
   and Portuguese treat zero as singular, Chinese and Japanese have no plural
   at all, and the rest use the singular for exactly one."
  [tag n]
  (let [n (long n)]
    (case (str tag)
      ("zh" "ja") :other
      ;; French and Portuguese treat zero as singular; CLDR pt covers both
      ;; zero and one under :one.
      ("fr" "pt") (if (< n 2) :one :other)
      (if (= n 1) :one :other))))

(defn tr
  "The phrase for `k`, with any {0}, {1} … filled in from `args`.

   A key with no translation anywhere returns a visible marker rather than an
   empty string or an exception: an obviously wrong window is easier to notice
   and fix than a silently blank one."
  [ctx k & args]
  (let [entry (get-in ctx [:strings k])]
    (cond
      (string? entry) (interpolate entry args)
      (map? entry)    (interpolate (or (:other entry) (:one entry)) args)
      :else           (str "⟦" (symbol k) "⟧"))))

(defn trn
  "The phrase for `k` in the form that suits `n`.

   `n` chooses between the singular and plural wordings and is not interpolated
   — the caller passes what should appear, which for a count is nearly always
   the number written out for this language. Interpolating `n` itself would
   quietly put an unformatted 1203 on screen where 1,203 was intended."
  [ctx k n & args]
  (let [entry    (get-in ctx [:strings k])
        category (plural-category (:tag ctx) n)]
    (if (map? entry)
      (interpolate (or (get entry category) (:other entry) (:one entry)) args)
      (apply tr ctx k args))))

;; ── Numbers ─────────────────────────────────────────────────────────────────

(defn ^:private locale-of ^Locale [ctx]
  (or (:locale ctx) Locale/ROOT))

(defn number
  "A whole number as this language writes it: 1,204,338 or 1.204.338."
  [ctx n]
  (.format (NumberFormat/getIntegerInstance (locale-of ctx)) (long n)))

(defn decimal
  "A number with `places` decimals, in this language's conventions."
  [ctx value places]
  (let [f (doto (NumberFormat/getNumberInstance (locale-of ctx))
            (.setMinimumFractionDigits places)
            (.setMaximumFractionDigits places))]
    (.format f (double value))))

(defn plain-number
  "A whole number with ASCII digits and no separators, for file names and
   anywhere else a machine will read it back."
  [n]
  (String/format Locale/ROOT "%d" (to-array [(long n)])))
