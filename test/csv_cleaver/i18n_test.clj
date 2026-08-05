(ns csv-cleaver.i18n-test
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.i18n :as i18n])
  (:import
   (java.util Locale)))

(deftest every-shipped-language-loads
  (doseq [tag i18n/supported]
    (testing tag
      (let [bundle (i18n/read-bundle tag)]
        (is (map? bundle))
        (is (string? (get-in bundle [:meta :name])))
        (is (seq (:strings bundle)))))))

(deftest no-language-is-missing-a-phrase
  (testing "a translation that falls behind English shows English for the keys
            it lacks, which is survivable but should never be a surprise"
    (let [english (set (keys (:strings (i18n/read-bundle "en"))))]
      (doseq [tag (remove #{"en"} i18n/supported)]
        (let [theirs  (set (keys (:strings (i18n/read-bundle tag))))
              missing (set/difference english theirs)]
          (is (empty? missing) (str tag " is missing: " (sort missing))))))))

(deftest no-language-has-invented-a-phrase
  (testing "a key that exists nowhere in English is a typo, and would never be
            shown to anyone"
    (let [english (set (keys (:strings (i18n/read-bundle "en"))))]
      (doseq [tag (remove #{"en"} i18n/supported)]
        (let [extra (set/difference (set (keys (:strings (i18n/read-bundle tag)))) english)]
          (is (empty? extra) (str tag " has unknown keys: " (sort extra))))))))

(deftest plural-forms-match-english
  (testing "a key English gives singular and plural forms must have them
            everywhere those forms differ"
    (let [english (:strings (i18n/read-bundle "en"))
          plural  (set (keep (fn [[k v]] (when (map? v) k)) english))]
      (doseq [tag ["es" "fr" "de"]]
        (let [theirs (:strings (i18n/read-bundle tag))]
          (doseq [k plural]
            (is (map? (get theirs k)) (str tag " " k " needs singular and plural"))
            (is (contains? (get theirs k) :one) (str tag " " k " needs :one"))
            (is (contains? (get theirs k) :other) (str tag " " k " needs :other"))))))))

(deftest placeholders-survive-translation
  (testing "a translator who drops a {0} leaves a sentence with a hole in it"
    (let [english (:strings (i18n/read-bundle "en"))
          holes   (fn [text] (set (re-seq #"\{\d+\}" (str text))))
          forms   (fn [v] (if (map? v) (vals v) [v]))]
      (doseq [tag (remove #{"en"} i18n/supported)]
        (let [theirs (:strings (i18n/read-bundle tag))]
          (doseq [[k v] english
                  :let [expected (apply set/union (map holes (forms v)))
                        actual   (apply set/union (map holes (forms (get theirs k))))]
                  :when (get theirs k)]
            (is (= expected actual) (str tag " " k))))))))

(deftest english-is-marked-reviewed-and-the-rest-are-not
  (is (get-in (i18n/read-bundle "en") [:meta :reviewed?]))
  (testing "machine translations must say so until a native speaker has been over them"
    (doseq [tag (remove #{"en"} i18n/supported)]
      (is (false? (get-in (i18n/read-bundle tag) [:meta :reviewed?])) tag))))

;; ── Choosing a language ─────────────────────────────────────────────────────

(deftest detects-the-system-language
  (is (= "de" (i18n/detect-tag (Locale/forLanguageTag "de-AT"))))
  (is (= "ja" (i18n/detect-tag (Locale/forLanguageTag "ja-JP"))))
  (testing "a language we do not translate falls back to English"
    (is (= "en" (i18n/detect-tag (Locale/forLanguageTag "is-IS")))))
  (is (string? (i18n/detect-tag))))

(deftest accepts-what-a-person-might-type
  (is (= "es" (i18n/normalise-tag "es")))
  (is (= "es" (i18n/normalise-tag "ES")))
  (is (= "es" (i18n/normalise-tag "es-MX")))
  (is (= "zh" (i18n/normalise-tag "zh_CN")))
  (is (nil? (i18n/normalise-tag "klingon")))
  (is (nil? (i18n/normalise-tag nil))))

(deftest a-context-carries-language-and-locale
  (let [ctx (i18n/context "fr")]
    (is (= "fr" (:tag ctx)))
    (is (= "fr" (.getLanguage ^Locale (:locale ctx))))
    (is (false? (:reviewed? ctx)))
    (is (seq (:strings ctx))))
  (testing "an unknown language quietly becomes English rather than failing"
    (is (= "en" (:tag (i18n/context "klingon")))))
  (is (string? (:tag (i18n/context)))))

(deftest english-shows-through-where-a-translation-is-absent
  (testing "a half-finished translation shows English for what it lacks, so a
            language can be added a phrase at a time"
    (let [real i18n/read-bundle]
      (with-redefs [i18n/read-bundle
                    (fn [tag]
                      (if (= tag "de")
                        {:meta    {:name "Deutsch" :reviewed? false}
                         :strings {:action/close "Schließen"}}
                        (real tag)))]
        (let [ctx (i18n/context "de")]
          (is (= "Schließen" (i18n/tr ctx :action/close)) "what was translated")
          (is (= "Cancel" (i18n/tr ctx :action/cancel))
              "and English for what was not, rather than a blank button"))))))

;; ── Phrases ─────────────────────────────────────────────────────────────────

(deftest fills-in-the-blanks
  (is (= "a-b" (i18n/interpolate "{0}-{1}" ["a" "b"])))
  (is (= "1 of 1" (i18n/interpolate "{0} of {0}" [1])))
  (is (= "nothing" (i18n/interpolate "nothing" []))))

(deftest apostrophes-are-left-alone
  (testing "java.text.MessageFormat would eat these; French is full of them"
    (let [ctx (i18n/context "fr")]
      (is (not (re-find #"\{" (i18n/tr ctx :empty/subhead)))))))

(deftest a-missing-key-is-loud-rather-than-blank
  (is (= "⟦nope/at-all⟧" (i18n/tr (i18n/context "en") :nope/at-all))))

(deftest plural-rules-differ-by-language
  (testing "English and German use the singular for exactly one"
    (is (= :one (i18n/plural-category "en" 1)))
    (is (= :other (i18n/plural-category "en" 0)))
    (is (= :other (i18n/plural-category "de" 2))))
  (testing "French and Portuguese count zero as singular — CLDR puts pt's
            0 and 1 both under :one"
    (is (= :one (i18n/plural-category "fr" 0)))
    (is (= :one (i18n/plural-category "fr" 1)))
    (is (= :other (i18n/plural-category "fr" 2)))
    (is (= :one (i18n/plural-category "pt" 0)))
    (is (= :one (i18n/plural-category "pt" 1)))
    (is (= :other (i18n/plural-category "pt" 2))))
  (testing "Italian follows the exactly-one rule"
    (is (= :one (i18n/plural-category "it" 1)))
    (is (= :other (i18n/plural-category "it" 0))))
  (testing "Chinese and Japanese have no plural at all"
    (is (= :other (i18n/plural-category "zh" 1)))
    (is (= :other (i18n/plural-category "ja" 1)))))

(deftest chooses-the-right-wording-for-a-count
  (let [en (i18n/context "en")]
    (is (= "1 data row" (i18n/trn en :file/data-rows 1 "1")))
    (is (= "5 data rows" (i18n/trn en :file/data-rows 5 "5")))))

(deftest the-count-is-not-silently-interpolated
  (testing "the caller passes the formatted number, so 1203 never reaches the
            screen where 1,203 was intended"
    (let [en (i18n/context "en")]
      (is (= "1,203 data rows" (i18n/trn en :file/data-rows 1203 "1,203"))))))

;; ── Numbers ─────────────────────────────────────────────────────────────────

(def cldr-groupings
  "How each language this application offers writes 1204338, and 6.4.

   Written out as literals on purpose. These are what CLDR says, not what this
   code produces — an expectation generated by calling `i18n/number` would agree
   with `i18n/number` however wrong it was, and several tests elsewhere are built
   on top of this one. French uses U+202F, a narrow no-break space, which is
   written as an escape here so that nobody has to wonder whether it is an
   ordinary space.

   These are also the only assertions in the suite that would notice a runtime
   with no locale data, which is how every installer shipped for weeks: on such
   a runtime every line below returns the English form. They do not notice it in
   practice, because tests run on a full JDK — see docs/DEVELOPING.md and the
   checks inside the packaging scripts."
  {"en" ["1,204,338" "6.4"]
   "de" ["1.204.338" "6,4"]
   "fr" ["1\u202f204\u202f338" "6,4"]
   "es" ["1.204.338" "6,4"]
   "it" ["1.204.338" "6,4"]
   "pt" ["1.204.338" "6,4"]
   "zh" ["1,204,338" "6.4"]
   "ja" ["1,204,338" "6.4"]})

(deftest numbers-follow-the-display-language
  (doseq [[tag [grouped decimal]] cldr-groupings]
    (testing tag
      (is (= grouped (i18n/number (i18n/context tag) 1204338)))
      (is (= decimal (i18n/decimal (i18n/context tag) 6.4 1))))))

(deftest the-languages-that-differ-actually-differ
  (testing "guards the table above against being quietly filled in with the
            English form for everything, which is exactly what a runtime with no
            locale data would produce"
    (is (= 3 (count (distinct (map first (vals cldr-groupings)))))
        "English/Chinese/Japanese group with commas, German/Spanish with stops,
         French with a narrow no-break space — three distinct forms")
    (is (not= (first (cldr-groupings "en")) (first (cldr-groupings "de"))))
    (is (not= (first (cldr-groupings "en")) (first (cldr-groupings "fr"))))))

(deftest file-names-keep-plain-digits
  (testing "so that a file manager can still sort them, whatever the language"
    (is (= "1204338" (i18n/plain-number 1204338)))))

(deftest languages-are-listed-for-the-picker
  (let [ls (i18n/languages)]
    (is (= (count i18n/supported) (count ls)))
    (is (= "English" (:name (first ls))))
    (is (every? :tag ls))
    (is (= "fr" (i18n/tag-for-name "Français")))
    (is (nil? (i18n/tag-for-name "Klingon")))))

;; ── The Easter eggs ─────────────────────────────────────────────────────────

(deftest hidden-languages-stay-hidden-until-revealed
  (i18n/conceal-hidden!)
  (try
    (is (not-any? #{"tlh" "vuh"} (i18n/available-tags))
        "not on offer by default")
    (is (nil? (i18n/normalise-tag "tlh"))
        "--locale tlh alone does nothing; the flag is the key")
    (i18n/reveal-hidden!)
    (is (every? (set (i18n/available-tags)) ["tlh" "vuh"]))
    (is (= "tlh" (i18n/normalise-tag "tlh")))
    (is (some #(= "tlhIngan Hol" (:name %)) (i18n/languages))
        "the picker offers Klingon by its own name")
    (is (some #(= "Vuhlkansu" (:name %)) (i18n/languages)))
    (finally (i18n/conceal-hidden!))))

(deftest a-hidden-bundle-may-be-partial-but-not-broken
  (testing "English shows through for anything untranslated — the same
            arrangement as every other missing phrase — but what IS there must
            interpolate and carry no invented keys"
    (let [en-keys (set (keys (:strings (#'i18n/read-bundle "en"))))]
      (doseq [tag i18n/hidden-tags]
        (let [bundle (#'i18n/read-bundle tag)]
          (is (seq (:strings bundle)) (str tag " has something to say"))
          (is (empty? (remove en-keys (keys (:strings bundle))))
              (str tag " must not invent keys English does not have"))
          (testing (str tag " falls back to English for the rest")
            (i18n/reveal-hidden!)
            (try
              (let [ctx (i18n/context tag)]
                (is (= "Qapla'! 3 teywI' chenmoHlu' — 2s."
                       (i18n/tr (i18n/context "tlh") :done/created 3 "2s"))
                    "the famous word, with the count interpolated")
                (is (string? (i18n/tr ctx :help/a-header))
                    "an untranslated phrase arrives in English, not as a marker")
                (is (not (str/starts-with? (i18n/tr ctx :help/a-header) "⟦"))))
              (finally (i18n/conceal-hidden!)))))))))
