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
# The licence, the copyright notice and the third-party attributions travel
# inside the installed application — NOTICE promises as much, and a warranty
# disclaimer the user never receives is worth little. jpackage copies
# everything in --input into the app's directory.
cp LICENSE NOTICE THIRD-PARTY.md "$STAGE_DIR/"
MAIN_JAR=$(basename "$STAGE_DIR"/*.jar)

ICON_OPT=()
if [ -f "$ICON" ]; then
  ICON_OPT=(--icon "$ICON")
else
  echo "==> No icon at $ICON; building with the default"
fi

# ── The runtime ─────────────────────────────────────────────────────────────
#
# jlink includes only the modules something declares a dependency on, and
# nothing declares one on locale data. Left to itself, jpackage bundled a
# runtime with data for a single locale, so java.text formatted every language
# as English: a Spanish window reading "470,128 filas de datos" while every
# other word on the screen was Spanish. It shipped that way from the first
# installer, and no test caught it because tests run on a full JDK, where the
# module is always there.
#
# ALL-DEFAULT is not the answer — it produces java.base and nothing else, and
# the application will not start. --add-modules replaces jpackage's own
# detection rather than adding to it, and --jlink-options refuses --add-modules
# outright, so the set has to be named in full. java.se is the aggregator for
# everything in the standard edition, which is stable across JDK releases in a
# way that a list of fifty-two individual modules is not.
MODULES="java.se,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.management"

APP_IMAGE_DIR="target/app-image"
rm -rf "$APP_IMAGE_DIR"

echo "==> jpackage (app image)"
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --vendor "$VENDOR" \
  --copyright "$COPYRIGHT" \
  --mac-package-identifier "$BUNDLE_ID" \
  --input "$STAGE_DIR" \
  --main-jar "$MAIN_JAR" \
  --license-file LICENSE \
  --add-modules "$MODULES" \
  --main-class csv_cleaver.main \
  --dest "$APP_IMAGE_DIR" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Dfile.encoding=UTF-8" \
  ${ICON_OPT[@]+"${ICON_OPT[@]}"}
  # ^ macOS still ships bash 3.2, where expanding an empty array under `set -u`
  #   is an "unbound variable" error. The ${x[@]+...} form is the portable way
  #   to say "these arguments, if there are any".

# ── Checks, before it becomes an installer someone downloads ────────────────
#
# The app image is built first precisely so there is something to look inside.
# A .dmg leaves nothing behind to check, which is how the previous version of
# this check came to sit there passing without ever running.
APP="$APP_IMAGE_DIR/$APP_NAME.app"
RUNTIME_RELEASE="$APP/Contents/runtime/Contents/Home/release"

if ! grep -q "jdk.localedata" "$RUNTIME_RELEASE"; then
  echo "!!! jdk.localedata is missing from the bundled runtime." >&2
  echo "    Every language would show English number formatting." >&2
  exit 1
fi
echo "==> Locale data is in the runtime"

# It has to start, too. A runtime missing something the application needs fails
# at class loading, which no amount of reading a module list would reveal.
if ! "$APP/Contents/MacOS/$APP_NAME" --version >/dev/null 2>&1; then
  echo "!!! The packaged application does not start." >&2
  "$APP/Contents/MacOS/$APP_NAME" --version >&2 || true
  exit 1
fi
echo "==> It starts: $("$APP/Contents/MacOS/$APP_NAME" --version)"

echo "==> jpackage (dmg)"
jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --app-image "$APP" \
  --mac-package-identifier "$BUNDLE_ID" \
  --dest "$DIST_DIR"

echo "==> Done"
ls -la "$DIST_DIR"
