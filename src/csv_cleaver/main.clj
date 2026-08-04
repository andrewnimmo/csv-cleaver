(ns csv-cleaver.main
  "Where the process starts, and the only namespace here that can be loaded on a
   machine with no display.

   Everything to do with the window lives in csv-cleaver.app, which is required
   at the moment a window is going to be opened and not before. That is not
   tidiness. Loading cljfx starts the JavaFX toolkit as a side effect of loading
   it, so a namespace that mentions cljfx in its :require cannot run --headless
   on a server: it would fail while loading, before anything had the chance to
   explain why."
  (:require
   [csv-cleaver.api.server :as api]
   [csv-cleaver.cli :as cli]
   [csv-cleaver.desktop :as desktop]
   [csv-cleaver.i18n :as i18n])
  (:gen-class))

(def exit!
  "Ending the process, behind a var so that a test can watch the intent without
   the test run being ended along with it."
  (fn [status] (System/exit (int status))))

;; ── The optional local service ──────────────────────────────────────────────

(def minimum-token-length
  "Below this, a token given with --api-token is worth saying something about.
   Not refused: it is the user's machine and their decision, but they should be
   making it knowingly."
  16)

(defn weak-token-warning
  "What to say about a token the user chose, or nil when there is nothing to
   say. Separated from the printing so it can be tested."
  [token]
  (when (and token (< (count token) minimum-token-length))
    (str "Warning: that --api-token is " (count token)
         " characters. Anything holding it can drive this application. Omit "
         "--api-token to have a strong one generated.")))

(defn start-api!
  "Start the local service, say how to reach it, and arrange for it to be shut
   down when the process ends.

   A failure here ends the process. The user asked for a service; carrying on as
   though nothing had happened would be a lie they might not discover until
   something depending on it quietly failed."
  [options]
  (when-let [warning (weak-token-warning (:api-token options))]
    (binding [*out* *err*] (println warning)))
  (try
    (let [service (api/start! {:port       (:api-port options)
                               :input-mode (:api-input options)
                               :token      (:api-token options)})]
      (println (api/banner service))
      (flush)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn [] ((:stop! service)))
                                 "csv-cleaver-api-shutdown"))
      service)
    (catch java.net.BindException _
      (binding [*out* *err*]
        (println (str "Port " (:api-port options) " is already in use. "
                      "Choose another with --api-port.")))
      (exit! 1))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Could not start the service: " (.getMessage e))))
      (exit! 1))))

(defn park!
  "Headless there is nothing to do but stay running until the process is
   stopped. The service's own threads are daemons, so something has to hold the
   process open, and this is the honest way to say so."
  []
  @(promise))

;; ── Entry point ─────────────────────────────────────────────────────────────

(defn -main
  [& args]
  (let [{:keys [action status message options]} (cli/parse args)]
    (if (= action :exit)
      (do (println message)
          (exit! status))
      (let [dir      (or (:languages options) (desktop/languages-dir))
            {:keys [problems]} (i18n/load-external! dir)
            headless (boolean (:headless options))]

        ;; Headless there is no window to explain a rejected translation in, and
        ;; the service answers in English whatever the window would have done.
        ;; Say what was ignored and carry on.
        (when (and headless (seq problems))
          (binding [*out* *err*]
            (println "Ignored one or more translations:")
            (doseq [p problems] (println " -" p)))
          (i18n/forget-external!))

        (when (:api options) (start-api! options))

        (cond
          headless          (park!)
          (seq problems)    ((requiring-resolve 'csv-cleaver.app/show-language-problems!)
                             problems options)
          :else             ((requiring-resolve 'csv-cleaver.app/start-window!)
                             options))))))
