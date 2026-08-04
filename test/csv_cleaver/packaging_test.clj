(ns csv-cleaver.packaging-test
  "The packaging scripts, checked as source.

   These cannot be executed here — jpackage takes minutes and produces a
   platform installer — but the thing that went wrong with them was a missing
   argument, which is perfectly visible in the text."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def scripts
  ["package/build-mac.sh" "package/build-linux.sh" "package/build-windows.bat"])

(defn- source [path]
  (let [f (io/file path)]
    (when (.isFile f) (slurp f))))

(deftest every-platform-bundles-locale-data
  (testing "jlink includes only what something declares a dependency on, and
            nothing declares one on locale data. Without jdk.localedata the
            bundled runtime has data for a single locale, so java.text formats
            every language as English — which is what shipped: a Spanish window
            reading \"470,128 filas de datos\".

            Checked here because it is invisible from inside the application:
            every test in this suite runs on a full JDK, where the module is
            always present."
    (doseq [path scripts]
      (when-let [text (source path)]
        (is (str/includes? text "jdk.localedata")
            (str path " must ask jpackage for jdk.localedata"))))))

(deftest every-platform-launches-the-right-main-class
  (testing "the entry point moved once and two of these were left behind,
            producing an installer that failed at startup"
    (doseq [path scripts]
      (when-let [text (source path)]
        (is (str/includes? text "csv_cleaver.main") path)
        (is (not (str/includes? text "csv_cleaver.app"))
            (str path " names a class that is no longer the entry point"))))))
