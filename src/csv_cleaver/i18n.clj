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
   (java.text NumberFormat)
   (java.util Locale)))

(def supported
  "Language tags shipped with the application, in the order they are offered."
  ["en" "es" "fr" "de" "zh" "ja"])

(def fallback-tag "en")

(defn bundle-resource
  "The EDN resource for `tag`, or nil when there is no such translation."
  [tag]
  (io/resource (str "i18n/" tag ".edn")))

(defn read-bundle
  "Read one translation file. Returns nil when it is missing or unreadable —
   a broken translation must never stop the application from starting, it just
   means English shows through."
  [tag]
  (try
    (when-let [url (bundle-resource tag)]
      (let [parsed (edn/read-string (slurp url))]
        (when (map? parsed) parsed)))
    (catch Exception _ nil)))

(defn detect-tag
  "The language to start in: the system's, when we have it, otherwise English."
  ([] (detect-tag (Locale/getDefault)))
  ([^Locale locale]
   (let [language (str/lower-case (str (.getLanguage locale)))]
     (if (some #{language} supported) language fallback-tag))))

(defn normalise-tag
  "Accept what a person might type on the command line — en, EN, en-GB, zh_CN —
   and return a supported tag, or nil when we have no translation for it."
  [tag]
  (when tag
    (let [language (-> (str tag) (str/replace "_" "-") (str/split #"-") first
                       str/lower-case)]
      (some #{language} supported))))

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

(def ^:private language-list
  (delay
    (mapv (fn [tag]
            (let [bundle (read-bundle tag)]
              {:tag       tag
               :name      (get-in bundle [:meta :name] tag)
               :reviewed? (boolean (get-in bundle [:meta :reviewed?]))}))
          supported)))

(defn languages
  "Every shipped language as {:tag :name :reviewed?}, for the language picker.
   Read once: the view asks for this on every render."
  []
  @language-list)

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

   Only these six languages are covered, and only where they differ: French
   treats zero as singular, Chinese and Japanese have no plural at all, and the
   rest use the singular for exactly one."
  [tag n]
  (let [n (long n)]
    (case (str tag)
      ("zh" "ja") :other
      "fr"        (if (< n 2) :one :other)
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
