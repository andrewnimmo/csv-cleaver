(ns csv-cleaver.format-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.format :as fmt]
   [csv-cleaver.i18n :as i18n])
  (:import
   (java.util Locale)))

(def en (i18n/context "en"))
(def de (i18n/context "de"))
(def fr (i18n/context "fr"))

(deftest numbers-follow-the-window-not-the-machine
  (testing "the bug this catches: clojure.core/format follows the JVM's default
            locale, so on this machine — which defaults to Spanish — an English
            window rendered 1204338 as 1.204.338. The language of the window is
            now what decides, whatever the machine is set to."
    (let [original (Locale/getDefault)]
      (try
        (Locale/setDefault (Locale/forLanguageTag "es-ES"))
        (is (= "1,204,338 data rows" (i18n/trn en :file/data-rows 1204338
                                               (i18n/number en 1204338))))
        (is (= "6.4 seconds" (fmt/duration en 6432)))
        (is (= "6,4 Sekunden" (fmt/duration de 6432)))
        (finally (Locale/setDefault original))))))

(deftest formats-file-sizes
  (is (= "0 bytes" (fmt/file-size en 0)))
  (is (= "512 bytes" (fmt/file-size en 512)))
  (is (= "1.0 KB" (fmt/file-size en 1024)))
  (is (= "218 MB" (fmt/file-size en 228515840)))
  (testing "three digits drop the decimal, so widths stay comparable"
    (is (= "500 KB" (fmt/file-size en (* 500 1024)))))
  (testing "each language writes both the number and the unit its own way"
    (is (= "1,5 KB" (fmt/file-size de 1536)))
    (is (= "1,5 Ko" (fmt/file-size fr 1536)))))

(deftest formats-durations
  (is (= "under a second" (fmt/duration en 0)))
  (is (= "under a second" (fmt/duration en 999)))
  (is (= "1.0 seconds" (fmt/duration en 1000)))
  (is (= "6.4 seconds" (fmt/duration en 6432)))
  (is (= "1 minute 34 seconds" (fmt/duration en 94000)))
  (is (= "2 minutes" (fmt/duration en 120000)))
  (is (= "1 minute 1 second" (fmt/duration en 61000)))
  (is (= "1 Minute 34 Sekunden" (fmt/duration de 94000))))

(deftest parses-row-counts-in-the-users-own-notation
  (is (= 65000 (fmt/parse-count en "65,000")))
  (is (= 65000 (fmt/parse-count en " 65000 ")))
  (is (= 65000 (fmt/parse-count en "65_000")))
  (is (= 1 (fmt/parse-count en "1")))
  (testing "a German typing 65.000 means sixty-five thousand, not sixty-five"
    (is (= 65000 (fmt/parse-count de "65.000"))))
  (testing "and a French user's space-grouped number is read the same way"
    (is (= 65000 (fmt/parse-count fr "65 000"))))
  (testing "anything that is not a positive whole number is refused"
    (is (nil? (fmt/parse-count en "0")))
    (is (nil? (fmt/parse-count en "-5")))
    (is (nil? (fmt/parse-count en "3.5")))
    (is (nil? (fmt/parse-count en "many")))
    (is (nil? (fmt/parse-count en "12abc")))
    (is (nil? (fmt/parse-count en "")))
    (is (nil? (fmt/parse-count en nil)))))

(deftest parses-file-sizes
  (testing "a bare number means megabytes, matching the label beside the box"
    (is (= (* 25 1024 1024) (fmt/parse-size en "25"))))
  (is (= (* 25 1024 1024) (fmt/parse-size en "25 MB")))
  (is (= (* 25 1024 1024) (fmt/parse-size en "25mb")))
  (is (= (long (* 1.5 1024 1024 1024)) (fmt/parse-size en "1.5 GB")))
  (is (= 2048 (fmt/parse-size en "2 KB")))
  (is (= 900 (fmt/parse-size en "900 B")))
  (testing "with the decimal comma a German user would type"
    (is (= (long (* 1.5 1024 1024 1024)) (fmt/parse-size de "1,5 GB"))))
  (is (nil? (fmt/parse-size en "0 MB")))
  (is (nil? (fmt/parse-size en "big")))
  (is (nil? (fmt/parse-size en ""))))

(deftest renders-a-problem-whatever-shape-it-arrives-in
  (is (nil? (fmt/message en nil)))
  (is (= "raw text" (fmt/message en "raw text")))
  (is (= "This file has no data rows to split." (fmt/message en :problem/no-data)))
  (is (= "That would create 6,000 files in one folder."
         (fmt/message en {:key :plan/many-files :args [6000]}))
      "a number inside a problem is still grouped for this language")
  (is (= "unexpected" (fmt/message en {:text "unexpected"}))))

(deftest describes-the-plan-in-a-sentence
  (testing "an uneven split names both sizes, because the odd last file
            surprises people who were not told about it"
    (is (= "This makes 19 files — 18 with 65,000 rows and one with 34,338."
           (fmt/plan-sentence en {:mode :rows :file-count 19 :data-rows 1204338
                                  :rows-per-file 65000 :last-file-rows 34338}))))
  (testing "an even split says so more simply"
    (is (= "This makes 4 files of 25 rows each."
           (fmt/plan-sentence en {:mode :rows :file-count 4 :data-rows 100
                                  :rows-per-file 25 :last-file-rows 25}))))
  (testing "one file means nothing would happen, which is worth saying plainly"
    (is (= "Everything fits in one file of 42 rows, so nothing would be split."
           (fmt/plan-sentence en {:mode :rows :file-count 1 :data-rows 42
                                  :rows-per-file 100 :last-file-rows 42}))))
  (testing "splitting by size can only estimate"
    (is (= "This makes about 12 files, roughly 8,000 rows in each."
           (fmt/plan-sentence en {:mode :bytes :file-count 12 :data-rows 96000
                                  :rows-per-file 8000 :last-file-rows 8000}))))
  (testing "a problem replaces the sentence entirely"
    (is (= "This file has no data rows to split."
           (fmt/plan-sentence en {:problem :problem/no-data}))))
  (testing "and it all works in another language"
    (is (= "Das ergibt 4 Dateien mit je 25 Zeilen."
           (fmt/plan-sentence de {:mode :rows :file-count 4 :data-rows 100
                                  :rows-per-file 25 :last-file-rows 25})))))

