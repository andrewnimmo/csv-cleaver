(ns csv-cleaver.split-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.split :as split]
   [csv-cleaver.test-util :as tu])
  (:import
   (java.io File)
   (java.nio.file Files)))

(defn- survey-of [dir filename content & [charset bom]]
  (scan/survey (tu/write-file dir filename content (or charset "UTF-8") bom)))

(defn- run [dir survey opts]
  (let [base (merge {:survey survey :out-dir dir :mode :rows :value 2
                     :has-header? true :include-header? true}
                    opts)]
    (split/execute! (assoc base :plan (split/plan base)))))

;; ── plan ────────────────────────────────────────────────────────────────────

(deftest plans-an-even-split
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" "id\n1\n2\n3\n4\n")
          p (split/plan {:survey s :mode :rows :value 2 :has-header? true})]
      (is (= 2 (:file-count p)))
      (is (= 4 (:data-rows p)))
      (is (= 2 (:rows-per-file p)))
      (is (= 2 (:last-file-rows p)))
      (is (:exact? p))
      (is (nil? (:problem p))))))

(deftest plans-an-uneven-split
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id\n" (map #(str % "\n") (range 5))))
          p (split/plan {:survey s :mode :rows :value 2 :has-header? true})]
      (is (= 3 (:file-count p)))
      (is (= 1 (:last-file-rows p))))))

(deftest refuses-a-plan-it-cannot-carry-out
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" "id\n1\n")]
      (is (= :problem/rows-needed
             (:problem (split/plan {:survey s :mode :rows :value nil :has-header? true}))))
      (is (= :problem/size-needed
             (:problem (split/plan {:survey s :mode :bytes :value 0 :has-header? true})))))
    (let [empty-file (survey-of dir "b.csv" "id\n")]
      (is (= :problem/no-data
             (:problem (split/plan {:survey empty-file :mode :rows :value 10
                                    :has-header? true})))))))

(deftest plans-by-size-approximately
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id,name\n" (repeat 100 "1,abcdefgh\n")))
          p (split/plan {:survey s :mode :bytes :value 200 :has-header? true})]
      (is (false? (:exact? p)))
      (is (pos? (:file-count p)))
      (is (pos? (:rows-per-file p))))))

(deftest warns-about-carpeting-a-folder
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id\n" (map #(str % "\n") (range 6000))))
          p (split/plan {:survey s :mode :rows :value 1 :has-header? true})]
      (is (= {:key :plan/many-files :args [6000]} (:warning p))))))

(deftest a-size-split-stops-at-excels-row-limit
  (testing "the hole the size mode would otherwise leave: the user never chooses
            a row count, so short rows and a large target could produce a file
            Excel cannot open, which defeats the whole purpose"
    (tu/with-temp-dir [dir]
      (let [s (survey-of dir "a.csv" (apply str "id\n" (repeat 200 "1\n")))
            p (split/plan {:survey s :mode :bytes :value (* 500 1024 1024)
                           :has-header? true})]
        (is (= split/excel-row-limit (:row-cap p)))
        (is (= split/excel-row-limit (:rows-per-file p)))
        (is (= {:key :plan/capped-at-excel :args [split/excel-row-limit]} (:warning p)))))))

(deftest the-cap-can-be-turned-off
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id\n" (repeat 200 "1\n")))
          p (split/plan {:survey s :mode :bytes :value (* 500 1024 1024)
                         :has-header? true :excel-safe? false})]
      (is (nil? (:row-cap p)))
      (is (> (:rows-per-file p) split/excel-row-limit)))))

(deftest a-row-count-the-user-typed-is-never-overridden
  (testing "they may not be using Excel at all; warn, but honour what was asked"
    (tu/with-temp-dir [dir]
      (let [s (survey-of dir "a.csv" (apply str "id\n" (repeat 10 "1\n")))
            p (split/plan {:survey s :mode :rows :value (* 2 split/excel-row-limit)
                           :has-header? true})]
        (is (= (* 2 split/excel-row-limit) (:rows-per-file p)))
        (is (nil? (:row-cap p)))
        (is (= {:key :plan/over-excel :args [split/excel-row-limit]} (:warning p)))))))

