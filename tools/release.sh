#!/usr/bin/env bash
#
# Release mit Testpflicht.
#
# Der Ablauf hier ist nicht Bequemlichkeit, sondern die Sperre: erst die
# volle Testsuite, dann bauen, dann veroeffentlichen. Jeder Schritt bricht
# beim ersten Fehler ab (`set -e`), und die Suite gilt nur als gruen, wenn
# sie wirklich gelaufen ist - alte Testergebnisse aus `build/` werden vorher
# geloescht, sonst koennte ein Lauf ohne Tests als Erfolg durchgehen.
#
#   tools/release.sh              # bauen und veroeffentlichen
#   tools/release.sh --dry-run    # Suite + Bau + Pruefung, ohne gh release
#
# Voraussetzungen: signierter Keystore (keystore.properties), gh CLI,
# angemeldet (`gh auth status`).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT=$(pwd)

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unbekannte Option: $arg" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFEHLER:\033[0m %s\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------------------
# 0. Werkzeug
# --------------------------------------------------------------------------
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
[ -x "$JAVA_HOME/bin/java" ] || fail "Kein JDK unter JAVA_HOME=$JAVA_HOME"
[ -d "$ANDROID_HOME" ] || fail "Kein SDK unter ANDROID_HOME=$ANDROID_HOME"

BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "${BT:-}" ] || fail "Keine build-tools im SDK"

VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] ||
    fail "versionName/versionCode nicht in app/build.gradle.kts gefunden"
TAG="v$VERSION_NAME"

step "Release $TAG (versionCode $VERSION_CODE)"

# --------------------------------------------------------------------------
# 1. Arbeitsbaum und letzte Version
# --------------------------------------------------------------------------
[ -z "$(git status --porcelain)" ] ||
    fail "Arbeitsbaum ist nicht sauber - was veroeffentlicht wird, muss committet sein:

$(git status --short)"

PREV_TAG=$(git tag --sort=-v:refname | head -1 || true)
if [ -n "$PREV_TAG" ]; then
    PREV_CODE=$(git show "$PREV_TAG:app/build.gradle.kts" 2>/dev/null |
        sed -n 's/.*versionCode = \([0-9]*\).*/\1/p') || PREV_CODE=""
    if [ -n "$PREV_CODE" ] && [ "$VERSION_CODE" -le "$PREV_CODE" ]; then
        fail "versionCode $VERSION_CODE ist nicht groesser als $PREV_CODE aus $PREV_TAG." \
            " Ohne Erhoehung behandelt Android den Wechsel als Neuinstallation."
    fi
    echo "Letzte Marke: $PREV_TAG (versionCode ${PREV_CODE:-unbekannt})"
fi
if gh release view "$TAG" >/dev/null 2>&1; then
    fail "$TAG existiert bereits als Release. Version erhoehen statt ein Release zu ueberschreiben."
fi

# --------------------------------------------------------------------------
# 2. Die Sperre: volle Testsuite
# --------------------------------------------------------------------------
step "Volle Testsuite (muss gruen sein, sonst kein Release)"
RESULTS=app/build/test-results/testDebugUnitTest
rm -rf "$RESULTS"
./gradlew :app:testDebugUnitTest --offline --rerun-tasks || fail "Testsuite ist gescheitert"

[ -d "$RESULTS" ] || fail "Keine Testergebnisse unter $RESULTS - der Lauf hat nichts geprueft"

read -r TESTS FAILURES ERRORS SKIPPED < <(
    awk -F'"' '/<testsuite / {
        for (i = 1; i < NF; i++) {
            if ($i ~ /tests=$/) t += $(i + 1)
            if ($i ~ /failures=$/) f += $(i + 1)
            if ($i ~ /errors=$/) e += $(i + 1)
            if ($i ~ /skipped=$/) s += $(i + 1)
        }
    } END { print t + 0, f + 0, e + 0, s + 0 }' "$RESULTS"/*.xml
)
[ "$TESTS" -gt 0 ] || fail "Testsuite meldet 0 Tests - das ist kein gruener Lauf, sondern ein leerer"
[ "$FAILURES" -eq 0 ] && [ "$ERRORS" -eq 0 ] ||
    fail "$FAILURES Fehlschläge, $ERRORS Fehler in $TESTS Tests - siehe $RESULTS"
step "Suite gruen: $TESTS Tests, 0 Fehler, $SKIPPED übersprungen"

# --------------------------------------------------------------------------
# 3. Bauen und das Artefakt pruefen, nicht den Quelltext
# --------------------------------------------------------------------------
step "Release-APK bauen"
./gradlew :app:assembleRelease --offline

APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || fail "Kein Release-APK unter $APK"

BADGING=$("$BT/aapt2" dump badging "$APK")
echo "$BADGING" | grep -q "versionName='$VERSION_NAME'" ||
    fail "APK traegt nicht $VERSION_NAME:
$(echo "$BADGING" | head -1)"
echo "$BADGING" | head -1

"$BT/apksigner" verify --print-certs "$APK" | head -3 ||
    fail "Signaturpruefung gescheitert"

if [ "$DRY_RUN" -eq 1 ]; then
    step "Trockenlauf beendet - nichts veroeffentlicht ($APK)"
    exit 0
fi

# --------------------------------------------------------------------------
# 4. Veroeffentlichen - und das Veroeffentlichte zurueckpruefen
# --------------------------------------------------------------------------
NOTES=$(mktemp)
trap 'rm -f "$NOTES"' EXIT
cat >"$NOTES" <<NOTES_EOF
Release $TAG

$(sed -n "/^## \[$VERSION_NAME\]/,/^## \[/p" CHANGELOG.md | sed '$d' | tail -n +2)
NOTES_EOF

step "gh release create $TAG"
gh release create "$TAG" "$APK" \
    --title "$TAG" \
    --notes-file "$NOTES" \
    --target "$(git rev-parse --abbrev-ref HEAD)"

step "Veroeffentlichtes Artefakt zurueckpruefen"
CHECK=$(mktemp -d)
rm -rf "$CHECK"/*
gh release download "$TAG" --pattern "*.apk" --dir "$CHECK" --clobber
"$BT/aapt2" dump badging "$(ls "$CHECK"/*.apk | head -1)" | head -1
"$BT/apksigner" verify "$(ls "$CHECK"/*.apk | head -1)" &&
    echo "Signatur der veroeffentlichten Datei: in Ordnung"
rm -rf "$CHECK"

step "Fertig: $TAG mit $TESTS gruenen Tests veroeffentlicht"