(deftest summarises-damage-and-always-reassures
  (is (nil? (fmt/damage-summary en {:damage {:ragged 0 :stray-quote 0 :unterminated-quote 0}
                                    :records 10})))
  (let [one (fmt/damage-summary en {:damage {:ragged 1} :records 500})]
    (is (= "1 row looks damaged" (:headline one)))
    (is (re-find #"nothing is lost" (:detail one))))
  (let [many (fmt/damage-summary en {:damage {:ragged 2 :stray-quote 3} :records 500})]
    (is (= "5 rows look damaged" (:headline many)))
    (is (re-find #"different number of columns" (:detail many)))
    (is (re-find #"quote mark" (:detail many))))
  (let [cut (fmt/damage-summary en {:damage {:unterminated-quote 1} :records 9})]
    (is (re-find #"ends in the middle of a quoted value" (:detail cut))))
  (testing "French treats one and zero alike, which the plural rules must honour"
    (is (= "1 ligne semble endommagée"
           (:headline (fmt/damage-summary fr {:damage {:ragged 1} :records 5}))))))

(deftest describes-the-outcome
  (is (= "19 files created in 6.4 seconds"
         (fmt/completion-sentence en {:files (repeat 19 :f) :elapsed-ms 6432})))
  (is (= "1 file created in under a second"
         (fmt/completion-sentence en {:files [:f] :elapsed-ms 200})))
  (testing "cancelling says what survived, so nobody has to guess"
    (is (re-find #"3 files were finished"
                 (fmt/completion-sentence en {:files [:a :b :c] :cancelled? true})))
    (is (re-find #"No files were created"
                 (fmt/completion-sentence en {:files [] :cancelled? true})))))

(deftest describes-progress
  (is (= "File 3 · 12,000 of 90,000 rows"
         (fmt/progress-sentence en {:rows-done 12000 :files-done 2} 90000)))
  (testing "before the first file is open it still reads as file 1"
    (is (= "File 1 · 0 of 10 rows"
           (fmt/progress-sentence en {:rows-done 0 :files-done 0} 10)))))

(deftest a-typed-number-can-be-rewritten-for-another-language
  (testing "R80. The boxes hold text, and that text is read back in whatever
            language the window is in. Leaving it alone across a language change is not a
            cosmetic fault: English 65,000 read as German is sixty-five."
    (is (= "65.000" (fmt/restate en de "65,000")))
    ;; French groups with a narrow no-break space, U+202F, which is why this
    ;; expectation is written as an escape rather than as something that looks
    ;; like a space and is not one.
    (is (= "65\u202f000" (fmt/restate en fr "65,000")))
    (is (= "65,000" (fmt/restate de en "65.000")))
    (is (= "65,000" (fmt/restate en en "65,000")) "no change is still correct")
    (testing "a unit survives, and only the number is rewritten"
      (is (= "1,5 GB" (fmt/restate en de "1.5 GB")))
      (is (= "25 MB" (fmt/restate en de "25 MB")))
      (is (= "1.5 GB" (fmt/restate de en "1,5 GB"))))
    (testing "anything unreadable is left exactly as it is: half-typed input
              belongs to the user, and guessing at it would be worse"
      (doseq [text ["" "  " "65," "abc" "MB" "-"]]
        (is (= text (fmt/restate en de text)) (pr-str text))))))

(deftest rewriting-a-number-round-trips
  (testing "changing language and changing back must not drift.

            Each case starts from text this language would itself have written,
            because that is the only text the property can hold for: \"1.5 GB\"
            is not Spanish, where a full stop groups thousands, and reading it
            as Spanish correctly yields fifteen."
    (let [tags ["en" "de" "fr" "es" "zh" "ja"]]
      (doseq [a tags
              b tags
              [render suffix] [[#(i18n/number % 65000) ""]
                               [#(i18n/number % 1048576) ""]
                               [#(i18n/decimal % 1.5 1) " GB"]
                               [#(i18n/number % 25) " MB"]]]
        (let [ca   (i18n/context a)
              cb   (i18n/context b)
              text (str (render ca) suffix)]
          (is (= text (fmt/restate cb ca (fmt/restate ca cb text)))
              (str (pr-str text) " through " a "→" b "→" a)))))))

(deftest rewriting-a-number-preserves-what-it-means
  (testing "the point of the exercise: the value the window reads back is the
            same number before and after a language change"
    (let [tags ["en" "de" "fr" "es" "zh" "ja"]]
      (doseq [a tags b tags]
        (let [ca   (i18n/context a)
              cb   (i18n/context b)
              text (i18n/number ca 65000)]
          (is (= 65000 (fmt/parse-count ca text)) (str a " reads its own text"))
          (is (= 65000 (fmt/parse-count cb (fmt/restate ca cb text)))
              (str a "→" b " keeps the number")))))))
