(ns csv-cleaver.api-test
  "The local HTTP service.

   Most of this drives the ring handler directly, with no socket: it is the same
   code the server runs, and a test that binds a port cannot be relied on to run
   anywhere. The two things a handler test cannot prove — that a multipart
   upload really is parsed, and that the archive really is a zip — are checked
   against a running server at the end.

   The security properties are tested as hard as the behaviour, because they are
   the whole reason this is safe to ship: no token, no answer; the input mode is
   enforced on every route that takes a file; and nothing binds anywhere but the
   loopback address."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [csv-cleaver.api.jobs :as jobs]
   [csv-cleaver.api.server :as server]
   [csv-cleaver.api.zip :as zip]
   [csv-cleaver.test-util :as tu]
   [muuntaja.core :as m])
  (:import
   (java.io ByteArrayOutputStream File)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util.zip ZipInputStream)))

(def token "test-token-0123456789")

(defn handler
  ([] (handler :both))
  ([input-mode]
   (let [registry (jobs/new-registry)]
     [(server/app {:registry registry :input-mode input-mode :token token})
      registry])))

(defn request
  "A ring request as muuntaja would hand it to the router."
  ([method uri] (request method uri nil))
  ([method uri body] (request method uri body token))
  ([method uri body auth]
   (cond-> {:request-method method
            :uri            uri
            :headers        (cond-> {"accept" "application/json"}
                              auth (assoc "authorization" (str "Bearer " auth)))}
     body (-> (assoc-in [:headers "content-type"] "application/json")
              (assoc :body (m/encode m/instance "application/json" body))))))

(defn body-of
  [response]
  (when-let [b (:body response)]
    (if (string? b)
      (try (m/decode m/instance "application/json" b) (catch Exception _ b))
      (m/decode m/instance "application/json" b))))

(defn sample-file
  ^File [dir]
  (tu/write-file dir "people.csv"
                 (apply str "id,name,town\n"
                        (for [i (range 1 61)]
                          (str i ",Name " i ",Town " i "\n")))))

;; ── The token is the whole boundary ─────────────────────────────────────────

