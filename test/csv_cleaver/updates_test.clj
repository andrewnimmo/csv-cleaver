(ns csv-cleaver.updates-test
  "The update check, tested offline.

   The network is injectable precisely so these tests never touch it: every
   outcome the real GitHub could produce — a newer tag, the same tag, junk,
   an outage — is played back through the stub. What cannot be tested here
   is the real endpoint answering, which is exercised once by a person the
   day a release exists to find; the contract these tests pin is that no
   possible answer, including no answer, escapes as anything other than the
   three documented statuses."
  (:require
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.state :as state]
   [csv-cleaver.updates :as updates]))

;; ── Version arithmetic ──────────────────────────────────────────────────────

(deftest versions-compare-numerically
  (testing "the one comparison that matters: is the candidate newer?"
    (is (updates/newer? "2.0.0" "2.0.1"))
    (is (updates/newer? "2.0.0" "2.1.0"))
    (is (updates/newer? "2.9.9" "10.0.0")
        "numeric, not lexicographic — the 10 > 9 case that string compare gets wrong")
    (is (not (updates/newer? "2.0.0" "2.0.0")))
    (is (not (updates/newer? "2.1.0" "2.0.9")))
    (is (not (updates/newer? "2.1" "2.1.0"))
        "a tie padded to equal length is a tie, not an update")
    (is (updates/newer? "v2.0.0" "V2.0.1")
        "tag prefixes are noise in either case")))

(deftest unparseable-versions-never-produce-an-update
  (testing "an update banner must be impossible to trigger with a tag that
            carries no numbers — a renamed release scheme should degrade to
            silence, not to a lie"
    (is (not (updates/newer? "2.0.0" "latest")))
    (is (not (updates/newer? "garbage" "2.0.1")))
    (is (not (updates/newer? "" "")))))

;; ── The endpoint comes from branding, or nowhere ────────────────────────────

(deftest endpoint-derived-from-homepage
  (is (= "https://api.github.com/repos/andrewnimmo/csv-cleaver/releases/latest"
         (updates/releases-endpoint "https://github.com/andrewnimmo/csv-cleaver")))
  (is (= "https://api.github.com/repos/o/r/releases/latest"
         (updates/releases-endpoint "https://github.com/o/r/"))
      "a trailing slash is someone's hand-edited branding.edn, not an error")
  (testing "a rebrand pointing elsewhere disables the feature rather than
            asking this repository about someone else's fork"
    (is (nil? (updates/releases-endpoint "https://example.com/my-app")))
    (is (nil? (updates/releases-endpoint nil)))))

;; ── Parsing what GitHub returns ─────────────────────────────────────────────

(def a-real-response
  "The two fields used, shaped as the live API shapes them, plus the noise
   fields a real response carries."
  "{\"tag_name\":\"v2.1.0\",\"html_url\":\"https://github.com/andrewnimmo/csv-cleaver/releases/tag/v2.1.0\",\"assets\":[],\"draft\":false}")

(deftest parses-the-two-facts-needed
  (is (= {:version "2.1.0"
          :url     "https://github.com/andrewnimmo/csv-cleaver/releases/tag/v2.1.0"}
         (updates/parse-latest a-real-response))))

(deftest surprising-shapes-parse-to-nothing
  (is (nil? (updates/parse-latest "not json at all")))
  (is (nil? (updates/parse-latest "{\"message\":\"Not Found\"}"))
      "GitHub's own 404 body — a repo with no releases yet")
  (is (nil? (updates/parse-latest "{\"tag_name\":42,\"html_url\":\"x\"}"))
      "wrong types are a surprise, not an update"))

;; ── The whole check, network played back ────────────────────────────────────

(deftest a-newer-release-is-an-update
  (is (= {:status  :update-available
          :version "2.1.0"
          :url     "https://github.com/andrewnimmo/csv-cleaver/releases/tag/v2.1.0"}
         (updates/check! "2.0.0" (fn [_] a-real-response)))))

(deftest the-same-release-is-up-to-date
  (is (= {:status :up-to-date}
         (updates/check! "2.1.0" (fn [_] a-real-response)))))

(deftest every-failure-collapses-to-error
  (testing "offline"
    (is (= {:status :error}
           (updates/check! "2.0.0" (fn [_] (throw (java.net.ConnectException.)))))))
  (testing "rate-limited or any non-200, surfaced by fetch as a throw"
    (is (= {:status :error}
           (updates/check! "2.0.0" (fn [_] (throw (ex-info "403" {})))))))
  (testing "junk body"
    (is (= {:status :error}
           (updates/check! "2.0.0" (fn [_] "<html>proxy login</html>"))))))

;; ── The window's side of the contract ───────────────────────────────────────

(deftest clicking-check-shows-checking-and-asks-once
  (let [{:keys [state effects]}
        (state/handle state/initial {:event/type ::state/update-check-clicked})]
    (is (= {:status :checking} (:update state)))
    (is (= [[:check-updates {:quiet? false}]] effects))))

(deftest a-loud-result-is-shown-whatever-it-says
  (doseq [result [{:status :up-to-date}
                  {:status :error}
                  {:status :update-available :version "9.9.9" :url "u"}]]
    (let [{:keys [state]}
          (state/handle state/initial {:event/type ::state/update-checked
                                       :result result :quiet? false})]
      (is (= result (:update state))))))

(deftest a-quiet-result-only-survives-when-it-matters
  (testing "the startup check must never manufacture a status message"
    (doseq [result [{:status :up-to-date} {:status :error}]]
      (let [{:keys [state]}
            (state/handle state/initial {:event/type ::state/update-checked
                                         :result result :quiet? true})]
        (is (nil? (:update state))
            (str (:status result) " from the quiet check must leave no trace")))))
  (testing "except an actual update, which is the point of opting in"
    (let [result {:status :update-available :version "2.1.0" :url "u"}
          {:keys [state]}
          (state/handle state/initial {:event/type ::state/update-checked
                                       :result result :quiet? true})]
      (is (= result (:update state))))))

(deftest the-link-opens-the-release-page-and-nothing-else
  (let [armed (assoc state/initial
                     :update {:status :update-available :version "2.1.0"
                              :url "https://example.invalid/release"})
        {:keys [effects]}
        (state/handle armed {:event/type ::state/update-link-clicked})]
    (is (= [[:open-url "https://example.invalid/release"]] effects))))

(deftest the-startup-opt-in-is-remembered
  (let [{:keys [state effects]}
        (state/handle state/initial {:event/type ::state/update-on-start-toggled
                                     :enabled? true})]
    (is (true? (:check-updates-on-start? state)))
    (is (= [[:save-prefs {:check-updates-on-start? true}]] effects))))
