(ns locale-lint
  "bb locale-lint — forbid default-locale text operations outside the two
   namespaces allowed to think about locales at all.

   This is the project that taught the lesson: an English window rendering
   1204338 as 1.204.338, because clojure.core/format follows the JVM default
   locale and the development machine was set to Spanish (docs/DECISIONS.md, 9).
   The fix became csv-cleaver.i18n — display through the *chosen* locale — and
   csv-cleaver.text — machine text through Locale/ROOT. What it never got until
   now is the gate. A rule that is only remembered recurs; the same fault has
   since turned up in two sibling projects (plinth's 0,32, qr-service's Turkish
   ı in host case-folding). This lint is the gate: in `bb locale-lint`, in CI,
   and pinned by the suite (csv-cleaver.text-test), so a raw call cannot come
   back without a red build.

   Exactly two source namespaces are exempt, and that list is a design
   decision, not a convenience:

   - csv-cleaver.text: the machine-facing folds and formats themselves. Every
     call in it names Locale/ROOT.
   - csv-cleaver.i18n: the sanctioned display-locale context. It owns the one
     legitimate reading of the machine's locale — detect-tag choosing a startup
     language — and formats numbers through the locale the *user* chose, which
     is an explicit argument everywhere. Exemption is not a licence: its own
     machine-facing folds (language tags, file names) still go through
     csv-cleaver.text.

   Anywhere else, a knowingly-safe line may carry `locale-ok` with a reason,
   and the lint will leave it alone. Scope is src/ and dev/ — the test suite is
   instead run whole under tr_TR by `bb test-tr`, which catches behaviourally
   what no pattern can."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [csv-cleaver.text :as text]))

(def forbidden
  [[#"str/lower-case" "use csv-cleaver.text/lower"]
   [#"str/upper-case" "use csv-cleaver.text/upper"]
   [#"str/capitalize" "use csv-cleaver.text/capitalize"]
   [#"clojure\.string/(?:lower-case|upper-case|capitalize)" "use csv-cleaver.text/lower|upper|capitalize"]
   [#"\(format\s" "use csv-cleaver.text/fmt (Locale/ROOT) — or csv-cleaver.i18n for anything a user reads"]
   [#"\.toLowerCase\b(?![^\n]*Locale/ROOT)" "pass java.util.Locale/ROOT (or use csv-cleaver.text/lower)"]
   [#"\.toUpperCase\b(?![^\n]*Locale/ROOT)" "pass java.util.Locale/ROOT (or use csv-cleaver.text/upper)"]
   [#"String/format(?![^\n]*Locale/ROOT)" "String/format takes java.util.Locale/ROOT first (or use csv-cleaver.text/fmt)"]
   [#"DateTimeFormatter/ofPattern(?![^\n]*Locale)" "ofPattern needs an explicit locale — Locale/ROOT for machine text, the context's locale for display"]
   [#"SimpleDateFormat|DecimalFormat\.|NumberFormat/getInstance\b|Collator/getInstance" "locale-sensitive formatter — use java.time with an explicit locale, or csv-cleaver.i18n for display"]
   [#"\(Locale/getDefault\)" "only csv-cleaver.i18n/detect-tag may ask what the machine's locale is"]])

(defn line-hits
  "The [pattern why] entries a single source line violates."
  [line]
  (when-not (str/includes? line "locale-ok")
    (for [[re why] forbidden :when (re-find re line)] [re why])))

(def exempt
  "See the namespace docstring: the two namespaces allowed to name a locale,
   and this lint itself."
  ["csv_cleaver/text.clj" "csv_cleaver/i18n.clj" "locale_lint.clj"])

(defn sources
  "Every file the lint reads: the application and the dev tooling, minus the
   exemptions."
  []
  (->> (concat (file-seq (io/file "src")) (file-seq (io/file "dev")))
       (filter (fn [^java.io.File f]
                 (and (.isFile f) (re-matches #".*\.cljc?" (.getName f)))))
       (map str)
       (remove (fn [path] (some #(str/ends-with? path %) exempt)))
       sort))

(defn hits
  "Every violation in src/ and dev/, as printable lines."
  []
  (vec (for [f (sources)
             [i line] (map-indexed vector (str/split-lines (slurp f)))
             [_ why] (line-hits line)]
         (text/fmt "%s:%d: %s — %s" f (inc i) (str/trim line) why))))

(defn run [_]
  (let [hs (hits)]
    (if (seq hs)
      (do (doseq [h hs] (println h))
          (println (text/fmt "locale-lint: %d forbidden call(s)" (count hs)))
          (System/exit 1))
      (println (text/fmt "locale-lint ok: %d files, no default-locale text operations outside csv-cleaver.text and csv-cleaver.i18n"
                         (count (sources)))))))
