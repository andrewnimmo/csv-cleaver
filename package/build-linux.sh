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
MAIN_JAR=$(basename "$STAGE_DIR"/*.jar)

ICON_OPT=()
if [ -f "$ICON" ]; then
  ICON_OPT=(--icon "$ICON")
fi

COMMON=(
  --name "$APP_NAME"
  --app-version "$APP_VERSION"
  --vendor "$VENDOR"
  --copyright "$COPYRIGHT"
  --input "$STAGE_DIR"
  --main-jar "$MAIN_JAR"
  --main-class csv_cleaver.app
  --java-options "--enable-native-access=ALL-UNNAMED"
  --java-options "-Dfile.encoding=UTF-8"
)

echo "==> jpackage (.deb)"
jpackage --type deb --dest "$DIST_DIR" \
  --linux-shortcut \
  --linux-menu-group Utility \
  "${COMMON[@]}" "${ICON_OPT[@]}"

echo "==> jpackage (app image, for the AppImage)"
jpackage --type app-image --dest "$APP_DIR" "${COMMON[@]}" "${ICON_OPT[@]}"

if command -v appimagetool >/dev/null 2>&1; then
  echo "==> AppImage"
  APPDIR="$APP_DIR/$APP_NAME"
  cat > "$APPDIR/AppRun" <<EOF
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/bin/$APP_NAME" "\$@"
EOF
  chmod +x "$APPDIR/AppRun"
  appimagetool "$APPDIR" "$DIST_DIR/${APP_NAME// /-}-${APP_VERSION}-x86_64.AppImage"
else
  echo "==> appimagetool not found; skipping AppImage (the .deb was still built)"
fi

echo "==> Done"
ls -la "$DIST_DIR"
