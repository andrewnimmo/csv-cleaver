(ns csv-cleaver.prefs
  "Remembering the handful of settings worth remembering between sessions.

   Failure here is never worth telling anyone about: an unreadable or missing
   settings file just means the defaults apply, which is exactly what happened
   the first time the application was ever run."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [csv-cleaver.desktop :as desktop])
  (:import
   (java.io File)))

(def remembered
  "Settings restored at startup. Deliberately excludes anything about a
   particular file — the window always opens ready for a new one."
  [:theme :language :template :mode :rows-text :size-text :advanced-open?
   :excel-safe? :output-base :window])

(defn load-prefs
  "Read remembered settings, or an empty map when there are none."
  ([] (load-prefs (desktop/prefs-file)))
  ([^File file]
   (try
     (if (.isFile file)
       (let [saved (edn/read-string (slurp file))]
         (if (map? saved)
           (cond-> (select-keys saved remembered)
             (string? (:output-base saved)) (assoc :output-base (io/file (:output-base saved))))
           {}))
       {})
     (catch Exception _ {}))))

(defn save-prefs!
  "Merge `settings` into what is already stored. Returns true when written."
  ([settings] (save-prefs! (desktop/prefs-file) settings))
  ([^File file settings]
   (try
     (io/make-parents file)
     (let [existing (try (edn/read-string (slurp file)) (catch Exception _ nil))
           existing (if (map? existing) existing {})
           merged   (-> (merge existing settings)
                        (select-keys remembered)
                        (update :output-base #(when % (str %))))]
       (spit file (pr-str (into {} (remove (comp nil? val) merged))))
       true)
     (catch Exception _ false))))