(deftest nothing-is-answered-without-the-token
  (let [[app _] (handler)]
    (doseq [uri ["/api/health" "/api/capabilities" "/api/surveys" "/api/splits/x"]]
      (testing uri
        (is (= 401 (:status (app (request :get uri nil nil))))
            "no Authorization header at all")
        (is (= 401 (:status (app (request :get uri nil "wrong-token-entirely"))))
            "a token of the wrong length")
        (is (= 401 (:status (app (request :get uri nil (str/replace token #".$" "X")))))
            "a token differing in one character")))))

(deftest the-description-of-the-service-is-readable-without-it
  (testing "the browser fetches this with no way to add a header, and it says
            nothing about the machine it is running on"
    (let [[app _] (handler)]
      (is (= 200 (:status (app (request :get "/api/openapi.json" nil nil)))))
      (is (= 200 (:status (app (request :get "/api-docs/index.html" nil nil))))))))

(deftest a-bearer-prefix-is-optional-and-case-insensitive
  ;; Written out rather than looped, so a failure names the form that failed.
  (let [[app _] (handler)
        try-header (fn [value]
                     (:status (app {:request-method :get
                                    :uri            "/api/health"
                                    :headers        {"authorization" value
                                                     "accept" "application/json"}})))]
    (is (= 200 (try-header (str "Bearer " token))))
    (is (= 200 (try-header (str "bearer " token))))
    (is (= 200 (try-header token)) "a bare token, for a caller that forgot")))

(deftest a-generated-token-is-long-and-not-repeated
  (let [tokens (repeatedly 200 server/generate-token)]
    (is (every? #(<= 32 (count %)) tokens))
    (is (= 200 (count (distinct tokens))))
    (is (every? #(re-matches #"[A-Za-z0-9_-]+" %) tokens)
        "safe to paste into a header without escaping")))

;; ── What the service will accept ────────────────────────────────────────────

(deftest capabilities-report-the-mode-the-service-was-started-with
  (testing "the whole point of the option is that a caller can ask, rather than
            finding out by being refused"
    (doseq [[mode path? upload?] [[:none false false]
                                  [:path true false]
                                  [:upload false true]
                                  [:both true true]]]
      (testing mode
        (let [[app _] (handler mode)
              body    (body-of (app (request :get "/api/capabilities")))]
          (is (= (name mode) (:inputMode body)))
          (is (= path? (:acceptsPath body)))
          (is (= upload? (:acceptsUpload body))))))))

(deftest a-path-is-refused-unless-the-mode-allows-paths
  (tu/with-temp-dir [dir]
    (let [file (sample-file dir)]
      (doseq [mode [:none :upload]]
        (testing mode
          (let [[app _]  (handler mode)
                response (app (request :post "/api/surveys"
                                       {:file (.getAbsolutePath file)}))]
            (is (= 400 (:status response)))
            (is (str/includes? (str (:error (body-of response))) "--api-input")
                "the refusal names the option that caused it")))))))

(deftest a-request-with-no-file-at-all-is-refused-clearly
  (let [[app _]  (handler)
        response (app (request :post "/api/surveys" {}))]
    (is (= 400 (:status response)))
    (is (str/includes? (str (:error (body-of response))) "file"))))

(deftest a-path-that-cannot-be-read-is-reported-not-guessed-at
  (tu/with-temp-dir [dir]
    (let [[app _] (handler :path)
          try-path (fn [p] (body-of (app (request :post "/api/surveys" {:file p}))))]
      (is (str/includes? (:error (try-path (str (io/file dir "absent.csv"))))
                         "No such file"))
      (is (str/includes? (:error (try-path (.getAbsolutePath ^File dir)))
                         "Not a file")))))

;; ── Reading a file ──────────────────────────────────────────────────────────

(deftest a-survey-describes-the-file-and-writes-nothing
  (tu/with-temp-dir [dir]
    (let [file     (sample-file dir)
          before   (set (map str (.listFiles ^File dir)))
          [app _]  (handler :path)
          body     (body-of (app (request :post "/api/surveys"
                                          {:file (.getAbsolutePath file)})))]
      (is (= 61 (:records body)))
      (is (= 3 (:fields body)))
      (is (= "," (:delimiter body)))
      (is (= "UTF-8" (:encoding body)))
      (is (true? (:tabular body)))
      (is (true? (:healthy body)))
      (is (= "header" (get-in body [:header :verdict])))
      (is (= [["id" "name" "town"] ["1" "Name 1" "Town 1"]] (:firstRows body))
          "the first rows are returned so a caller can show them to someone")
      (is (= before (set (map str (.listFiles ^File dir))))
          "the folder is exactly as it was"))))

(deftest a-survey-honours-an-explicit-delimiter
  (tu/with-temp-dir [dir]
    (let [file    (tu/write-file dir "semi.csv" "a;b;c\n1;2;3\n4;5;6\n")
          [app _] (handler :path)
          ask     (fn [extra]
                    (body-of (app (request :post "/api/surveys"
                                           (merge {:file (.getAbsolutePath file)}
                                                  extra)))))]
      (is (= 3 (:fields (ask {:delimiter ";"}))))
      (is (= ";" (:delimiter (ask {:delimiter ";"})))))))

(deftest a-plan-says-what-would-happen-without-doing-it
  (tu/with-temp-dir [dir]
    (tu/with-temp-dir [out]
      (let [file    (sample-file dir)
            [app _] (handler :path)
            body    (body-of (app (request :post "/api/plans"
                                           {:file    (.getAbsolutePath file)
                                            :value   20
                                            :outDir  (.getAbsolutePath ^File out)})))]
        (is (= 3 (:fileCount body)))
        (is (= 60 (:dataRows body)))
        (is (= 20 (:rowsPerFile body)))
        (is (true? (:exact body)))
        (is (zero? (count (.listFiles ^File out))) "nothing was written")))))

(deftest a-plan-that-cannot-work-says-so-in-words-and-in-a-code
  (tu/with-temp-dir [dir]
    (let [file    (sample-file dir)
          [app _] (handler :path)
          body    (body-of (app (request :post "/api/plans"
                                         {:file (.getAbsolutePath file) :value 0})))]
      (is (= "problem/rows-needed" (get-in body [:problem :code]))
          "a code, so a caller can branch on it")
      (is (seq (get-in body [:problem :message]))
          "and a sentence, so a caller can log something a person can read"))))

;; ── Splitting ───────────────────────────────────────────────────────────────

(defn wait-for
  "Poll a job until it is no longer running. Fails rather than hanging."
  [app id]
  (loop [tries 0]
    (let [body (body-of (app (request :get (str "/api/splits/" id))))]
      (cond
        (not= "running" (:state body)) body
        (< tries 200)                  (do (Thread/sleep 25) (recur (inc tries)))
        :else                          (do (is false "the job never finished") body)))))

(deftest a-split-runs-in-the-background-and-reports-what-it-wrote
  (tu/with-temp-dir [dir]
    (tu/with-temp-dir [out]
      (let [file     (sample-file dir)
            [app _]  (handler :path)
            started  (app (request :post "/api/splits"
                                   {:file   (.getAbsolutePath file)
                                    :value  20
                                    :outDir (.getAbsolutePath ^File out)}))
            body     (body-of started)]
        (is (= 202 (:status started))
            "accepted, not done — the caller is not made to hold a connection open")
        (is (= "running" (:state body)))
        (is (string? (:id body)))
        (let [finished (wait-for app (:id body))]
          (is (= "finished" (:state finished)))
          (is (= 60 (get-in finished [:result :rows])))
          (is (= 3 (count (get-in finished [:result :files]))))
          (is (= 3 (count (.listFiles ^File out))))
          (is (every? #(str/starts-with? % (.getAbsolutePath ^File out))
                      (get-in finished [:result :files]))
              "everything landed in the folder that was named and nowhere else"))))))

(deftest a-split-repeats-the-header-into-every-file-by-default
  (tu/with-temp-dir [dir]
    (tu/with-temp-dir [out]
      (let [file    (sample-file dir)
            [app _] (handler :path)
            id      (:id (body-of (app (request :post "/api/splits"
                                                {:file   (.getAbsolutePath file)
                                                 :value  20
                                                 :outDir (.getAbsolutePath ^File out)}))))]
        (wait-for app id)
        (doseq [^File f (.listFiles ^File out)]
          (is (str/starts-with? (slurp f) "id,name,town\n")
              (str (.getName f) " should begin with the column names")))))))

(deftest a-split-that-cannot-work-is-refused-rather-than-started
  (testing "a job that dies immediately is a worse answer than a refusal: the
            caller has to poll to discover a mistake it made in the request"
    (tu/with-temp-dir [dir]
      (let [file     (sample-file dir)
            [app reg] (handler :path)
            response (app (request :post "/api/splits"
                                   {:file (.getAbsolutePath file) :value 0}))]
        (is (= 409 (:status response)))
        (is (= "problem/rows-needed" (:code (body-of response))))
        (is (empty? @reg) "and no job was registered")))))

(deftest a-split-that-would-produce-one-file-is-refused
  (tu/with-temp-dir [dir]
    (let [file     (sample-file dir)
          [app _]  (handler :path)
          response (app (request :post "/api/splits"
                                 {:file (.getAbsolutePath file) :value 5000}))]
      (is (= 409 (:status response)))
      (is (= "problem/nothing-to-split" (:code (body-of response)))))))

(deftest nothing-already-on-disk-is-replaced
  (testing "the application's central promise, over the API as well as the window"
    (tu/with-temp-dir [dir]
      (tu/with-temp-dir [out]
        (let [file     (sample-file dir)
              existing (tu/write-file out "people_1.csv" "do not touch me\n")
              [app _]  (handler :path)
              id       (:id (body-of (app (request :post "/api/splits"
                                                   {:file     (.getAbsolutePath file)
                                                    :value    20
                                                    :template "{name}_{index}"
                                                    :outDir   (.getAbsolutePath ^File out)}))))
              finished (wait-for app id)]
          (is (= "do not touch me\n" (slurp existing))
              "the file that was already there is untouched")
          (is (#{"finished" "failed"} (:state finished))))))))

(deftest an-unknown-job-is-not-invented
  (let [[app _] (handler)]
    (doseq [uri ["/api/splits/nope" "/api/splits/nope/archive"]]
      (is (= 404 (:status (app (request :get uri)))) uri))
    (is (= 404 (:status (app (request :delete "/api/splits/nope")))))))

;; ── The job registry on its own ─────────────────────────────────────────────

(deftest a-finished-job-is-kept-for-a-while-and-then-forgotten
  (tu/with-temp-dir [dir]
    (let [registry (jobs/new-registry)
          work-dir (io/file dir "work")]
      (.mkdirs work-dir)
      (spit (io/file work-dir "leftover.csv") "x\n")
      (swap! registry assoc
             "recent" {:id "recent" :state :finished :started-at 0
                       :finished-at (System/currentTimeMillis)}
             "ancient" {:id "ancient" :state :finished :started-at 0
                        :finished-at (- (System/currentTimeMillis)
                                        jobs/keep-finished-ms 1000)
                        :work-dir work-dir}
             "busy"   {:id "busy" :state :running :started-at 0})
      (jobs/expire! registry)
      (is (some? (jobs/fetch registry "recent")) "a slow poller can still look")
      (is (some? (jobs/fetch registry "busy")) "a running job is never dropped")
      (is (nil? (jobs/fetch registry "ancient")))
      (is (not (.exists work-dir))
          "and the temporary folder it owned went with it"))))

(deftest stopping-the-service-cancels-what-is-running-and-clears-up
  (tu/with-temp-dir [dir]
    (let [registry (jobs/new-registry)
          work-dir (io/file dir "work")
          flag     (atom false)]
      (.mkdirs work-dir)
      (swap! registry assoc "busy" {:id "busy" :state :running :started-at 0
                                    :cancelled flag :work-dir work-dir})
      (jobs/stop-all! registry)
      (is (true? @flag) "the running job was asked to stop")
      (is (empty? @registry))
      (is (not (.exists work-dir))))))

(deftest running-jobs-are-counted-for-capabilities
  (let [registry (jobs/new-registry)]
    (swap! registry assoc
           "a" {:id "a" :state :running :started-at 0}
           "b" {:id "b" :state :running :started-at 0}
           "c" {:id "c" :state :finished :started-at 0 :finished-at 1})
    (is (= 2 (jobs/running-count registry)))))

;; ── The archive ─────────────────────────────────────────────────────────────

(defn zip-entries
  "Every entry name and its content, read back out of a zip stream."
  [stream]
  (with-open [zis (ZipInputStream. stream)]
    (loop [found {}]
      (if-let [entry (.getNextEntry zis)]
        (let [out (ByteArrayOutputStream.)]
          (io/copy zis out)
          (recur (assoc found (.getName entry) (.toString out "UTF-8"))))
        found))))

(deftest an-archive-carries-every-file-under-its-own-name
  (tu/with-temp-dir [dir]
    (let [files (mapv #(tu/write-file dir (str "part-" % ".csv") (str "row " % "\n"))
                      (range 1 4))]
      (is (= {"part-1.csv" "row 1\n" "part-2.csv" "row 2\n" "part-3.csv" "row 3\n"}
             (zip-entries (zip/stream files)))))))

(deftest two-files-with-the-same-name-do-not-collide-in-the-archive
  (testing "a split cannot normally produce two files with one name, but an
            archive with a repeated entry is a broken archive and this is not
            worth leaving to chance"
    (tu/with-temp-dir [a]
      (tu/with-temp-dir [b]
        (let [files [(tu/write-file a "same.csv" "from a\n")
                     (tu/write-file b "same.csv" "from b\n")]
              found (zip-entries (zip/stream files))]
          (is (= 2 (count found)))
          (is (= #{"from a\n" "from b\n"} (set (vals found)))))))))

(deftest an-archive-of-a-job-that-wrote-nothing-is-refused-not-empty
  (let [registry (jobs/new-registry)
        app      (server/app {:registry registry :input-mode :path :token token})]
    (swap! registry assoc "empty" {:id "empty" :state :finished :started-at 0
                                   :finished-at 1 :result {:files []}})
    (is (= 409 (:status (app (request :get "/api/splits/empty/archive")))))))

(deftest the-description-offers-only-what-the-service-will-accept
  (testing "a caller reading the Swagger page should not be offered a file
            picker by a service that would refuse the upload, nor a JSON body by
            one that would refuse the path.

            Worth a test of its own because reitit describes a multipart body by
            replacing the content map rather than adding to it, so `both` needs
            the JSON form putting back by hand and would otherwise lose it
            silently."
    (let [content-types
          (fn [mode]
            (let [[app _] (handler mode)
                  doc     (body-of (app (request :get "/api/openapi.json" nil nil)))]
              ;; Keys come back as keywords such as :multipart/form-data, so
              ;; the namespace has to be kept — `name` would drop half of it.
              (set (map #(subs (str %) 1)
                        (keys (get-in doc [:paths (keyword "/api/splits")
                                           :post :requestBody :content]))))))]
      (is (not (contains? (content-types :path) "multipart/form-data")))
      (is (contains? (content-types :path) "application/json"))
      (is (= #{"multipart/form-data"} (content-types :upload)))
      (is (= #{"application/json" "multipart/form-data"} (content-types :both))))))

(deftest every-endpoint-is-marked-as-needing-the-token
  (let [[app _] (handler)
        doc     (body-of (app (request :get "/api/openapi.json" nil nil)))]
    (doseq [[path operations] (:paths doc)
            [verb operation]  operations]
      (is (= [{:bearer []}] (:security operation))
          (str verb " " (name path))))
    (is (= {:type "http" :scheme "bearer"
            :description "The token printed at startup."}
           (get-in doc [:components :securitySchemes :bearer])))))

;; ── Against a real server ───────────────────────────────────────────────────

(def crlf "\r\n")

(defn multipart-body
  "A multipart/form-data body. Hand-built so that the test exercises the same
   parsing a real client would provoke."
  ^bytes [boundary parts]
  (let [out (ByteArrayOutputStream.)
        w   #(.write out (.getBytes ^String % "UTF-8"))]
    (doseq [{:keys [name filename value content]} parts]
      (w (str "--" boundary crlf))
      (w (str "Content-Disposition: form-data; name=\"" name "\""
              (when filename (str "; filename=\"" filename "\"")) crlf))
      (when filename (w (str "Content-Type: text/csv" crlf)))
      (w crlf)
      (if content (.write out ^bytes content) (w (str value)))
      (w crlf))
    (w (str "--" boundary "--" crlf))
    (.toByteArray out)))

(defn send-multipart
  [url auth parts]
  (let [boundary "csvcleavertestboundary"
        client   (HttpClient/newHttpClient)
        posted   (-> (HttpRequest/newBuilder (URI/create url))
                     (.header "Content-Type" (str "multipart/form-data; boundary=" boundary))
                     (.header "Authorization" (str "Bearer " auth))
                     (.POST (HttpRequest$BodyPublishers/ofByteArray
                             (multipart-body boundary parts)))
                     (.build))
        response (.send client posted (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body   (m/decode m/instance "application/json" (.body response))}))

(defn get-bytes
  [url auth]
  (let [client   (HttpClient/newHttpClient)
        asked    (-> (HttpRequest/newBuilder (URI/create url))
                     (.header "Authorization" (str "Bearer " auth))
                     (.build))
        response (.send client asked (HttpResponse$BodyHandlers/ofInputStream))]
    {:status (.statusCode response) :stream (.body response)}))

(defn wait-over-http
  "Poll a job through a real socket until it stops running."
  [url id]
  (loop [tries 0]
    (let [job (m/decode m/instance "application/json"
                        (:stream (get-bytes (str url "/api/splits/" id) token)))]
      (cond
        (not= "running" (:state job)) job
        (< tries 200)                 (do (Thread/sleep 25) (recur (inc tries)))
        :else                         (do (is false "the job never finished") job)))))

(defn with-service
  "Run `f` with a service on a port nothing else is using."
  [input-mode f]
  (let [service (server/start! {:port 0 :input-mode input-mode :token token})]
    (try (f service) (finally ((:stop! service))))))

(deftest an-upload-is-split-and-fetched-back-as-an-archive
  (testing "the whole point of upload mode: the caller never names a path on
            this machine, in either direction"
    (with-service :upload
      (fn [{:keys [url]}]
        (let [csv (apply str "id,name\n" (for [i (range 1 41)] (str i ",Name " i "\n")))
              {:keys [status body]}
              (send-multipart (str url "/api/splits") token
                              [{:name "file" :filename "people.csv"
                                :content (.getBytes ^String csv "UTF-8")}
                               {:name "value" :value "10"}])]
          (is (= 202 status))
          (is (string? (:archive body))
              "and it is told where to fetch the results, since it cannot see them")
          (is (= "finished" (:state (wait-over-http url (:id body)))))
          (let [{:keys [status stream]} (get-bytes (str url "/api/splits/" (:id body)
                                                        "/archive") token)
                entries (zip-entries stream)]
            (is (= 200 status))
            (is (= 4 (count entries)))
            (is (every? #(str/starts-with? % "id,name\n") (vals entries))
                "each carries the header")))))))

(deftest an-upload-may-not-name-a-folder-to-write-into
  (testing "outDir is a path on this machine, and upload mode exists precisely
            so that a caller cannot name one"
    (with-service :upload
      (fn [{:keys [url]}]
        (let [{:keys [status body]}
              (send-multipart (str url "/api/splits") token
                              [{:name "file" :filename "x.csv"
                                :content (.getBytes "a,b\n1,2\n3,4\n" "UTF-8")}
                               {:name "value" :value "1"}
                               {:name "outDir" :value "/tmp/somewhere"}])]
          (is (= 400 status))
          (is (str/includes? (str (:error body)) "--api-input")))))))

(deftest an-upload-is-refused-when-the-mode-does-not-allow-one
  (with-service :path
    (fn [{:keys [url]}]
      (let [{:keys [status body]}
            (send-multipart (str url "/api/surveys") token
                            [{:name "file" :filename "x.csv"
                              :content (.getBytes "a,b\n1,2\n" "UTF-8")}])]
        (is (= 400 status))
        (is (str/includes? (str (:error body)) "--api-input path"))))))

(deftest the-service-binds-to-the-loopback-address-and-nowhere-else
  (testing "not configurable, and not something to discover by accident"
    (is (= "127.0.0.1" server/loopback))
    (with-service :none
      (fn [{:keys [url]}]
        (is (str/starts-with? url "http://127.0.0.1:"))))))
