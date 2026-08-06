// Two small behaviours, both optional to the page working at all.
//
// Appearance: follow the system until the visitor chooses, then remember —
// the same contract as the application's Appearance control. The toggle
// cycles system → light → dark.
//
// Downloads: ask the GitHub API for the latest release and point the main
// button at this machine's likely installer. Static fallback: the releases
// page, which is where the button already points in the HTML.

(function () {
  var order = ["system", "light", "dark"];
  var labels = { system: "◐", light: "○", dark: "●" };

  function apply(mode) {
    if (mode === "system") {
      document.documentElement.removeAttribute("data-theme");
    } else {
      document.documentElement.setAttribute("data-theme", mode);
    }
    var btn = document.getElementById("theme");
    if (btn) {
      btn.textContent = labels[mode];
      btn.title = "Appearance: " + mode;
    }
  }

  var saved = null;
  try { saved = localStorage.getItem("theme"); } catch (e) {}
  if (order.indexOf(saved) < 0) saved = "system";
  apply(saved);

  document.addEventListener("DOMContentLoaded", function () {
    apply(saved);
    var btn = document.getElementById("theme");
    if (btn) {
      btn.addEventListener("click", function () {
        saved = order[(order.indexOf(saved) + 1) % order.length];
        try { localStorage.setItem("theme", saved); } catch (e) {}
        apply(saved);
      });
    }

    var main = document.getElementById("dl-main");
    var note = document.getElementById("dl-note");
    if (!main) return;
    fetch("https://api.github.com/repos/andrewnimmo/csv-cleaver/releases/latest")
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (rel) {
        if (!rel || !rel.assets) return;
        var ua = navigator.userAgent;
        var pick = null, label = null;
        function asset(suffix) {
          for (var i = 0; i < rel.assets.length; i++) {
            if (rel.assets[i].name.indexOf(suffix) >= 0) return rel.assets[i];
          }
          return null;
        }
        if (/Macintosh/.test(ua)) {
          // Browsers do not reveal Apple Silicon vs Intel; offer Apple
          // Silicon (the common case for a current Mac) and say which.
          pick = asset("apple-silicon.dmg");
          label = "Download for macOS (Apple Silicon)";
        } else if (/Windows/.test(ua)) {
          pick = asset(".msi");
          label = "Download for Windows";
        } else if (/Linux/.test(ua)) {
          pick = asset(".AppImage");
          label = "Download for Linux (AppImage)";
        }
        if (pick) {
          main.href = pick.browser_download_url;
          main.textContent = label;
        }
        if (note && rel.tag_name) {
          note.textContent = rel.tag_name.replace(/^v/, "") +
            " · macOS · Windows · Linux — every installer carries its own Java runtime.";
        }
      })
      .catch(function () {});
  });
})();