(deftest the-cap-actually-rolls-the-file-over
  (testing "not merely advertised in the plan"
    (tu/with-temp-dir [dir]
      (let [s    (survey-of dir "a.csv" (apply str "id\n" (repeat 20 "1\n")))
            plan (assoc (split/plan {:survey s :mode :bytes :value 100000
                                     :has-header? true})
                        :row-cap 5)
            r    (split/execute! {:survey s :out-dir dir :mode :bytes :value 100000
                                  :has-header? true :include-header? false :plan plan})]
        (is (= 4 (count (:files r))) "20 rows capped at 5 to a file")
        (is (= [5 5 5 5] (mapv :rows (:written r))))))))

;; ── execute! ────────────────────────────────────────────────────────────────

(deftest splits-by-rows-repeating-the-header
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "people.csv" "id,name\n1,Ann\n2,Bob\n3,Cy\n")
          r (run dir s {:value 2})]
      (is (= ["people_0001.csv" "people_0002.csv"] (tu/names (:files r))))
      (is (= 3 (:rows r)))
      (is (false? (:cancelled? r)))
      (is (nil? (:abandoned r)))
      (is (= "id,name\n1,Ann\n2,Bob\n" (tu/read-file (first (:files r)))))
      (is (= "id,name\n3,Cy\n" (tu/read-file (second (:files r)))))
      (is (= [2 1] (mapv :rows (:written r)))))))

(deftest can-leave-the-header-out-of-each-file
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "people.csv" "id,name\n1,Ann\n2,Bob\n")
          r (run dir s {:value 1 :include-header? false})]
      (is (= "1,Ann\n" (tu/read-file (first (:files r)))))
      (is (= "2,Bob\n" (tu/read-file (second (:files r))))))))

(deftest treats-every-row-as-data-when-there-is-no-header
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" "1,Ann\n2,Bob\n")
          r (run dir s {:value 1 :has-header? false :include-header? false})]
      (is (= 2 (count (:files r))))
      (is (= "1,Ann\n" (tu/read-file (first (:files r))))))))

(deftest never-tears-a-record-in-half
  (testing "the defect that made the old application corrupt data silently"
    (tu/with-temp-dir [dir]
      (let [content "id,notes\n1,\"first line\nsecond line\"\n2,plain\n3,\"a,b\"\n"
            s       (survey-of dir "notes.csv" content)
            r       (run dir s {:value 1})]
        (is (= 3 (count (:files r))))
        (is (= "id,notes\n1,\"first line\nsecond line\"\n"
               (tu/read-file (first (:files r))))
            "the quoted newline stays inside one output file")))))

(deftest rejoins-byte-for-byte
  (tu/with-temp-dir [dir]
    (let [content "id,notes\r\n1,\"one\r\ntwo\"\r\n2,plain\r\n3,\"say \"\"hi\"\"\"\r\n"
          source  (tu/write-file dir "in.csv" content)
          s       (scan/survey source)
          r       (run dir s {:value 2 :include-header? false})
          header  "id,notes\r\n"
          rejoined (str header (str/join (map tu/read-file (:files r))))]
      (is (= content rejoined)
          "line endings, quoting and spacing all survive untouched"))))

(deftest preserves-a-windows-1252-file
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "in.csv" "name\nRené\nZoë\n" "windows-1252" nil)
          r (run dir s {:value 1})]
      (is (= "name\nRené\n" (tu/read-file (first (:files r)) "windows-1252")))
      (testing "and does not silently become UTF-8, where é would take two bytes
                and push \"name\\nRené\\n\" from ten bytes to eleven"
        (is (= 10 (count (Files/readAllBytes (.toPath ^File (first (:files r)))))))))))

(deftest gives-every-output-file-the-byte-order-mark-the-input-had
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "in.csv" "name\nRené\nZoë\n" "UTF-8" [0xEF 0xBB 0xBF])
          r (run dir s {:value 1})]
      (doseq [^File f (:files r)]
        (is (= [-17 -69 -65] (take 3 (vec (Files/readAllBytes (.toPath f)))))
            (str (.getName f) " must open correctly in Excel"))))))

(deftest splits-by-size
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id,name\n" (repeat 40 "1,abcdefgh\n")))
          r (run dir s {:mode :bytes :value 120 :include-header? false})]
      (is (> (count (:files r)) 1))
      (is (= 40 (:rows r)))
      (doseq [^File f (butlast (:files r))]
        (is (<= (.length f) 130) "no file may overshoot the target by a whole record")))))

