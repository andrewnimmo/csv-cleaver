(ns csv-cleaver.text-test
  "Machine-facing text is the same on every machine (R85).

   The adversary throughout is the Turkish locale, because it is the one that
   makes case-folding bugs undeniable: I lower-cases to the dotless ı and i
   upper-cases to the dotted İ. Each test forces tr_TR around the call, so this
   suite fails on an unpinned fold even when the machine running it is set to
   English; `bb test-tr` then runs every other test under tr_TR as the wide
   net, and `bb locale-lint` bans the raw calls at the source."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.cli :as tools-cli]
   [csv-cleaver.cli :as cli]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.text :as text]
   [locale-lint :as lint])
  (:import
   (java.util Locale)))

(defn- under
  "Run `f` with the JVM default locale forced to `tag`, and put it back."
  [tag f]
  (let [original (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag tag))
      (f)
      (finally (Locale/setDefault original)))))

(deftest machine-text-ignores-the-machines-locale
  (under "tr-TR"
         (fn []
           (is (= "items" (text/lower "Items"))
               "unpinned, tr_TR folds this to ıtems")
           (is (= "IT" (text/upper "it"))
               "unpinned, tr_TR folds this to İT")
           (is (= "Friday" (text/capitalize "FRIDAY"))
               "unpinned, tr_TR folds this to Frıday")
           (is (= "1204.338" (text/fmt "%.3f" 1204.338))
               "unpinned, tr_TR prints a decimal comma")))
  (under "es-ES"
         (fn []
           (is (= "1,204,338" (text/fmt "%,d" 1204338))
               "the founding bug: unpinned, a Spanish machine groups this as
                1.204.338 — which an English window nearly shipped"))))

(deftest language-tags-survive-a-turkish-machine
  ;; These two went through str/lower-case until this test existed. Both were
  ;; wrong in the same quiet way: on a machine set to Turkish, the fold turned
  ;; I into ı, the tag stopped matching anything, and the user silently got
  ;; English instead of what they asked for.
  (under "tr-TR"
         (fn []
           (testing "--locale IT still means Italian"
             (is (= "it" (i18n/normalise-tag "IT")))
             (is (= "it" (i18n/normalise-tag "it_IT"))))
           (testing "--theme LIGHT is still a theme, not the keyword :lıght"
             (let [{:keys [options errors]}
                   (tools-cli/parse-opts ["--theme" "LIGHT"] cli/options)]
               (is (empty? errors))
               (is (= :light (:theme options))))))))

(deftest the-locale-lint-is-clean-and-sees-each-form
  (testing "the lint is actually looking at the project — a lint over an empty
            file list reports success exactly like a clean one, which is the
            same trap the audit task once fell into"
    (is (<= 20 (count (lint/sources)))))
  (testing "src/ and dev/ are clean, so reintroducing a raw call fails the
            suite itself, not only `bb locale-lint`"
    (is (= [] (lint/hits))))
  (testing "each forbidden form is seen"
    (is (seq (lint/line-hits "(str/lower-case x)")))
    (is (seq (lint/line-hits "(str/capitalize x)")))
    (is (seq (lint/line-hits "(format \"%.2f\" x)")))
    (is (seq (lint/line-hits "(.toLowerCase s)")))
    (is (seq (lint/line-hits "(String/format \"%d\" (to-array [n]))")))
    (is (seq (lint/line-hits "(DateTimeFormatter/ofPattern \"yyyy-MM-dd\")")))
    (is (seq (lint/line-hits "(SimpleDateFormat. \"yyyy-MM-dd HHmm\")")))
    (is (seq (lint/line-hits "(Locale/getDefault)"))))
  (testing "pinned forms and documented waivers pass"
    (is (empty? (lint/line-hits "(.toLowerCase s java.util.Locale/ROOT)")))
    (is (empty? (lint/line-hits "(String/format Locale/ROOT \"%d\" args)")))
    (is (empty? (lint/line-hits "(DateTimeFormatter/ofPattern \"HHmm\" Locale/ROOT)")))
    (is (empty? (lint/line-hits "(str/lower-case x) ;; locale-ok: why this one is safe")))))
