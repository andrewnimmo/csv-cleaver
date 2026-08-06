(ns csv-cleaver.updates
  "Finding out whether a newer release exists, and nothing else.

   The whole feature is one GET to GitHub's public releases endpoint, a
   version comparison, and a link the user can choose to open. Nothing is
   downloaded, nothing installs itself, and nothing identifies the user —
   the request carries only the application's own name and version as its
   User-Agent, which GitHub requires of every API caller.

   Network etiquette is decided here, once: a short timeout, and every
   failure — offline, rate-limited, unexpected JSON — collapses to
   {:status :error} for the caller to show quietly or not at all. An update
   check that nags about being offline would be worse than no check.

   The endpoint is derived from branding.edn's :homepage rather than written
   here, so a rebrand pointing somewhere that is not GitHub disables the
   feature instead of asking this repository about someone else's fork."
  (:require
   [clojure.string :as str]
   [csv-cleaver.branding :as branding]
   [jsonista.core :as json])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest
                  HttpResponse$BodyHandlers)
   (java.time Duration)))

(defn releases-endpoint
  "The API URL that answers \"what is the latest release?\", or nil when the
   homepage is not a GitHub repository and there is nothing sensible to ask."
  ([] (releases-endpoint (branding/value :homepage)))
  ([homepage]
   (when-let [[_ owner repo]
              (and homepage
                   (re-matches #"https://github\.com/([^/]+)/([^/]+?)/?"
                               (str homepage)))]
     (str "https://api.github.com/repos/" owner "/" repo "/releases/latest"))))

(defn version-parts
  "The numeric spine of a version: \"v2.10.1-beta\" -> [2 10 1]. Anything
   after the numbers is ignored — this application's releases are plain
   numbers, and being lenient here beats refusing to compare."
  [v]
  (->> (str/split (str/replace (str v) #"^[vV]" "") #"\.")
       (map #(re-find #"\d+" %))
       (take-while some?)
       (mapv #(Long/parseLong %))))

(defn newer?
  "Is `candidate` a later version than `current`? False on a tie, and false
   when either has no numbers at all — an unparseable tag must never produce
   an update banner."
  [current candidate]
  (let [a   (version-parts current)
        b   (version-parts candidate)
        n   (max (count a) (count b))
        pad (fn [v] (vec (concat v (repeat (- n (count v)) 0))))]
    (and (seq a) (seq b)
         ;; Padded to equal length so 2.1 vs 2.1.0 is a tie, not an update;
         ;; compare on same-length vectors is element-wise and numeric.
         (pos? (compare (pad b) (pad a))))))

(defn parse-latest
  "The two facts needed from GitHub's response: the version it calls the
   latest, and the page a person would open. Nil when the shape surprises."
  [body]
  (try
    (let [m (json/read-value body json/keyword-keys-object-mapper)
          tag (:tag_name m)
          url (:html_url m)]
      (when (and (string? tag) (string? url))
        {:version (str/replace tag #"^[vV]" "") :url url}))
    (catch Exception _ nil)))

(defn fetch-latest!
  "GET the endpoint and return the body string, or throw. Three seconds to
   connect, five for the whole exchange: a version check is a courtesy, not
   something a person should ever wait on."
  [^String url]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 3))
                   (.followRedirects HttpClient$Redirect/NORMAL)
                   (.build))
        request (-> (HttpRequest/newBuilder (URI. url))
                    (.timeout (Duration/ofSeconds 5))
                    (.header "Accept" "application/vnd.github+json")
                    (.header "User-Agent" (str (branding/app-name) "/"
                                               (branding/value :version)))
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (if (= 200 (.statusCode response))
      (.body response)
      (throw (ex-info "unexpected status" {:status (.statusCode response)})))))

(defn check!
  "The whole check, with the network injectable so tests can be offline.
   Returns exactly one of:

     {:status :update-available :version \"2.1.0\" :url \"https://…\"}
     {:status :up-to-date}
     {:status :error}"
  ([] (check! (branding/value :version) fetch-latest!))
  ([current fetch]
   (try
     (if-let [url (releases-endpoint)]
       (if-let [{:keys [version] :as latest} (parse-latest (fetch url))]
         (if (newer? current version)
           (assoc latest :status :update-available)
           {:status :up-to-date})
         {:status :error})
       {:status :error})
     (catch Exception _ {:status :error}))))
