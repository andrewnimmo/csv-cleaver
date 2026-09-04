(ns csv-cleaver.text
  "Text operations for machines, pinned to Locale/ROOT.

   This application handles text for two different readers, and they must never
   share a code path. Text for the *user* — sentences, numbers, unit words —
   follows the language of the window, and belongs to csv-cleaver.i18n, which
   takes the chosen locale as an explicit argument. Text for a *machine* —
   language tags, CLI keywords, file-name suffixes, format tokens — must come
   out the same on every computer in the world, and that is what lives here.

   Neither reader is served by the JVM's default locale, but that is what
   `clojure.string/lower-case`, `upper-case`, `capitalize` and
   `clojure.core/format` quietly consult. On a machine set to Turkish,
   (str/lower-case \"IT\") is \"ıt\" — the dotless ı — so the Italian language
   tag stops being recognised; (format \"%.1f\" 6.4) is \"6,4\"; and on the
   Spanish machine this project was developed on, an English window once
   rendered 1204338 as 1.204.338 and nearly shipped (docs/DECISIONS.md, 9).
   Every machine-facing fold and format goes through this namespace instead;
   `bb locale-lint` forbids the raw calls everywhere outside it and
   csv-cleaver.i18n, and `bb test-tr` runs the whole suite under tr_TR to
   catch whatever a pattern cannot."
  (:import
   (java.util Locale)))

(defn lower
  "Case-fold independent of the machine's locale."
  [s]
  (.toLowerCase ^String (str s) Locale/ROOT))

(defn upper
  "Upper-case independent of the machine's locale."
  [s]
  (.toUpperCase ^String (str s) Locale/ROOT))

(defn capitalize
  "clojure.string/capitalize with both folds pinned to Locale/ROOT."
  [s]
  (let [s (str s)]
    (if (< (count s) 2)
      (upper s)
      (str (upper (subs s 0 1)) (lower (subs s 1))))))

(defn fmt
  "clojure.core/format under Locale/ROOT — never the default locale."
  [pattern & args]
  (String/format Locale/ROOT pattern (to-array args)))
