(ns csv-cleaver.branding
  "Reads resources/branding.edn, the one place a name, colour or icon has to be
   changed to rebrand this application.

   The same file feeds the window title, the About box and the packaging
   scripts, so there is no second place for them to disagree."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util Base64)))

(def defaults
  "Used when branding.edn is missing or unreadable. A wrong-looking window is
   better than one that will not open."
  {:name      "CSV Cleaver"
   :tagline   ""
   :version   "0.0.0"
   :bundle-id "dev.nimmo.csvcleaver"
   :vendor    ""
   :copyright ""
   :homepage  ""
   :accent    nil})

(defn read-config
  ([] (read-config (io/resource "branding.edn")))
  ([source]
   (merge defaults
          (try
            (when source
              (let [parsed (edn/read-string (slurp source))]
                (when (map? parsed) parsed)))
            (catch Exception _ nil)))))

(def config
  "Loaded once. Rebranding means editing the file and rebuilding, which was the
   agreed trade for keeping this a plain data lookup."
  (delay (read-config)))

(defn value [k] (get @config k))

(defn app-name [] (value :name))

(defn version [] (value :version))

(def build-info
  "Which commit this was built from, written by build.clj. Absent when running
   from source, which is itself worth saying."
  (delay
    (try
      (when-let [url (io/resource "build-info.edn")]
        (let [parsed (edn/read-string (slurp url))]
          (when (map? parsed) parsed)))
      (catch Exception _ nil))))

(defn build-label
  "The version as it should be shown, with the commit where there is one:
   \"2.0.0 (a1d0232)\", or \"2.0.0 (from source)\".

   Without this there is no way to tell a rebuilt installer from a stale one,
   which turns every question about a fix into guesswork."
  []
  (let [{:keys [commit]} @build-info]
    (str (version) " (" (or commit "from source") ")")))

(defn accent-css
  "A stylesheet overriding the theme's accent colour, as a data URI, or nil
   when no accent has been set.

   A data URI rather than a written-out file because a packaged application's
   resources are inside a jar and cannot be edited at run time, and because it
   leaves nothing behind on disk."
  ([] (accent-css (value :accent)))
  ([accent]
   (when-not (str/blank? (str accent))
     (let [css (str ".root {"
                    " -color-accent-fg: " accent ";"
                    " -color-accent-emphasis: " accent ";"
                    " -color-accent-muted: " accent ";"
                    " }")]
       (str "data:text/css;base64,"
            (.encodeToString (Base64/getEncoder)
                             (.getBytes ^String css StandardCharsets/UTF_8)))))))

(defn stylesheets
  "Every stylesheet the window should load, in the order they apply: the
   application's own, then an optional resources/brand.css, then the accent
   override from branding.edn. Later ones win."
  []
  (->> [(some-> (io/resource "csv_cleaver/app.css") .toExternalForm)
        (some-> (io/resource "brand.css") .toExternalForm)
        (accent-css)]
       (remove nil?)
       vec))
