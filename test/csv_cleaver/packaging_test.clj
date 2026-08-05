(ns csv-cleaver.packaging-test
  "The packaging scripts, checked as source.

   These cannot be executed here — jpackage takes minutes and produces a
   platform installer — so this is a source-level check and should be understood
   as one. The proof that an installer is correct is in the build itself, which
   looks inside the image it has just made and launches the binary before
   wrapping it up. What is checked here is the argument that has to be right for
   that to succeed, so a mistake is caught by a test run rather than by a
   fifteen-minute release build.

   The first version of this namespace searched the file for the string
   \"jdk.localedata\" and passed happily with the module removed from the build,
   because the name also appears in the script's own error message. It was found
   by deliberately breaking the script and discovering that nothing failed.
   Hence the parsing below: what matters is the value of --add-modules, not
   whether a word occurs somewhere in the file."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def scripts
  ["package/build-mac.sh" "package/build-linux.sh" "package/build-windows.bat"])

(defn- source [path]
  (let [f (io/file path)]
    (when (.isFile f) (slurp f))))

(defn- without-comments
  "The script with its prose removed.

   Necessary, and the reason is instructive: the first parser found
   \"--add-modules replaces jpackage's own detection\" in a comment and came
   back with the module list #{\"replaces\"}, on which every assertion below is
   nonsense. A test that reads the wrong thing does not usually announce it."
  [text]
  (->> (str/split-lines text)
       (remove #(re-matches #"\s*(#|REM\b|rem\b).*" %))
       (str/join "\n")))

(defn requested-modules
  "The modules a script asks jpackage for, as a set.

   Handles both spellings in use: a MODULES=\"…\" shell variable passed as
   --add-modules \"$MODULES\", and the list written at the argument itself."
  [text]
  (let [text    (without-comments text)
        inline  (second (re-find #"--add-modules\s+([A-Za-z0-9_.,]+)" text))
        via-var (second (re-find #"MODULES=\"([^\"]+)\"" text))]
    (->> [inline via-var]
         (remove nil?)
         (mapcat #(str/split % #","))
         (remove str/blank?)
         (set))))

(deftest the-module-list-is-found-and-looks-like-modules
  (testing "a check that reads the wrong thing passes everything below.

            Not hypothetical: the parser here once returned #{\"replaces\"},
            having matched prose in a comment, and the tests below went on
            asserting things about it perfectly happily. Hence the shape check
            as well as the emptiness one — every entry must be a dotted module
            name."
    (doseq [path scripts]
      (when-let [text (source path)]
        (let [modules (requested-modules text)]
          (is (seq modules)
              (str path " — no --add-modules could be parsed"))
          (is (every? #(re-matches #"[a-z]+(\.[a-z0-9]+)+" %) modules)
              (str path " — parsed something that is not a module name: "
                   (pr-str (remove #(re-matches #"[a-z]+(\.[a-z0-9]+)+" %)
                                   modules)))))))))

(deftest every-platform-bundles-locale-data
  (testing "jlink includes only modules something declares a dependency on, and
            nothing declares one on locale data. Without jdk.localedata the
            bundled runtime has data for a single locale, so java.text formats
            every language as English — which is what shipped: a Spanish window
            reading \"470,128 filas de datos\".

            Invisible from inside the application: every other test in this
            suite runs on a full JDK, where the module is always present."
    (doseq [path scripts]
      (when-let [text (source path)]
        (is (contains? (requested-modules text) "jdk.localedata")
            (str path " must ask jpackage for jdk.localedata"))))))

(deftest no-platform-asks-for-ALL-DEFAULT
  (testing "it reads like \"everything jpackage would have chosen anyway\" and is
            not: it resolves to java.base alone and the application will not
            start. Tried, and caught only because the build now launches what it
            has built."
    (doseq [path scripts]
      (when-let [text (source path)]
        (is (not (contains? (requested-modules text) "ALL-DEFAULT")) path)))))

(deftest the-module-list-covers-what-the-application-uses
  (testing "--add-modules replaces jpackage's own detection rather than adding
            to it, so anything left out here is simply absent from the runtime."
    (doseq [path scripts]
      (when-let [text (source path)]
        (let [modules (requested-modules text)]
          (is (or (contains? modules "java.se") (contains? modules "java.desktop"))
              (str path " — without java.desktop there is no window, and no
                   java.awt.Desktop to move a file to the Trash"))
          (is (contains? modules "jdk.unsupported")
              (str path " — Clojure needs sun.misc.Unsafe")))))))

(deftest every-platform-launches-the-right-main-class
  (testing "the entry point moved once and all three of these were left behind,
            producing an installer that failed at startup"
    (doseq [path scripts]
      (when-let [text (source path)]
        (is (str/includes? text "--main-class csv_cleaver.main") path)
        (is (not (str/includes? text "--main-class csv_cleaver.app"))
            (str path " names a class that is no longer the entry point"))))))

(deftest every-platform-has-its-icon-and-each-is-what-it-claims-to-be
  (testing "the paths come from branding.edn, which is what the build scripts
            read, so a renamed file fails here rather than producing an
            installer with the default coffee-cup icon. Magic bytes rather than
            existence: a zero-byte or misformatted file exists happily."
    (let [icons (:icons (read-string (slurp "resources/branding.edn")))
          magic (fn [path n]
                  (let [f (io/file path)]
                    (when (.isFile f)
                      (with-open [in (io/input-stream f)]
                        (let [buf (byte-array n)]
                          (.read in buf)
                          (mapv #(bit-and % 0xff) buf))))))]
      (is (= [0x69 0x63 0x6e 0x73] (magic (:macos icons) 4))
          "icns magic — the literal bytes of \"icns\"")
      (is (= [0 0 1 0] (magic (:windows icons) 4))
          "ico header: reserved 0, type 1")
      (is (= [0x89 0x50 0x4e 0x47] (magic (:linux icons) 4))
          "png signature"))))

(deftest the-licence-actually-reaches-the-user
  (testing "NOTICE promises that THIRD-PARTY.md is installed alongside the
            application, and for months nothing was: only the jar was staged.
            A warranty disclaimer the user never receives is worth little, so
            every platform stages the three legal files and hands LICENSE to
            jpackage, which presents it at install time."
    (doseq [path scripts]
      (when-let [text (source path)]
        (is (str/includes? text "--license-file LICENSE")
            (str path " must present the licence at install time"))
        (doseq [f ["LICENSE" "NOTICE" "THIRD-PARTY.md"]]
          (is (str/includes? text f)
              (str path " must stage " f " into the application")))))))
