(ns csv-cleaver.api.jobs
  "A register of splits in progress.

   A split of a file large enough to need splitting takes minutes, which is
   longer than any HTTP client should be asked to hold a connection open. So
   POST starts a job and returns at once, and the caller asks after it.

   Everything here is deliberately in memory: a job describes work happening in
   this process, and there is nothing to recover if the process ends. Finished
   jobs are kept for a while so a caller that polls slowly still sees the
   outcome, then dropped so a long-lived service does not grow without bound."
  (:require
   [csv-cleaver.files :as files]
   [csv-cleaver.split :as split])
  (:import
   (java.io File)
   (java.util UUID)))

(def keep-finished-ms
  "How long a finished job stays readable. Long enough for a slow poller, short
   enough that a service left running for a month does not accumulate them."
  (* 30 60 1000))

(defn new-registry []
  (atom {}))

(defn- now [] (System/currentTimeMillis))

(defn- public-view
  "What a caller sees. Files are paths, not java.io.File; internals stay in.

   A job over an uploaded file wrote into a temporary folder the caller cannot
   see, so `archive` says where to fetch the results instead of listing paths
   that would mean nothing."
  [{:keys [id state started-at finished-at progress result error work-dir]}]
  (cond-> {:id         id
           :state      (name state)
           :startedAt  started-at}
    work-dir    (assoc :archive (str "/api/splits/" id "/archive"))
    finished-at (assoc :finishedAt finished-at)
    progress    (assoc :progress {:rowsDone   (:rows-done progress 0)
                                  :filesDone  (:files-done progress 0)
                                  :currentFile (:current-name progress)})
    error       (assoc :error error)
    result      (assoc :result
                       {:files       (mapv (fn [^File f] (.getAbsolutePath f))
                                           (:files result))
                        :rows        (:rows result)
                        :elapsedMs   (:elapsed-ms result)
                        :cancelled   (boolean (:cancelled? result))
                        :trashed     (mapv (fn [^File f] (.getAbsolutePath f))
                                           (:trashed result))
                        :leftBehind  (mapv (fn [^File f] (.getAbsolutePath f))
                                           (:left-behind result))})))

(defn- expired?
  [cutoff job]
  (and (:finished-at job) (< (long (:finished-at job)) (long cutoff))))

(defn expire!
  "Forget jobs that finished long enough ago, and remove the temporary folders
   they owned. Nothing a user chose is touched: a :work-dir only ever exists for
   a file that was uploaded to this service in the first place."
  [registry]
  (let [cutoff (- (now) keep-finished-ms)
        gone   (volatile! [])]
    (swap! registry
           (fn [jobs]
             (vreset! gone (keep (fn [[_ job]] (when (expired? cutoff job)
                                                 (:work-dir job)))
                                 jobs))
             (into {} (remove (fn [[_ job]] (expired? cutoff job)) jobs))))
    (run! files/delete-tree! @gone)))

(defn start!
  "Begin a split on a background thread and return the job's public view.

   `request` is the map csv-cleaver.split/execute! takes, plus an optional
   :work-dir — a temporary folder holding an uploaded file and its results,
   which this job now owns and which is removed when the job is forgotten.

   The work runs on a daemon thread, so a service told to stop does not wait
   for it."
  [registry request]
  (expire! registry)
  (let [id        (str (UUID/randomUUID))
        cancelled (atom false)
        job       {:id id :state :running :started-at (now) :cancelled cancelled
                   :work-dir (:work-dir request)}]
    (swap! registry assoc id job)
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               (let [result (split/execute!
                             (assoc request
                                    :cancelled?  (fn [] @cancelled)
                                    :on-progress (fn [p]
                                                   (swap! registry update id
                                                          assoc :progress p))))]
                 (swap! registry update id merge
                        {:state       (if (:cancelled? result) :cancelled :finished)
                         :finished-at (now)
                         :result      result}))
               (catch Throwable t
                 (swap! registry update id merge
                        {:state       :failed
                         :finished-at (now)
                         :error       (or (.getMessage t) (str (class t)))}))))
           (str "csv-cleaver-api-" id))
      (.setDaemon true)
      (.start))
    (public-view job)))

(defn fetch
  "A job's public view, or nil when there is no such job."
  [registry id]
  (when-let [job (get @registry id)]
    (public-view job)))

(defn cancel!
  "Ask a running job to stop. Returns its view, or nil when unknown."
  [registry id]
  (when-let [job (get @registry id)]
    (when (= :running (:state job))
      (reset! (:cancelled job) true))
    (public-view (get @registry id))))

(defn output-files
  "The files a job wrote, for the archive endpoint. `nil` when there is no such
   job, which is a different answer from a job that wrote nothing."
  [registry id]
  (when-let [job (get @registry id)]
    (vec (get-in job [:result :files]))))

(defn running-count
  [registry]
  (count (filter (comp #{:running} :state) (vals @registry))))

(defn stop-all!
  "Ask every running job to stop and forget the lot, removing any temporary
   folders. Called when the service is shut down."
  [registry]
  (doseq [job (vals @registry)]
    (when-let [flag (:cancelled job)] (reset! flag true)))
  (let [dirs (keep :work-dir (vals @registry))]
    (reset! registry {})
    (run! files/delete-tree! dirs)))
