(ns build
  "tools.build script for CSV Cleaver.

   Targets:
     clj -T:build clean   – remove target/
     clj -T:build uber    – build target/csv-cleaver-<version>.jar

   The uberjar is what jpackage consumes as its --input; see
   .github/workflows/release.yml and package/build-mac.sh."
  (:require
   [clojure.tools.build.api :as b]))

(def lib 'dev.nimmo/csv-cleaver)
(def version "2.0.0")
(def main-ns 'csv-cleaver.app)

(def class-dir "target/classes")
(def uber-file (format "target/csv-cleaver-%s.jar" version))

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn- git-commit
  "The short commit this is being built from, or nil outside a checkout."
  []
  (try
    (let [{:keys [exit out]} (b/process {:command-args ["git" "rev-parse" "--short" "HEAD"]
                                         :out :capture})]
      (when (zero? exit) (clojure.string/trim out)))
    (catch Exception _ nil)))

(defn- stamp-build!
  "Record which commit this build came from, so that a running application can
   say. Without it there is no way to tell a rebuilt installer from a stale one,
   which turns any question about a fix into guesswork."
  []
  (let [file (java.io.File. ^String class-dir "build-info.edn")]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str {:commit   (or (git-commit) "unknown")
                        :built-at (str (java.time.Instant/now))
                        :version  version}))))

(defn clean
  "Delete the target directory."
  [_]
  (b/delete {:path "target"})
  (println "Cleaned target/"))

(defn uber
  "Build a standalone uberjar with csv-cleaver.app as its entry point.

   Only the entry-point namespace is AOT compiled. Compiling everything would
   drag the JavaFX classes into the AOT step, which boots the toolkit at build
   time and leaves a hung JVM behind on headless CI runners."
  [_]
  (clean nil)
  (let [basis (basis)]
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis      basis
                    :src-dirs   ["src"]
                    :ns-compile [main-ns]
                    :class-dir  class-dir})
    (stamp-build!)
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns}))
  (println "Built" uber-file))
