(ns site
  "Builds the documentation site into target/site. Run with: bb site

   Static, hermetic, and built by Babashka like everything else here — no
   Node, no theme framework. The look is the application's own: AtlantaFX's
   Primer theme translated back to the web it came from (Primer is GitHub's
   design system), the lime of the icon as the brand colour, and the same
   light/dark behaviour — follow the system until the visitor says otherwise.

   Everything nameable comes from resources/branding.edn, same as the
   installers."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [markdown.core :as md]))

(def brand (read-string (slurp "resources/branding.edn")))
(def out-dir "target/site")
(def repo-url (:homepage brand))

(def pages
  "The documentation, in nav order. :group is the sidebar heading."
  [{:file "USER-GUIDE.md"    :slug "user-guide"    :title "User guide"     :group "Using it"}
   {:file "API.md"           :slug "api"           :title "REST API"       :group "Using it"}
   {:file "SECURITY.md"      :slug "security"      :title "Security"       :group "Using it"}
   {:file "SPECIFICATION.md" :slug "specification" :title "Specification"  :group "The project"}
   {:file "DECISIONS.md"     :slug "decisions"     :title "Decisions"      :group "The project"}
   {:file "DEVELOPING.md"    :slug "developing"    :title "Developing"     :group "The project"}
   {:file "ASSUMPTIONS.md"   :slug "assumptions"   :title "Assumptions"    :group "The project"}
   {:file "SIGNING.md"       :slug "signing"       :title "Signing"        :group "The project"}
   {:file "PROVENANCE.md"    :slug "provenance"    :title "Provenance"     :group "Trust"}
   {:file "VERIFICATION.md"  :slug "verification"  :title "Verification"   :group "Trust"}])

(def md->slug
  (into {} (map (juxt :file :slug)) pages))

(defn rewrite-links
  "Repo-relative Markdown links become site links; everything the site does
   not carry (LICENSE, source files) goes to the repository on GitHub."
  [html]
  (-> html
      (str/replace #"href=\"(?:\.\./)?(?:docs/)?([A-Z-]+\.md)(#[^\"]*)?\""
                   (fn [[whole file anchor]]
                     (if-let [slug (md->slug file)]
                       (str "href=\"" slug ".html" anchor "\"")
                       whole)))
      (str/replace #"href=\"(?:\.\./)?(LICENSE|NOTICE|THIRD-PARTY\.md|README\.md)\""
                   (str "href=\"" repo-url "/blob/main/$1\""))))

(defn esc [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn layout
  "One shell for every page. :body is trusted HTML; everything else is text."
  [{:keys [title body sidebar? depth]}]
  (let [p    (if (pos? (or depth 0)) "../" "")
        name (:name brand)]
    (str
     "<!doctype html>\n<html lang=\"en\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     ;; Google Search Console ownership proof. Verification fetches the root
     ;; page, but every page carrying it costs nothing and survives Google
     ;; later being pointed at a subpage.
     "<meta name=\"google-site-verification\" content=\"z3NocoQ5SZh74db3ndpryU17g6qvP4Uid00NBtkdIwY\">\n"
     "<title>" (esc (if title (str title " — " name) name)) "</title>\n"
     "<meta name=\"description\" content=\"" (esc (str name " — split large CSV files into smaller files that Excel can open. Free, open source, private by design.")) "\">\n"
     "<link rel=\"icon\" href=\"" p "assets/icon.png\">\n"
     "<link rel=\"stylesheet\" href=\"" p "style.css\">\n"
     "<script defer src=\"" p "site.js\"></script>\n"
     "</head>\n<body>\n"
     "<header class=\"top\">\n"
     "  <a class=\"wordmark\" href=\"" p "index.html\">"
     "<img src=\"" p "assets/icon.png\" alt=\"\" width=\"28\" height=\"28\">"
     "<strong>" (esc name) "</strong></a>\n"
     "  <nav>\n"
     "    <a href=\"" p "user-guide.html\">Docs</a>\n"
     "    <a href=\"" repo-url "/releases/latest\">Download</a>\n"
     "    <a href=\"" repo-url "\">GitHub</a>\n"
     "    <button id=\"theme\" type=\"button\" title=\"Appearance\">◐</button>\n"
     "  </nav>\n"
     "</header>\n"
     (if sidebar?
       (str "<div class=\"shell\">\n<aside class=\"side\">\n"
            (->> (partition-by :group pages)
                 (map (fn [grp]
                        (str "<h3>" (esc (:group (first grp))) "</h3>\n<ul>"
                             (->> grp
                                  (map #(str "<li><a href=\"" (:slug %) ".html\""
                                             (when (= title (:title %)) " class=\"here\"")
                                             ">" (esc (:title %)) "</a></li>"))
                                  (apply str))
                             "</ul>\n")))
                 (apply str))
            "</aside>\n<main class=\"doc\">\n" body "\n</main>\n</div>\n")
       (str "<main>\n" body "\n</main>\n"))
     "<footer>\n"
     "  <p>" (esc (:copyright brand)) " · Apache License 2.0 · "
     "<a href=\"mailto:" (:contact brand) "\">" (esc (:contact brand)) "</a></p>\n"
     "  <p>Made with 🤖 in Barcelona — see <a href=\"" p "provenance.html\">provenance</a>.</p>\n"
     "</footer>\n</body>\n</html>\n")))

(def landing-body
  (str
   "<section class=\"hero\">\n"
   "  <img class=\"hero-icon\" src=\"assets/icon.png\" alt=\"CSV Cleaver icon\" width=\"96\" height=\"96\">\n"
   "  <h1>CSV Cleaver</h1>\n"
   "  <p class=\"tag\">Split large CSV files into smaller files that Excel can open.</p>\n"
   "  <div class=\"dl\" id=\"downloads\">\n"
   "    <a class=\"btn primary\" id=\"dl-main\" href=\"" repo-url "/releases/latest\">Download</a>\n"
   "    <a class=\"btn\" href=\"" repo-url "/releases/latest\">All downloads</a>\n"
   "  </div>\n"
   "  <p class=\"dl-note\" id=\"dl-note\">macOS · Windows · Linux — every installer carries its own Java runtime.</p>\n"
   "</section>\n"
   "<section class=\"cleave card\">\n"
   "  <p><strong>cleave</strong> <span class=\"ipa\">/kliːv/</span> <em>verb</em></p>\n"
   "  <ol><li>to split or divide, especially along a natural line.</li>\n"
   "      <li>to cling or hold fast to.</li></ol>\n"
   "  <p class=\"muted\"><em>One word, two opposite meanings — a contranym. This application does the first to your file and the second to your rows.</em></p>\n"
   "</section>\n"
   "<section class=\"shot\">\n"
   "  <img class=\"only-light\" src=\"assets/app-light.png\" alt=\"CSV Cleaver ready to split a file, light theme\" width=\"720\" height=\"660\">\n"
   "  <img class=\"only-dark\" src=\"assets/app-dark.png\" alt=\"CSV Cleaver ready to split a file, dark theme\" width=\"720\" height=\"660\">\n"
   "</section>\n"
   "<section class=\"features\">\n"
   "  <div class=\"card\"><h3>Byte-faithful</h3><p>Concatenating the output reproduces the input, byte for byte. Tested on every build, and the tests themselves are mutation-checked.</p></div>\n"
   "  <div class=\"card\"><h3>Nothing overwritten</h3><p>No file on disk is ever replaced without consent — collisions are detected and asked about, never assumed away.</p></div>\n"
   "  <div class=\"card\"><h3>Private by design</h3><p>Your data never leaves your machine. No accounts, no telemetry, no network access to split a file.</p></div>\n"
   "  <div class=\"card\"><h3>Eight languages</h3><p>English, Spanish, French, German, Italian, Portuguese, Chinese and Japanese — numbers formatted correctly in each.</p></div>\n"
   "  <div class=\"card\"><h3>A REST API too</h3><p>Run it headless with <code>--api</code> and script it: async jobs, uploads, a Swagger page. <a href=\"api.html\">API docs</a>.</p></div>\n"
   "  <div class=\"card\"><h3>Honest about itself</h3><p>Written predominantly by an AI under human direction, and it says so — with a <a href=\"verification.html\">verification ledger</a> of what is proven and how.</p></div>\n"
   "</section>\n"
   "<section class=\"cleave card\" id=\"privacy\">\n"
   "  <p><strong>Privacy</strong></p>\n"
   "  <p class=\"muted\">This site is static files served by GitHub Pages: no cookies, no analytics, no tracking scripts. Your browser makes one request to GitHub's API, to show the latest version number on the download button; pages served from github.io appear in GitHub's ordinary server logs, covered by the <a href=\"https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement\">GitHub Privacy Statement</a>. The <code>google-site-verification</code> tag in the page header is an inert ownership proof for search indexing — it loads nothing and reports nothing about you. The application's own privacy promises are stronger still: see <a href=\"user-guide.html\">the user guide</a>.</p>\n"
   "</section>\n"))

(defn build! []
  (fs/delete-tree out-dir)
  (fs/create-dirs (str out-dir "/assets"))
  (doseq [f ["style.css" "site.js"]]
    (fs/copy (str "site/" f) (str out-dir "/" f)))
  (doseq [f (fs/list-dir "site/assets")]
    (fs/copy f (str out-dir "/assets/" (fs/file-name f))))
  (spit (str out-dir "/index.html")
        (layout {:title nil :body landing-body :sidebar? false :depth 0}))
  (doseq [{:keys [file slug title]} pages]
    (let [html (-> (slurp (str "docs/" file))
                   (md/md-to-html-string :heading-anchors true :footnotes? true)
                   rewrite-links)]
      (spit (str out-dir "/" slug ".html")
            (layout {:title title :body html :sidebar? true :depth 0}))))
  (println "==> Site built:" out-dir
           (str "(" (count (fs/glob out-dir "**.html")) " pages)")))

(build!)