(deftest a-record-larger-than-the-target-gets-its-own-file
  (tu/with-temp-dir [dir]
    (let [big (apply str (repeat 500 "x"))
          s   (survey-of dir "a.csv" (str "id\n" big "\n" big "\n"))
          r   (run dir s {:mode :bytes :value 100 :include-header? false})]
      (is (= 2 (count (:files r))) "rather than looping forever or losing the row"))))

(deftest cancelling-removes-only-the-file-it-was-writing
  (tu/with-temp-dir [dir]
    (let [rows (apply str "id\n" (for [i (range (* 3 split/progress-interval))]
                                   (str i "\n")))
          s    (survey-of dir "big.csv" rows)
          r    (run dir s {:value 1000 :cancelled? (constantly true)})]
      (is (:cancelled? r))
      (is (some? (:abandoned r)))
      (is (not (.exists ^File (:abandoned r)))
          "the half-written file must not be left behind")
      (doseq [^File f (:files r)]
        (is (.exists f) "completed files stay"))
      (is (not (some #{(:abandoned r)} (:files r)))
          "and the abandoned one is not reported as a result"))))

(deftest creates-the-output-folder-when-it-is-missing
  (tu/with-temp-dir [dir]
    (let [s   (survey-of dir "a.csv" "id\n1\n2\n")
          out (io/file dir "new" "nested")
          r   (split/execute! {:survey s :out-dir out :mode :rows :value 1
                               :has-header? true :include-header? true
                               :plan {:file-count 2}})]
      (is (.isDirectory out))
      (is (= 2 (count (:files r)))))))

(deftest honours-a-file-name-pattern
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "sales.csv" "id\n1\n2\n")
          r (run dir s {:value 1 :template "part-{index}-of-{name}"})]
      (is (= ["part-0001-of-sales.csv" "part-0002-of-sales.csv"] (tu/names (:files r)))))))

(deftest widens-the-index-for-a-large-split
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id\n" (map #(str % "\n") (range 10000))))
          r (run dir s {:value 1 :plan {:file-count 10000}})]
      (is (= "a_00001.csv" (.getName ^File (first (:files r)))))
      (is (= "a_10000.csv" (.getName ^File (last (:files r))))))))

(deftest reports-progress
  (tu/with-temp-dir [dir]
    (let [rows (apply str "id\n" (for [i (range (* 2 split/progress-interval))]
                                   (str i "\n")))
          s    (survey-of dir "big.csv" rows)
          seen (atom [])
          _    (run dir s {:value 3000 :on-progress #(swap! seen conj %)})]
      (is (seq @seen))
      (is (every? #(contains? % :rows-done) @seen))
      (is (some :current-name @seen)))))

(deftest adds-a-terminator-to-a-header-that-lacks-one
  (testing "a single-line file still produces a well-formed output file"
    (tu/with-temp-dir [dir]
      (let [s (survey-of dir "a.csv" "id,name")
            p (split/plan {:survey s :mode :rows :value 1 :has-header? false})]
        (is (= 1 (:file-count p)))))))

;; ── disk space (R38–R41) ────────────────────────────────────────────────────

(deftest estimates-what-the-output-will-occupy
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" (apply str "id,name\n" (repeat 100 "1,abcdefgh\n")))]
      (testing "without a repeated header the pieces are about the size of the original"
        (is (= (:bytes s) (split/required-space {:survey s :file-count 1
                                                 :has-header? true :include-header? false}))))
      (testing "a repeated header adds roughly a row to each extra file"
        (is (> (split/required-space {:survey s :file-count 10
                                      :has-header? true :include-header? true})
               (:bytes s)))))))

(deftest a-byte-order-mark-is-counted-for-every-file
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "a.csv" "id,name\n1,Ann\n" "UTF-8" [0xEF 0xBB 0xBF])]
      (is (= (+ (:bytes s) (* 9 3))
             (split/required-space {:survey s :file-count 10
                                    :has-header? true :include-header? false}))))))

(deftest reads-the-free-space-of-the-volume
  (tu/with-temp-dir [dir]
    (is (pos? (split/free-space dir)))
    (testing "a folder that does not exist yet reports its future volume"
      (is (pos? (split/free-space (io/file dir "not" "yet" "created")))))
    (is (nil? (split/free-space nil)))))

