(ns csv-cleaver.api.server
  "An optional HTTP service exposing what the window does, for local automation.

   Three things about it are deliberate and should stay that way.

   It is **off** unless asked for. A desktop application that quietly listens on
   a port is not what anyone installed.

   It binds to **the loopback interface only**. Nothing outside this machine can
   reach it, whatever the firewall is doing.

   It requires a **token** on every call, printed once at startup. With
   `--api-input path` a caller can ask the application to read any file the user
   can read, so that token is the entire security boundary — which is why
   `--api-input` exists, and why `none` and `upload` are offered for anyone who
   would rather not extend it."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [csv-cleaver.api.jobs :as jobs]
   [csv-cleaver.api.zip :as zip]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.files :as files]
   [csv-cleaver.format :as format]
   [csv-cleaver.i18n :as i18n]
   [csv-cleaver.naming :as naming]
   [csv-cleaver.scan :as scan]
   [csv-cleaver.split :as split]
   [csv-cleaver.state :as state]
   [malli.json-schema :as json-schema]
   [muuntaja.core :as m]
   [org.httpkit.server :as http]
   [reitit.coercion.malli]
   [reitit.openapi :as openapi]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.malli]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.multipart :as multipart]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger-ui :as swagger-ui])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.security SecureRandom)
   (java.util Base64)))

(def loopback
  "The only interface this ever binds to."
  "127.0.0.1")

(def default-port
  "Unregistered, and unlikely to collide with anything a developer is running."
  8377)

(def max-upload-bytes
  "Ceiling on an uploaded file, reported through /api/capabilities so a caller
   need not discover it by failing.

   Modest on purpose. An uploaded body is held in memory before it reaches
   disk, and a desktop application that can be made to allocate a gigabyte by
   one HTTP request is a denial of service with extra steps. The files this
   application exists for are larger than this, which is what `--api-input
   path` is for: it never copies the file at all."
  (* 256 1024 1024))

