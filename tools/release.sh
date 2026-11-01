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
#
# `pipefail` ist Absicht: faellt ein Glied einer Pipeline aus, gilt sie als
# gescheitert. Deshalb steht nirgends `| head -n`: `head` schliesst die Pipe
# und der schreibende Prozess stirbt an SIGPIPE (Exit 141) - das Skript
# bricht dann mitten im Ablauf ab, obwohl nichts kaputt ist. Gekuerzt wird
# mit `| sed -n '1,3p'`, das alles liest.
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

if [ "$(git config --get core.hooksPath || true)" != ".githooks" ]; then
    echo "Hinweis: der pre-push-Haken ist nicht aktiv." \
        " Aktivieren: git config --local core.hooksPath .githooks"
fi

# Marken vom Server holen: `gh release create` legt sie dort an, und ein
# Klon, der nur gepusht hat, kennt sie sonst nicht. Ohne das verglich die
# Sperre unten gegen eine veraltete Vorgaengerversion.
git fetch --tags --quiet origin || fail "Marken liessen sich nicht holen (origin erreichbar?)"

PREV_TAG=$(git tag --sort=-v:refname | sed -n '1p')
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
FIRST_LINE=$(printf '%s\n' "$BADGING" | sed -n '1p')
echo "$FIRST_LINE"
printf '%s\n' "$BADGING" | grep -q "versionName='$VERSION_NAME'" ||
    fail "APK traegt nicht $VERSION_NAME. Erste Zeile:
$FIRST_LINE"

"$BT/apksigner" verify --print-certs "$APK" | sed -n '1,3p' ||
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

# Die Marke wird hier gesetzt, nicht von `gh` am entfernten Zweig-Kopf:
# sonst zeigt sie auf den zuletzt gepushten Stand, und der kann aelter sein
# als das, was hier gerade gebaut wurde. v1.9.3 stand so auf dem Commit
# *vor* der Aenderung, die das Release beschreibt.
BRANCH=$(git rev-parse --abbrev-ref HEAD)
step "Zweig $BRANCH pushen"
git push --quiet origin "HEAD:$BRANCH" || fail "Zweig liess sich nicht pushen"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    [ "$(git rev-parse "$TAG^{commit}")" = "$(git rev-parse HEAD)" ] ||
        fail "Marke $TAG existiert bereits und zeigt nicht auf HEAD."
else
    git tag -a "$TAG" -m "$TAG" || fail "Marke liess sich nicht setzen"
fi

# Der Push der Marke laeuft durch .githooks/pre-push - die Suite also ein
# zweites Mal, mit genau demselben Zaehler. Das kostet eine halbe Minute
# und ist der Punkt des Hakens: kein Umweg, auch nicht fuer dieses Skript.
step "Marke $TAG pushen (pre-push prueft die Suite erneut)"
git push --quiet origin "refs/tags/$TAG" || fail "Marke liess sich nicht pushen"

step "gh release create $TAG"
# --verify-tag: die Marke existiert schon und zeigt auf den gebauten Stand.
gh release create "$TAG" "$APK" \
    --title "$TAG" \
    --notes-file "$NOTES" \
    --verify-tag

step "Veroeffentlichtes Artefakt zurueckpruefen"
CHECK=$(mktemp -d)
trap 'rm -rf "$CHECK"' EXIT
gh release download "$TAG" --pattern "*.apk" --dir "$CHECK" --clobber
PUB=$(ls "$CHECK"/*.apk | sed -n '1p')
[ -f "$PUB" ] || fail "Keine APK im Release $TAG"

PUB_LINE=$("$BT/aapt2" dump badging "$PUB" | sed -n '1p')
echo "$PUB_LINE"
printf '%s\n' "$PUB_LINE" | grep -q "versionName='$VERSION_NAME'" ||
    fail "Veroeffentlichte APK traegt nicht $VERSION_NAME:
$PUB_LINE"
"$BT/apksigner" verify "$PUB" || fail "Signatur der veroeffentlichten Datei ist nicht in Ordnung"

# Die Bytes muessen die sein, die hier gebaut wurden - ein Upload kann
# eine andere Datei treffen, und `apksigner` merkt das nicht.
LOCAL_SHA=$(sha256sum "$APK" | awk '{print $1}')
PUB_SHA=$(sha256sum "$PUB" | awk '{print $1}')
[ "$LOCAL_SHA" = "$PUB_SHA" ] ||
    fail "Veroeffentlichte Datei weicht ab: lokal $LOCAL_SHA, im Release $PUB_SHA"
echo "Signatur und SHA-256 der veroeffentlichten Datei: in Ordnung"

step "Fertig: $TAG mit $TESTS gruenen Tests veroeffentlicht"
