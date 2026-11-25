#!/usr/bin/env bash
# Build and sign a Ducky release APK from a clean checkout of a tag.
#
#   scripts/release.sh v0.27.0
#
# You supply the key, through the environment:
#   DUCKY_KEYSTORE_PATH      the keystore file (PKCS12 or JKS). Required.
#   DUCKY_KEYSTORE_KEYALIAS  the key's alias. Default: ducky.
#   DUCKY_KEYSTORE_PASS      the password, used for the store and the key. If unset, the
#                            script asks for it (so it needs a terminal).
#   DUCKY_EXPECTED_CERT      the SHA-256 of the certificate the APK must be signed with.
#                            Default: the certificate of the published Ducky releases. Set it
#                            to your own certificate when you sign with your own key.
#
# The tag is cloned into a temporary directory and built there, so uncommitted changes and
# untracked files in this checkout never reach the release, and the commit inside the APK is
# the tag's. The password reaches Gradle only through the environment: it is never printed,
# never put on a command line and never written to disk, and the configuration cache is off for
# this build so Gradle does not keep it either.
#
# Output in dist/: ducky-<version>.apk, SHA256SUMS and ducky-<version>-mapping.txt (R8's map
# from obfuscated names back to the source names; kept to read crash stack traces from this
# build, never published). Nothing lands there unless the build succeeds, the widget classes
# survived R8, the APK records the tag's commit, and the signing certificate matches.
set -euo pipefail

OFFICIAL_CERT="4F:86:15:6A:55:92:2A:CD:96:98:F0:46:7D:00:09:DD:57:CD:1F:4E:9E:D1:4E:05:7D:24:86:D4:E2:6A:4D:E2"

TAG="${1:-}"
[ -n "$TAG" ] || { echo "usage: scripts/release.sh <tag>" >&2; exit 2; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
    || { echo "No tag $TAG in $REPO" >&2; exit 1; }

STORE="${DUCKY_KEYSTORE_PATH:-}"
[ -n "$STORE" ] && [ -f "$STORE" ] \
    || { echo "Set DUCKY_KEYSTORE_PATH to your keystore file" >&2; exit 1; }
STORE="$(cd "$(dirname "$STORE")" && pwd)/$(basename "$STORE")"
ALIAS="${DUCKY_KEYSTORE_KEYALIAS:-ducky}"
EXPECTED_CERT="${DUCKY_EXPECTED_CERT:-$OFFICIAL_CERT}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK" >&2; exit 1; }
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

if [ -z "${DUCKY_KEYSTORE_PASS:-}" ]; then
    [ -t 0 ] || { echo "Set DUCKY_KEYSTORE_PASS or run this in a terminal" >&2; exit 1; }
    read -rsp "Keystore password: " DUCKY_KEYSTORE_PASS; echo >&2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --quiet --no-local --depth 1 --branch "$TAG" "file://$REPO" "$WORK/src"
cd "$WORK/src"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
[ "$TAG" = "v$VERSION" ] \
    || { echo "Tag $TAG does not match versionName $VERSION (expected v$VERSION)" >&2; exit 1; }

export DUCKY_KEYSTORE_PATH="$STORE"
export DUCKY_KEYSTORE_KEYALIAS="$ALIAS"
export DUCKY_KEYSTORE_STOREPASSWORD="$DUCKY_KEYSTORE_PASS"
export DUCKY_KEYSTORE_KEYPASSWORD="$DUCKY_KEYSTORE_PASS"
unset DUCKY_KEYSTORE_PASS

./gradlew --no-daemon --no-configuration-cache --no-build-cache :app:assembleRelease

unset DUCKY_KEYSTORE_STOREPASSWORD DUCKY_KEYSTORE_KEYPASSWORD

# Glance tells widget providers apart by class. If R8 merges the look-only widget classes,
# every widget draws the same picture (the keep rule is in app/proguard-rules.pro). Each class
# must map to itself.
MAPPING=app/build/outputs/mapping/release/mapping.txt
for c in TimerWidget PondWidget BubblesWidget EggTimerWidget; do
    grep -q "^com.merrimentlabs.ducky.widget.$c -> com.merrimentlabs.ducky.widget.$c:" "$MAPPING" \
        || { echo "R8 merged or renamed the widget class $c (see app/proguard-rules.pro)" >&2; exit 1; }
done

BUILT_APK=app/build/outputs/apk/release/app-release.apk

# The Android Gradle Plugin records the commit it built from in the APK. It must be the tag's,
# so anyone can check out the exact source of a release.
COMMIT="$(git rev-parse HEAD)"
VCS_INFO="$(unzip -p "$BUILT_APK" META-INF/version-control-info.textproto 2>/dev/null || true)"
grep -q "revision: \"$COMMIT\"" <<<"$VCS_INFO" \
    || { echo "The APK does not record commit $COMMIT (META-INF/version-control-info.textproto)" >&2; exit 1; }

APK_CERT="$("$BUILD_TOOLS/apksigner" verify --print-certs "$BUILT_APK" \
    | sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' | head -1 \
    | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"

echo
echo "Tag:           $TAG ($(git rev-parse --short HEAD), recorded in the APK)"
echo "APK cert:      $APK_CERT"
echo "Expected cert: $EXPECTED_CERT"
[ "$APK_CERT" = "$EXPECTED_CERT" ] \
    || { echo "The APK is not signed with the expected certificate; dist/ left as it was" >&2; exit 1; }

# Each R8 build renames differently, so only this build's mapping can retrace its stack traces.
[ -s "$MAPPING" ] || { echo "R8 wrote no mapping at $MAPPING; dist/ left as it was" >&2; exit 1; }

mkdir -p "$REPO/dist"
APK="ducky-$VERSION.apk"
KEPT_MAPPING="ducky-$VERSION-mapping.txt"
cp "$BUILT_APK" "$REPO/dist/$APK"
cp "$MAPPING" "$REPO/dist/$KEPT_MAPPING"
cd "$REPO/dist"
if command -v sha256sum >/dev/null; then sha256sum "$APK" > SHA256SUMS; else shasum -a 256 "$APK" > SHA256SUMS; fi
cat SHA256SUMS
echo "Wrote dist/$APK, dist/SHA256SUMS and dist/$KEPT_MAPPING (keep the mapping; do not publish it)"
