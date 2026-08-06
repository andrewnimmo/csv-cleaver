#!/usr/bin/env bash
# Builds a .deb and an AppImage on Linux.
#
# The .deb suits Debian and Ubuntu; the AppImage runs on anything, which matters
# because there is no way to test every distribution.

set -euo pipefail
cd "$(dirname "$0")/.."

read_brand() {
  bb -e "(:$1 (read-string (slurp \"resources/branding.edn\")))" | tr -d '"'
}

APP_NAME=$(read_brand name)
APP_VERSION=$(read_brand version)
VENDOR=$(read_brand vendor)
COPYRIGHT=$(read_brand copyright)
ICON=$(bb -e '(get-in (read-string (slurp "resources/branding.edn")) [:icons :linux])' | tr -d '"')

DIST_DIR="dist"
STAGE_DIR="target/jpackage-input"
APP_DIR="target/appdir"

echo "==> Building uberjar"
clojure -T:build uber

echo "==> Staging"
rm -rf "$DIST_DIR" "$STAGE_DIR" "$APP_DIR"
mkdir -p "$DIST_DIR" "$STAGE_DIR"
cp target/csv-cleaver-*.jar "$STAGE_DIR/"
cp LICENSE NOTICE THIRD-PARTY.md "$STAGE_DIR/"
MAIN_JAR=$(basename "$STAGE_DIR"/*.jar)

ICON_OPT=()
if [ -f "$ICON" ]; then
  ICON_OPT=(--icon "$ICON")
fi

# jlink includes only the modules something declares a dependency on, and
# nothing declares one on locale data. Without jdk.localedata the bundled
# runtime carries data for a single locale and java.text formats every language
# as English — Spanish text with 470,128 in the middle of it, which is what
# shipped. --add-modules replaces jpackage's own detection rather than adding to
# it, so the set is named in full; java.se is the aggregator for the standard
# edition and is stable across JDK releases.
MODULES="java.se,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.management"

COMMON=(
  --name "$APP_NAME"
  --app-version "$APP_VERSION"
  --vendor "$VENDOR"
  --copyright "$COPYRIGHT"
  --input "$STAGE_DIR"
  --main-jar "$MAIN_JAR"
  --add-modules "$MODULES"
  --main-class csv_cleaver.main
  --java-options "--enable-native-access=ALL-UNNAMED"
  --java-options "-Dfile.encoding=UTF-8"
)

# The ${x[@]+...} form expands to nothing when the array is empty, rather than
# failing under `set -u`.
echo "==> jpackage (.deb)"
# --license-file only here: jpackage refuses it for the app-image build that
# feeds the AppImage below.
jpackage --type deb --dest "$DIST_DIR" \
  --license-file LICENSE \
  --linux-shortcut \
  --linux-menu-group Utility \
  "${COMMON[@]}" ${ICON_OPT[@]+"${ICON_OPT[@]}"}

echo "==> jpackage (app image, for the AppImage)"
jpackage --type app-image --dest "$APP_DIR" \
  "${COMMON[@]}" ${ICON_OPT[@]+"${ICON_OPT[@]}"}

RUNTIME_RELEASE="$APP_DIR/$APP_NAME/lib/runtime/release"
if [ -f "$RUNTIME_RELEASE" ] && ! grep -q "jdk.localedata" "$RUNTIME_RELEASE"; then
  echo "!!! jdk.localedata is missing from the bundled runtime." >&2
  echo "    Every language would show English number formatting." >&2
  exit 1
fi

if command -v appimagetool >/dev/null 2>&1; then
  echo "==> AppImage"
  APPDIR="$APP_DIR/$APP_NAME"
  SLUG="${APP_NAME// /-}"
  cat > "$APPDIR/AppRun" <<EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/bin/$APP_NAME" "\$@"
EOF
  chmod +x "$APPDIR/AppRun"
  # appimagetool refuses an AppDir without a top-level .desktop file and the
  # icon that .desktop names — jpackage's app image provides neither at the
  # root. This branch shipped without them and could never have produced an
  # AppImage; nothing noticed because for months nothing installed
  # appimagetool either. The icon comes from branding.edn's own file rather
  # than from jpackage's internal layout, which is not a documented contract.
  cat > "$APPDIR/$SLUG.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$APP_NAME
Exec=AppRun
Icon=$SLUG
Categories=Utility;
Terminal=false
EOF
  cp "$ICON" "$APPDIR/$SLUG.png"
  # ARCH stated rather than guessed: appimagetool otherwise infers it by
  # scanning the AppDir for ELF binaries, and the filename below already
  # promises x86_64 either way.
  ARCH=x86_64 appimagetool "$APPDIR" "$DIST_DIR/$SLUG-${APP_VERSION}-x86_64.AppImage"
else
  # Locally this is a courtesy — the .deb is the main event and installing
  # appimagetool should not be an entry fee for building it. On CI the same
  # courtesy shipped a release whose notes promised an AppImage that was not
  # there: the workflow had installed the AppImage *runtime* but never the
  # tool, this branch said "skipping" into a log nobody reads, and the
  # uploader was satisfied because the .deb matched its glob. A release
  # build that cannot produce what the release notes sell has to say so
  # with an exit code.
  if [ -n "${CI:-}" ]; then
    echo "!!! appimagetool not found, and this is a CI build." >&2
    echo "    The release notes promise an AppImage; refusing to under-deliver silently." >&2
    exit 1
  fi
  echo "==> appimagetool not found; skipping AppImage (the .deb was still built)"
fi

echo "==> Done"
ls -la "$DIST_DIR"
