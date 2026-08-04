#!/usr/bin/env bash
# Builds CSV Cleaver.dmg on macOS.
#
# Requires JDK 25 (which ships jpackage), the Clojure CLI, and Babashka.
# Everything nameable comes from resources/branding.edn, so rebranding the
# installer means editing that file, not this script.

set -euo pipefail
cd "$(dirname "$0")/.."

read_brand() {
  bb -e "(:$1 (read-string (slurp \"resources/branding.edn\")))" | tr -d '"'
}

APP_NAME=$(read_brand name)
APP_VERSION=$(read_brand version)
BUNDLE_ID=$(read_brand bundle-id)
VENDOR=$(read_brand vendor)
COPYRIGHT=$(read_brand copyright)
ICON=$(bb -e '(get-in (read-string (slurp "resources/branding.edn")) [:icons :macos])' | tr -d '"')

DIST_DIR="dist"
STAGE_DIR="target/jpackage-input"

echo "==> Building uberjar"
clojure -T:build uber

echo "==> Staging"
rm -rf "$DIST_DIR" "$STAGE_DIR"
mkdir -p "$DIST_DIR" "$STAGE_DIR"
cp target/csv-cleaver-*.jar "$STAGE_DIR/"
MAIN_JAR=$(basename "$STAGE_DIR"/*.jar)

ICON_OPT=()
if [ -f "$ICON" ]; then
  ICON_OPT=(--icon "$ICON")
else
  echo "==> No icon at $ICON; building with the default"
fi

echo "==> jpackage"
jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --vendor "$VENDOR" \
  --copyright "$COPYRIGHT" \
  --mac-package-identifier "$BUNDLE_ID" \
  --input "$STAGE_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class csv_cleaver.main \
  --dest "$DIST_DIR" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Dfile.encoding=UTF-8" \
  ${ICON_OPT[@]+"${ICON_OPT[@]}"}
  # ^ macOS still ships bash 3.2, where expanding an empty array under `set -u`
  #   is an "unbound variable" error. The ${x[@]+...} form is the portable way
  #   to say "these arguments, if there are any".

echo "==> Done"
ls -la "$DIST_DIR"
