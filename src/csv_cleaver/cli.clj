(ns csv-cleaver.cli
  "The command line.

   Getopt-style: short and long forms, --option=value or --option value, and a
   --help that lists everything. The whole point of --locale is to be able to
   see the application in Japanese without changing the machine's language, so
   it is worth it working from the installed application too — jpackage passes
   arguments straight through, so `CSV Cleaver --locale ja` does the same thing
   as `clj -M:run --locale ja`.

   Deliberately in English. Choosing a language is the thing this parser does,
   so it has to say what it can do before it knows which language to say it in."
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [csv-cleaver.branding :as branding]
   [csv-cleaver.i18n :as i18n]))

(def options
  [["-l" "--locale TAG"
    (str "Interface language: " (str/join ", " i18n/supported)
         ". Defaults to the system language, or English.")
    ;; Only the shape is checked here. Whether we actually have that language
    ;; cannot be known yet: an extra translation supplied by the user has not
    ;; been loaded at the moment the command line is parsed, and refusing
    ;; --locale it before looking would defeat the point of allowing one. An
    ;; unknown code is reported at startup, not here.
    :validate [#(re-matches #"[A-Za-z]{2,3}([-_].+)?" (str %))
               "Use a language code such as en, de or it"]]

   ["-t" "--theme NAME"
    "Appearance: auto, light or dark. Auto follows the system and is the default."
    :parse-fn #(let [k (keyword (str/lower-case %))]
                 ;; "auto" is the word people reach for; "system" is what the
                 ;; code calls it. Both are accepted.
                 (if (= k :auto) :system k))
    :validate [#{:system :light :dark} "Choose one of: auto, light, dark"]]

   ["-L" "--languages DIR"
    (str "Folder of extra translation files. Defaults to a languages folder "
         "beside your settings. Each file is checked before use.")]

   ["-h" "--help" "Show this message and exit."]
   ["-V" "--version" "Show the version and exit."]])

(defn usage
  [summary]
  (str/join
   "\n"
   [(str (branding/app-name) " " (branding/version))
    ;; English, like the rest of this output: the language is what this parser
    ;; is being asked to choose.
    (or (branding/value :tagline)
        (i18n/tr (i18n/context i18n/fallback-tag) :about/tagline))
    ""
    (str "Usage: " (-> (branding/app-name) str/lower-case (str/replace " " "-"))
         " [options]")
    ""
    "Options:"
    summary
    ""
    "With no options the window opens in the system language."]))

(defn version-text []
  (str (branding/app-name) " " (branding/version)))

(defn parse
  "Work out what the user asked for. Returns one of:

     {:action :run  :options {...}}          start the window
     {:action :exit :status 0 :message \"…\"}  print and stop

   Never calls System/exit itself, so it can be tested."
  [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args csv-cleaver.cli/options)]
    (cond
      (:help options)    {:action :exit :status 0 :message (usage summary)}
      (:version options) {:action :exit :status 0 :message (version-text)}
      errors             {:action :exit :status 1
                          :message (str/join "\n" (concat errors ["" (usage summary)]))}
      :else              {:action :run :options options})))