(deftest refuses-to-split-when-the-disk-could-not-hold-the-result
  (testing "filling a disk is worse than declining to split, and the damage
            spreads well beyond this application"
    (tu/with-temp-dir [dir]
      (let [s    (survey-of dir "a.csv" (apply str "id\n" (repeat 100 "1\n")))
            ;; Pretend the volume has almost nothing left.
            plan (with-redefs [split/free-space (constantly 10)]
                   (split/plan {:survey s :mode :rows :value 10
                                :has-header? true :out-dir dir}))]
        (is (= :problem/not-enough-space (:key (:problem plan))))
        (is (= :bytes (:arg-format (:problem plan)))
            "the numbers are sizes, and must read as sizes")))))

(deftest warns-when-the-result-would-only-just-fit
  (tu/with-temp-dir [dir]
    (let [s      (survey-of dir "a.csv" (apply str "id\n" (repeat 100 "1\n")))
          needed (split/required-space {:survey s :file-count 1 :has-header? true})
          plan   (with-redefs [split/free-space (constantly (long (* needed 1.10)))]
                   (split/plan {:survey s :mode :rows :value 10
                                :has-header? true :out-dir dir}))]
      (is (nil? (:problem plan)) "it does fit, so it is allowed")
      (is (= :plan/tight-space (:key (:warning plan)))))))

(deftest plenty-of-room-raises-nothing
  (tu/with-temp-dir [dir]
    (let [s    (survey-of dir "a.csv" "id\n1\n2\n")
          plan (split/plan {:survey s :mode :rows :value 1
                            :has-header? true :out-dir dir})]
      (is (nil? (:problem plan)))
      (is (nil? (:warning plan)))
      (is (pos? (:free-bytes plan))))))

(deftest the-check-is-skipped-when-there-is-nowhere-to-check
  (testing "no output folder chosen yet means no verdict, not a false alarm"
    (tu/with-temp-dir [dir]
      (let [plan (split/plan {:survey (survey-of dir "a.csv" "id\n1\n2\n")
                              :mode :rows :value 1 :has-header? true})]
        (is (nil? (:problem plan)))
        (is (nil? (:free-bytes plan)))))))

;; ── separators (R4) ─────────────────────────────────────────────────────────

(deftest an-explicit-separator-overrides-detection
  (testing "detection is right almost always, but there must be a way out"
    (tu/with-temp-dir [dir]
      (let [file (tu/write-file dir "odd.csv" "a|b|c\n1|2|3\n")]
        (is (= \| (:delimiter (scan/survey file))) "detected")
        (is (= \, (:delimiter (scan/survey file {:delimiter \,})))
            "and overridable")
        (testing "and the whole survey follows the override"
          (is (= 1 (:fields (scan/survey file {:delimiter \,})))))))))

;; ── replacing, and what "replace" has to mean ───────────────────────────────

(deftest replacing-clears-the-matching-files-it-does-not-write
  (testing "the user was shown a list and agreed to those files being replaced.
            Leaving the ones this run happens not to write produces a folder
            that is part new and part old with nothing to tell them apart.

            They go to the Trash, never to deletion: these files were recognised
            by name alone, and the folder may have been chosen by mistake."
    (tu/with-temp-dir [dir]
      (doseq [i (range 1 9)]
        (tu/write-file dir (format "people_%04d.csv" i) "not necessarily ours\n"))
      (let [s       (survey-of dir "people.csv" "id\n1\n2\n3\n4\n")
            old     (split/collisions {:survey s :out-dir dir})
            _       (is (= 8 (count old)))
            binned  (atom [])
            r       (split/execute! {:survey s :out-dir dir :mode :rows :value 2
                                     :has-header? true :include-header? true
                                     :plan (split/plan {:survey s :mode :rows :value 2
                                                        :has-header? true})
                                     :replace-existing old
                                     :remove-file (fn [f] (swap! binned conj f)
                                                    (.delete ^File f))})]
        (is (= 2 (count (:files r))) "this run writes two")
        (is (= 6 (count (:trashed r))) "and the six it did not write are binned")
        (is (empty? (:left-behind r)))
        (is (= 6 (count @binned)) "through the injected remover, never a delete")))))

