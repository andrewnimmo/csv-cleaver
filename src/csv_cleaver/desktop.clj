(ns csv-cleaver.desktop
  "The few things that differ between macOS, Windows and Linux."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.awt Desktop Desktop$Action)
   (java.io File)))

(defn os
  "Which family of operating system this is: :mac, :windows or :linux."
  ([] (os (System/getProperty "os.name")))
  ([^String os-name]
   (let [n (str/lower-case (str os-name))]
     (cond
       (str/includes? n "mac")  :mac
       (str/includes? n "win")  :windows
       :else                    :linux))))

(defn reveal-label-key
  "Which phrase names the button that opens the output folder. Each platform
   has its own word for it — Finder, Explorer — and each language has its own
   word for that, so this returns a translation key rather than text."
  ([] (reveal-label-key (os)))
  ([os-key]
   (case os-key
     :mac     :action/reveal-mac
     :windows :action/reveal-windows
     :action/reveal-other)))

(defn reveal-command
  "The command that opens `dir` in the platform's file manager, or nil when the
   platform has no known one and java.awt.Desktop should be tried instead."
  [os-key ^File dir]
  (let [path (.getAbsolutePath dir)]
    (case os-key
      :mac     ["open" path]
      :windows ["explorer" path]
      :linux   ["xdg-open" path]
      nil)))

(defn launch!
  "Start `command`, or hand `dir` to java.awt.Desktop when there is no command
   for this platform. Separated out so tests can substitute it."
  [command ^File dir]
  (if command
    (.start (ProcessBuilder. ^java.util.List (vec command)))
    (when (Desktop/isDesktopSupported)
      (.open (Desktop/getDesktop) dir)))
  true)

(defn reveal!
  "Open `dir` in the file manager. Gives up quietly rather than throwing:
   failing to open a folder is not worth interrupting someone over, and the
   completion panel already says where the files went."
  ([^File dir] (reveal! dir (os) launch!))
  ([^File dir os-key launch]
   (when (and dir (.isDirectory dir))
     (try
       (launch (reveal-command os-key dir) dir)
       true
       (catch Exception _
         false)))))

(defn mail-uri
  "A mailto: URI for `address`, with `subject` already filled in.

   Built with the multi-argument URI constructor, which percent-encodes what
   the scheme cannot carry — the spaces and parentheses in a subject like
   \"CSV Cleaver 2.0.0 (a1b2c3d)\"."
  ^java.net.URI [address subject]
  (java.net.URI. "mailto" (str address "?subject=" subject) nil))

(defn compose-mail!
  "Open the user's mail client addressed to `uri`. Same shape as reveal!: the
   doing is injectable, so tests watch what would be sent without a mail
   client opening on whoever runs the suite.

   Desktop MAIL is missing on many Linux desktops; browsing the mailto: URI
   hands it to whatever the system associates with mail, which is the next
   best answer. Gives up quietly — a contact line that cannot open a composer
   is an inconvenience, not an error worth a dialog."
  ([^java.net.URI uri] (compose-mail! uri
                                      (fn [^java.net.URI u]
                                        (let [d (Desktop/getDesktop)]
                                          (if (.isSupported d Desktop$Action/MAIL)
                                            (.mail d u)
                                            (.browse d u))))))
  ([^java.net.URI uri send!]
   (boolean
    (try
      (when (and uri (Desktop/isDesktopSupported))
        (send! uri)
        true)
      (catch Throwable _ false)))))

(defn prefs-file
  "Where remembered settings live, following each platform's convention."
  (^File [] (prefs-file (os) (System/getProperty "user.home")))
  (^File [os-key ^String home]
   (case os-key
     :mac     (io/file home "Library" "Application Support" "CSV Cleaver" "settings.edn")
     :windows (io/file (or (System/getenv "APPDATA") home) "CSV Cleaver" "settings.edn")
     (io/file (or (System/getenv "XDG_CONFIG_HOME") (str home "/.config"))
              "csv-cleaver" "settings.edn"))))

(defn languages-dir
  "Where a user may drop further translations, beside their settings. Adding
   Italian means putting it.edn here and restarting — no rebuild required.
   Everything found there is validated before use; see csv-cleaver.i18n."
  (^File [] (languages-dir (os) (System/getProperty "user.home")))
  (^File [os-key ^String home]
   (io/file (.getParentFile (prefs-file os-key home)) "languages")))

(defn trash-supported?
  "Whether this platform can move a file to the Trash or Recycle Bin.

   macOS and Windows can; several Linux desktops cannot. Where it is
   unsupported nothing is removed at all — falling back to deletion would
   quietly turn a recoverable action into an irreversible one, which is the
   opposite of the point."
  []
  (try
    (and (Desktop/isDesktopSupported)
         (.isSupported (Desktop/getDesktop) Desktop$Action/MOVE_TO_TRASH))
    (catch Throwable _ false)))

(defn move-to-trash!
  "Move `file` to the Trash. Returns true when it went. Never deletes: a file
   this application merely recognised by name is not one it should destroy."
  [^File file]
  (boolean
   (try
     (and file
          (.exists file)
          (trash-supported?)
          (.moveToTrash (Desktop/getDesktop) file))
     (catch Throwable _ false))))