(defn generate-token
  "A token for this run. SecureRandom, not Math/random: it is the only thing
   between a caller and the user's file system."
  []
  (let [bytes (byte-array 24)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

;; ── Turning internal maps into something a caller can read ──────────────────

(def ^:private english
  ;; The API answers in English regardless of the window's language. A caller is
  ;; a program: it wants a stable string it can log and a code it can branch on,
  ;; not something that changes with the user's locale.
  (delay (i18n/context i18n/fallback-tag)))

(defn- problem-code
  "The keyword naming a problem, as a string — the part a caller should branch
   on. The sentence beside it is for a human reading a log.

   Qualified, e.g. \"problem/not-enough-space\": these are the same names the
   specification uses, and a caller comparing against the document should not
   have to wonder whether half of it was dropped."
  [m]
  (when-let [k (cond (keyword? m) m (:key m) (:key m))]
    (subs (str k) 1)))

(defn- problem-view
  [m]
  (when m
    (cond-> {:message (format/message @english m)}
      (problem-code m) (assoc :code (problem-code m)))))

(defn survey-view
  [survey]
  {:file          (.getAbsolutePath ^File (:file survey))
   :bytes         (:bytes survey)
   :records       (:records survey)
   :fields        (:fields survey)
   :delimiter     (str (:delimiter survey))
   :encoding      (get-in survey [:encoding :label])
   :encodingBasis (name (get-in survey [:encoding :basis]))
   :tabular       (boolean (:tabular? survey))
   :healthy       (boolean (:healthy? survey))
   :damage        {:ragged            (get-in survey [:damage :ragged] 0)
                   :strayQuote        (get-in survey [:damage :stray-quote] 0)
                   :unterminatedQuote (get-in survey [:damage :unterminated-quote] 0)}
   :header        {:verdict (name (get-in survey [:header :verdict]))
                   :score   (get-in survey [:header :score])
                   :likely  (boolean (:header-likely? survey))}
   :firstRows     (:preview survey)})

(defn plan-view
  [plan]
  (cond-> {:fileCount    (:file-count plan)
           :exact        (boolean (:exact? plan))
           :dataRows     (:data-rows plan)
           :rowsPerFile  (:rows-per-file plan)
           :lastFileRows (:last-file-rows plan)}
    (:row-cap plan)        (assoc :rowCap (:row-cap plan))
    (:required-bytes plan) (assoc :requiredBytes (:required-bytes plan))
    (:free-bytes plan)     (assoc :freeBytes (:free-bytes plan))
    ;; A problem means the split cannot go ahead; a warning means it can but the
    ;; user should know something. Both carry a code to branch on.
    (:problem plan)        (assoc :problem (problem-view (:problem plan)))
    (:warning plan)        (assoc :warning (problem-view (:warning plan)))))

;; ── What a request asks for ─────────────────────────────────────────────────
;;
;; The same three endpoints accept either a JSON body naming a path, or a
;; multipart upload carrying the bytes. Which of those is allowed is fixed at
;; startup by --api-input; the settings are otherwise identical, so they are
;; read through one pair of accessors and everything downstream is unaware of
;; how the file arrived.

(defn settings
  "The split settings a request carries, from whichever half of it holds them."
  [request]
  (let [{:keys [body multipart]} (:parameters request)]
    (merge (dissoc (or multipart {}) :file) (or body {}))))

(defn- upload-part [request] (get-in request [:parameters :multipart :file]))
(defn- given-path  [request] (get-in request [:parameters :body :file]))

(defn- upload-attempted?
  "Whether the caller sent a multipart body at all.

   In a mode that does not allow uploads the parts are never parsed, so there is
   nothing to find in :parameters. The shape of the request is enough to give a
   refusal that names the reason instead of a puzzled one about no file having
   been given."
  [request]
  (boolean (some-> (get-in request [:headers "content-type"])
                   (str/starts-with? "multipart/form-data"))))

(defn- temp-copy
  "An uploaded part, put somewhere this process can read it.

   `File.getName` on the client's filename discards any directory part, so an
   upload announcing itself as \"../../.ssh/config\" lands in the temporary
   folder like anything else."
  [{:keys [tempfile filename]}]
  (let [dir  (.toFile (Files/createTempDirectory "csv-cleaver-upload"
                                                 (make-array FileAttribute 0)))
        dest (io/file dir "input"
                      (or (some-> filename not-empty (File.) (.getName))
                          "upload.csv"))]
    (io/make-parents dest)
    (io/copy tempfile dest)
    {:file dest :work-dir dir}))

(defn resolve-input
  "The file a request is about, or {:error …} explaining why not.

   Which routes are open depends on --api-input, and a caller can ask for that
   through /api/capabilities rather than discovering it by being refused."
  [{:keys [input-mode]} request]
  (let [path      (given-path request)
        upload    (upload-part request)
        uploaded? (or (some? upload) (upload-attempted? request))]
    (cond
      (= :none input-mode)
      {:error "This service was started with --api-input none, so it accepts no files."}

      (and path (not (#{:path :both} input-mode)))
      {:error (str "A file path was given, but this service was started with "
                   "--api-input " (name input-mode) ".")}

      (and uploaded? (not (#{:upload :both} input-mode)))
      {:error (str "A file was uploaded, but this service was started with "
                   "--api-input " (name input-mode) ".")}

      upload (temp-copy upload)

      path
      (let [f (io/file path)]
        (cond
          (not (.exists f))  {:error (str "No such file: " path)}
          (not (.isFile f))  {:error (str "Not a file: " path)}
          (not (.canRead f)) {:error (str "Cannot read: " path)}
          :else              {:file f}))

      :else {:error "Give either a file path or an uploaded file."})))

(defn split-options
  "Turn a file and a settings map into what the engine takes.

   Every setting has the same default the window uses, so a caller that sends
   nothing but a file and a row count gets the behaviour a user would get by
   dropping that file on the window and pressing Split."
  [^File file asked]
  (let [survey  (scan/survey file {:delimiter (some-> (:delimiter asked) first)})
        mode    (if (= "size" (:mode asked)) :bytes :rows)
        out-dir (if-let [d (not-empty (str (:outDir asked)))]
                  (io/file d)
                  (state/default-out-dir {} file))
        pick    (fn [k fallback]
                  (if (contains? asked k) (boolean (get asked k)) fallback))
        base    {:survey          survey
                 :mode            mode
                 :value           (:value asked)
                 :has-header?     (pick :hasHeader (:header-likely? survey))
                 :include-header? (pick :includeHeader true)
                 :excel-safe?     (pick :excelSafe true)
                 :out-dir         out-dir
                 :template        (or (not-empty (str (:template asked)))
                                      naming/default-template)}]
    (assoc base :plan (split/plan base) :survey-view (survey-view survey))))

;; ── Request shapes ──────────────────────────────────────────────────────────
;;
;; Declared twice, because they arrive two ways. The JSON body is [:maybe …]:
;; a multipart request has no JSON body at all, and a schema that insists on a
;; map would reject every upload before the handler saw it.

(def settings-fields
  [[:mode {:optional true
           :description "\"rows\" (the default) or \"size\"."} [:enum "rows" "size"]]
   [:value {:optional true
            :description "Rows per file, or bytes per file when mode is \"size\"."} :int]
   [:delimiter {:optional true
                :description (str "Field separator, one character. Detected from "
                                  "the file when omitted.")} :string]
   [:hasHeader {:optional true
                :description (str "Whether the first row names the columns. "
                                  "Detected when omitted.")} :boolean]
   [:includeHeader {:optional true
                    :description "Repeat that row in every output file."} :boolean]
   [:excelSafe {:optional true
                :description (str "Also roll over at Excel's row limit when "
                                  "splitting by size.")} :boolean]
   [:outDir {:optional true
             :description (str "Folder to write into. A new folder beside the "
                               "input, named after it, when omitted.")} :string]
   [:template {:optional true
               :description "Output name pattern, e.g. {name}_{index}."} :string]])

(def body-map-schema
  (into [:map [:file {:optional true
                      :description "Path to a file on this machine."} :string]]
        settings-fields))

(def body-schema
  ;; [:maybe …] because a multipart request has no JSON body at all, and a
  ;; schema insisting on a map would reject every upload before the handler saw
  ;; it.
  [:maybe body-map-schema])

(def multipart-schema
  (into [:map [:file {:optional true
                      :description "The CSV file itself."} reitit.ring.malli/temp-file-part]]
        settings-fields))

(defn request-data
  "The route data the three file-taking endpoints share, which depends on what
   the service was started with.

   Declaring only what this service will actually accept means the description
   says so too: a caller reading the Swagger page is not offered a file picker
   by a service that would refuse the upload, nor a JSON body by one that would
   refuse the path.

   The `:openapi` clause is there because reitit describes a multipart request
   body by replacing the content map rather than adding to it. In `both` mode
   that would hide the JSON form entirely, so it is put back by hand."
  [input-mode]
  (cond-> {:parameters (cond-> {:body body-schema}
                         (#{:upload :both} input-mode)
                         (assoc :multipart multipart-schema))}
    (= :both input-mode)
    (assoc :openapi
           {:requestBody
            {:content {"application/json"
                       {:schema (json-schema/transform body-map-schema)}}}})))

;; ── Handlers ────────────────────────────────────────────────────────────────

(defn- bad-request [message] {:status 400 :body {:error message}})

(defn- with-input
  "Resolve the file, run `f` on it, and remove an upload's temporary folder
   afterwards. For a split, which outlives the request, see `start-split`."
  [config request f]
  (let [{:keys [file error work-dir]} (resolve-input config request)]
    (if error
      (bad-request error)
      (try (f file)
           (finally (some-> work-dir files/delete-tree!))))))

(defn- start-split
  "Begin a split and answer with the job.

   An upload has nowhere obvious to put its results — the caller named no folder
   and, in `upload` mode, is not allowed to. So the output goes into the same
   temporary folder as the upload and is fetched back as one archive, and the
   whole folder is removed when the job is forgotten. Ownership of that folder
   passes to the job here; nothing in this function deletes it."
  [{:keys [registry input-mode] :as config} request]
  (let [{:keys [file error work-dir]} (resolve-input config request)
        asked-out-dir (not-empty (str (:outDir (settings request))))]
    (cond
      error (bad-request error)

      (and asked-out-dir (not (#{:path :both} input-mode)))
      (do (some-> work-dir files/delete-tree!)
          (bad-request (str "outDir names a folder on this machine, and this "
                            "service was started with --api-input "
                            (name input-mode) ". Omit it and fetch the results "
                            "from /api/splits/{id}/archive.")))

      :else
      (let [opts (split-options file (settings request))
            ;; An upload with no folder named writes beside itself, inside the
            ;; temporary folder the job owns.
            opts (cond-> opts
                   (and work-dir (not asked-out-dir))
                   (assoc :out-dir (io/file work-dir "split")))]
        (if-let [problem (:problem (:plan opts))]
          (do (some-> work-dir files/delete-tree!)
              {:status 409 :body (problem-view problem)})
          {:status 202
           :body   (jobs/start! registry (assoc opts :work-dir work-dir))})))))

(defn- archive-response
  "Every file a finished job produced, as one zip.

   Written to the response as it is read, so a split of forty gigabytes does not
   have to exist twice."
  [registry id]
  (let [files (jobs/output-files registry id)]
    (cond
      (nil? files)  {:status 404 :body {:error "No such job."}}
      (empty? files) {:status 409 :body {:error (str "That job has produced no "
                                                     "files, or they have been "
                                                     "removed.")}}
      :else
      {:status  200
       :headers {"Content-Type"        "application/zip"
                 "Content-Disposition" (str "attachment; filename=\"" id ".zip\"")}
       :body    (zip/stream files)})))

(defn routes
  [{:keys [registry input-mode] :as config}]
  [["/api/openapi.json"
    {:get {:no-doc  true
           :openapi {:info
                     {:title       (str (branding/app-name) " API")
                      :version     (branding/version)
                      :description (str "Splitting CSV files, from a service on "
                                        "this machine and nowhere else.\n\n"
                                        "Every call needs `Authorization: Bearer "
                                        "<token>`. The token is printed when the "
                                        "service starts — press **Authorize** and "
                                        "paste it in.\n\n"
                                        "This service was started with "
                                        "`--api-input " (name input-mode) "`. Ask "
                                        "`/api/capabilities` what that allows.")}
                     :components
                     {:securitySchemes
                      {"bearer" {:type   "http"
                                 :scheme "bearer"
                                 :description "The token printed at startup."}}}}
           :handler (openapi/create-openapi-handler)}}]

   ["/api-docs/*"
    {:get {:no-doc  true
           :handler (swagger-ui/create-swagger-ui-handler
                     {:url    "/api/openapi.json"
                      :config {:persistAuthorization true}})}}]

   ["/api"
    {:tags    #{(branding/app-name)}
     :openapi {:security [{"bearer" []}]}}

    ["/health"
     {:get {:summary "Whether the service is up."
            :responses {200 {:body [:map [:status :string]]}}
            :handler (fn [_] {:status 200 :body {:status "ok"}})}}]

    ["/capabilities"
     {:get {:summary "What this service was started with."
            :handler
            (fn [_]
              {:status 200
               :body {:name          (branding/app-name)
                      :version       (branding/build-label)
                      :inputMode     (name input-mode)
                      :acceptsPath   (boolean (#{:path :both} input-mode))
                      :acceptsUpload (boolean (#{:upload :both} input-mode))
                      :maxUploadBytes max-upload-bytes
                      :excelRowLimit split/excel-row-limit
                      :runningJobs   (jobs/running-count registry)}})}}]

    ["/surveys"
     {:post (merge
             (request-data input-mode)
             {:summary     "Read a file once and describe it. Writes nothing."
              :description (str "What the window shows on the file card: how many "
                                "records, what separates the fields, which "
                                "encoding, whether the first row looks like column "
                                "names, and anything that looked damaged.")
              :handler
              (fn [request]
                (with-input config request
                  (fn [file] {:status 200 :body (survey-view (scan/survey file))})))})}]

    ["/plans"
     {:post (merge
             (request-data input-mode)
             {:summary     "What a split would do. Touches no disk."
              :description (str "Answer this before /api/splits if you want to "
                                "show someone what is about to happen. It reports "
                                "the same problems and warnings, including running "
                                "out of room.")
              :handler
              (fn [request]
                (with-input config request
                  (fn [file]
                    {:status 200
                     :body (plan-view (:plan (split-options file (settings request))))})))})}]

    ["/splits"
     {:post (merge
             (request-data input-mode)
             {:summary     (str "Start a split. Answers at once with a job id; ask "
                                "/api/splits/{id} how it is getting on.")
              :description (str "Splitting a file large enough to be worth "
                                "splitting takes minutes, which is longer than a "
                                "request should be held open. Nothing already on "
                                "disk is ever overwritten: a name that is already "
                                "taken stops the job rather than replacing "
                                "anything.")
              :handler     (fn [request] (start-split config request))})}]

    ["/splits/:id"
     {:get {:summary "How a split is getting on, and its result once finished."
            :parameters {:path [:map [:id :string]]}
            :handler (fn [{{{:keys [id]} :path} :parameters}]
                       (if-let [job (jobs/fetch registry id)]
                         {:status 200 :body job}
                         {:status 404 :body {:error "No such job."}}))}

      :delete {:summary     "Ask a running split to stop."
               :description (str "What is left on disk is always a whole number "
                                 "of complete files: the one being written is "
                                 "closed and removed.")
               :parameters  {:path [:map [:id :string]]}
               :handler (fn [{{{:keys [id]} :path} :parameters}]
                          (if-let [job (jobs/cancel! registry id)]
                            {:status 200 :body job}
                            {:status 404 :body {:error "No such job."}}))}}]

    ["/splits/:id/archive"
     {:get {:summary     "The files a finished split produced, as one zip."
            :description (str "The only way to get at the results of an uploaded "
                              "file, which are written where the caller cannot "
                              "see them. Available for a split of a local path "
                              "too, though the files are already in the folder "
                              "you named.")
            :parameters  {:path [:map [:id :string]]}
            :handler (fn [{{{:keys [id]} :path} :parameters}]
                       (archive-response registry id))}}]]])

;; ── Authorisation ───────────────────────────────────────────────────────────

(defn- constant-time=
  "Compares without leaking where the first difference is. Overkill against a
   local caller, and cheap enough not to argue about."
  [^String a ^String b]
  (if (or (nil? a) (nil? b) (not= (count a) (count b)))
    false
    (zero? (reduce bit-or 0 (map (fn [x y] (bit-xor (int x) (int y))) a b)))))

(def open-paths
  "The two things that may be read without the token: the description of the
   service, and the page that renders it. Neither discloses anything about the
   machine, and the browser fetches the first with no way to add a header.

   Everything else under /api/ is closed."
  #{"/api/openapi.json"})

(defn wrap-token
  "Every /api call must carry the token."
  [handler token]
  (fn [{:keys [uri headers] :as request}]
    (if (or (not (str/starts-with? (str uri) "/api/"))
            (contains? open-paths (str uri)))
      (handler request)
      (let [given (some-> (get headers "authorization")
                          (str/replace #"(?i)^bearer\s+" ""))]
        (if (constant-time= token given)
          (handler request)
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body "{\"error\":\"Missing or wrong token. Use the Authorization: Bearer header.\"}"})))))

(def formats
  "JSON and EDN, exactly as API.md offers, and nothing else. muuntaja's
   default instance also negotiates the two transit formats, one of which
   rides on org.msgpack/msgpack — a jar abandoned in 2015 whose CPE
   collects every other language's MessagePack CVEs as false positives.
   That jar is excluded in deps.edn, and this restriction is what makes
   the exclusion safe: no Accept header can steer a request into code
   that would want the missing class."
  (m/create (update m/default-options :formats
                    select-keys ["application/json" "application/edn"])))

(defn app
  [{:keys [token] :as config}]
  (-> (ring/ring-handler
       (ring/router
        (routes config)
        {:data {:coercion   reitit.coercion.malli/coercion
                :muuntaja   formats
                :middleware [openapi/openapi-feature
                             parameters/parameters-middleware
                             muuntaja/format-middleware
                             exception/exception-middleware
                             coercion/coerce-exceptions-middleware
                             coercion/coerce-request-middleware
                             coercion/coerce-response-middleware
                             multipart/multipart-middleware]}})
       (ring/routes
        (ring/create-default-handler
         {:not-found (constantly {:status 404
                                  :headers {"Content-Type" "application/json"}
                                  :body "{\"error\":\"No such endpoint.\"}"})})))
      (wrap-token token)))

(defn start!
  "Start the service. Returns a map describing it, including a :stop! function.

   Binding is to the loopback address and nothing else, which is not
   configurable on purpose."
  [{:keys [port input-mode token]}]
  (let [registry (jobs/new-registry)
        token    (or token (generate-token))
        port     (or port default-port)
        config   {:registry registry :input-mode (or input-mode :path) :token token}
        server   (http/run-server (app config)
                                  {:ip                   loopback
                                   :port                 port
                                   :max-body             max-upload-bytes
                                   :legacy-return-value? false})
        ;; Port 0 means "any free one", which only a test asks for. Reporting
        ;; the number that was actually bound is the only way anything can then
        ;; reach it.
        port     (http/server-port server)]
    {:port       port
     :token      token
     :input-mode (:input-mode config)
     :registry   registry
     :url        (str "http://" loopback ":" port)
     :stop!      (fn []
                   ;; Jobs first: a split still running holds a file open, and
                   ;; an uploaded file's temporary folder should not outlive the
                   ;; service that made it.
                   (jobs/stop-all! registry)
                   ;; Idempotent: http-kit answers nil for a server that has
                   ;; already stopped, and the shutdown hook will call this
                   ;; again after anything that stopped it deliberately.
                   (some-> (http/server-stop! server {:timeout 500}) deref))}))

(defn banner
  "What is printed at startup. It states the token and, in plain words, what
   holding it allows — a caller with `path` can read anything the user can."
  [{:keys [url token input-mode]}]
  (str/join
   "\n"
   (cond-> [""
            (str (branding/app-name) " service listening on " url)
            (str "  Documentation:  " url "/api-docs/index.html")
            (str "  Token:          " token)
            (str "  Input mode:     " (name input-mode))
            ""
            "  Every /api call needs:  Authorization: Bearer <token>"
            "  Reachable from this machine only."]
     ;; Whenever paths are allowed at all — which includes `both`. The first
     ;; version tested `= :path` exactly, so the mode that grants everything
     ;; path grants, plus uploads, came with no warning. Found by writing the
     ;; test from R64's reasoning rather than from this code.
     (#{:path :both} input-mode)
     (conj (str "\n  With --api-input " (name input-mode)
                ", anyone holding this token can ask the application to\n"
                "  read any file you can read. Treat it as a password. "
                "Use --api-input upload\n  or none to narrow that.")))))