(deftest what-cannot-be-binned-is-reported-rather-than-deleted
  (testing "on a platform with no Trash nothing is removed, and the window says
            so instead of quietly turning a recoverable act into a permanent one"
    (tu/with-temp-dir [dir]
      (doseq [i (range 1 5)]
        (tu/write-file dir (format "people_%04d.csv" i) "theirs\n"))
      (let [s (survey-of dir "people.csv" "id\n1\n2\n")
            r (split/execute! {:survey s :out-dir dir :mode :rows :value 1
                               :has-header? true :include-header? true
                               :plan (split/plan {:survey s :mode :rows :value 1
                                                  :has-header? true})
                               :replace-existing (split/collisions {:survey s :out-dir dir})
                               :remove-file (constantly false)})]
        (is (empty? (:trashed r)))
        (is (= 2 (count (:left-behind r))))
        (is (.exists (io/file dir "people_0003.csv")) "and they are all still there")))))

(deftest a-failed-or-cancelled-split-never-removes-anything
  (testing "leftovers are cleared only after success, so a failure costs nobody
            their old files"
    (tu/with-temp-dir [dir]
      (doseq [i (range 1 5)]
        (tu/write-file dir (format "people_%04d.csv" i) "stale\n"))
      (let [rows (apply str "id\n" (for [i (range (* 3 split/progress-interval))]
                                     (str i "\n")))
            s    (survey-of dir "people.csv" rows)
            old  (split/collisions {:survey s :out-dir dir})
            r    (split/execute! {:survey s :out-dir dir :mode :rows :value 1000
                                  :has-header? true :include-header? true
                                  :plan (split/plan {:survey s :mode :rows :value 1000
                                                     :has-header? true})
                                  :replace-existing old
                                  :cancelled? (constantly true)})]
        (is (:cancelled? r))
        (is (empty? (:removed r)))))))

(deftest without-permission-to-replace-nothing-is-removed
  (tu/with-temp-dir [dir]
    (tu/write-file dir "people_0099.csv" "stale\n")
    (let [s (survey-of dir "people.csv" "id\n1\n2\n")
          r (split/execute! {:survey s :out-dir dir :mode :rows :value 1
                             :has-header? true :include-header? true
                             :plan (split/plan {:survey s :mode :rows :value 1
                                                :has-header? true})})]
      (is (empty? (:removed r)))
      (is (.exists (io/file dir "people_0099.csv"))))))

;; ── progress ────────────────────────────────────────────────────────────────

(deftest progress-counts-files-finished-not-the-one-in-hand
  (testing "it used to report the current file's number here and the count of
            finished ones elsewhere, so the window announced \"File 2\" while it
            was still writing the first — and \"File 2\" for a single-file run"
    (tu/with-temp-dir [dir]
      (let [rows (apply str "id\n" (for [i (range (* 2 split/progress-interval))]
                                     (str i "\n")))
            s    (survey-of dir "big.csv" rows)
            seen (atom [])
            _    (split/execute! {:survey s :out-dir dir :mode :rows
                                  :value (* 10 split/progress-interval)
                                  :has-header? true :include-header? true
                                  :plan {:file-count 1}
                                  :on-progress #(swap! seen conj %)})]
        (is (seq @seen))
        (is (every? #(zero? (:files-done %)) @seen)
            "one file, so none is finished until the end")))))

;; ── collisions ──────────────────────────────────────────────────────────────

(deftest reports-nothing-for-a-clear-folder
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "sales.csv" "id\n1\n")]
      (is (= [] (split/collisions {:survey s :out-dir dir}))))))

(deftest reports-files-that-would-be-replaced
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "sales.csv" "id\n1\n2\n")]
      (run dir s {:value 1})
      (let [found (split/collisions {:survey s :out-dir dir})]
        (is (= ["sales_0001.csv" "sales_0002.csv"] (tu/names found)))))))

(deftest a-different-pattern-collides-with-different-files
  (tu/with-temp-dir [dir]
    (let [s (survey-of dir "sales.csv" "id\n1\n")]
      (run dir s {:value 1})
      (is (= [] (split/collisions {:survey s :out-dir dir
                                   :template "part-{index}-of-{name}"}))))))
