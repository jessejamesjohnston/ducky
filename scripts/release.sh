#!/usr/bin/env bash
# Build and sign a Ducky release APK from a clean checkout of a tag, then verify it.
#
#   scripts/release.sh v0.28.0            build, sign and verify
#   scripts/release.sh --verify <apk>     verify an APK only (its recorded commit is not compared)
#
# You supply the key, through the environment:
#   DUCKY_KEYSTORE_PATH      the keystore file (PKCS12 or JKS). Required to build.
#   DUCKY_KEYSTORE_KEYALIAS  the key's alias. Optional when the keystore holds one key (it is used
#                            whatever its alias), or a key named ducky.
#   DUCKY_KEYSTORE_PASS      the password, used for the store and the key. If unset, the script
#                            asks for it (so it needs a terminal).
#   DUCKY_EXPECTED_CERT      the SHA-256 of the certificate the APK must be signed with.
#                            Default: the certificate of the published Ducky releases. Set it
#                            to your own certificate when you sign with your own key.
#
# The password and the alias are checked against the keystore, and the password must unlock the
# key, before anything is cloned or built, so a wrong one fails at once. The tag is cloned into
# a temporary directory and built there, so uncommitted changes and untracked files in this
# checkout never reach the release, and the commit recorded inside the APK is the tag's. The
# password reaches keytool and Gradle only through the environment: it is never printed, never
# put on a command line and never written to disk, and Gradle runs without its daemon or
# configuration cache so it does not outlive this run.
#
# Output in dist/: ducky-<version>.apk, SHA256SUMS and ducky-<version>-mapping.txt (R8's map
# from obfuscated names back to the source names; kept to read crash stack traces from this
# build, never published). Nothing lands there unless the build succeeds and every check passes.
set -euo pipefail

OFFICIAL_CERT="4F:86:15:6A:55:92:2A:CD:96:98:F0:46:7D:00:09:DD:57:CD:1F:4E:9E:D1:4E:05:7D:24:86:D4:E2:6A:4D:E2"
EXPECTED_CERT="${DUCKY_EXPECTED_CERT:-$OFFICIAL_CERT}"
VCS_ENTRY=META-INF/version-control-info.textproto

die() { echo "release: $*" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" ]] || die "set ANDROID_HOME to your Android SDK"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[[ -x "$BUILD_TOOLS/apksigner" ]] || die "no apksigner in $BUILD_TOOLS"

# Every check a release APK must pass. $2, when given, is the commit the APK must record.
# Prints what it checked; returns non-zero on any failure.
verify() {
    local apk="$1" want="${2:-}" fail=0 out rc sha dn rev
    [[ -f "$apk" ]] || die "no APK at $apk"

    echo "== Signature"
    # One apksigner run: its exit status says whether the signature verifies at all (a modified
    # APK does not), and its output names the signing certificate.
    rc=0
    out="$("$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$apk" 2>&1)" || rc=$?
    if (( rc != 0 )); then
        echo "  the signature does not verify (the APK was modified after signing, or is not signed)" >&2
        sed -n 's/^ERROR: /  apksigner: /p' <<<"$out" | head -3 >&2
        return 1
    fi
    grep -E "^Verified using|^Verifies" <<<"$out" || true
    sha=$(sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' <<<"$out" | head -1 | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')
    dn=$(sed -n 's/^.*[Ss]igner.* certificate DN: //p' <<<"$out" | head -1)
    echo "Certificate SHA-256: $sha"
    echo "Expected:            $EXPECTED_CERT"
    [[ "$sha" == "$EXPECTED_CERT" ]] || { echo "  not the expected certificate" >&2; fail=1; }
    # Android's debug keys all carry this subject; a debug-signed APK must never be published.
    [[ "$dn" != *"CN=Android Debug"* ]] || { echo "  signed with a debug key" >&2; fail=1; }

    # The Android Gradle Plugin records the commit a release was built from, so anyone can check
    # out the exact source.
    echo "== Source commit"
    rev=$(unzip -p "$apk" "$VCS_ENTRY" 2>/dev/null | sed -n 's/^ *revision: "\([0-9a-f]*\)".*/\1/p' | head -1)
    if [[ -z "$rev" ]]; then
        echo "  no $VCS_ENTRY naming a commit" >&2; fail=1
    else
        echo "Recorded: $rev"
        if [[ -n "$want" ]]; then
            echo "Tag:      $want"
            [[ "$rev" == "$want" ]] || { echo "  not the tag's commit" >&2; fail=1; }
        fi
    fi

    return "$fail"
}

if [[ "${1:-}" == "--verify" ]]; then
    verify "${2:-}" || die "verification FAILED"
    echo "release: verified"
    exit 0
fi

TAG="${1:-}"
[[ -n "$TAG" ]] || { echo "usage: scripts/release.sh <tag> | --verify <apk>" >&2; exit 2; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no tag $TAG in $REPO"
TAG_COMMIT="$(git -C "$REPO" rev-parse "$TAG^{commit}")"

STORE="${DUCKY_KEYSTORE_PATH:-}"
[[ -n "$STORE" && -f "$STORE" ]] || die "set DUCKY_KEYSTORE_PATH to your keystore file"
STORE="$(cd "$(dirname "$STORE")" && pwd)/$(basename "$STORE")"

if [[ -z "${DUCKY_KEYSTORE_PASS:-}" ]]; then
    [[ -t 0 ]] || die "set DUCKY_KEYSTORE_PASS or run this in a terminal"
    read -rsp "Keystore password: " DUCKY_KEYSTORE_PASS; echo >&2
fi
[[ -n "$DUCKY_KEYSTORE_PASS" ]] || die "empty keystore password"
# Fail before Gradle if the password or alias is wrong. keytool reads the password from the
# environment, and runs in English so its listing can be parsed the same way everywhere. The
# locale is left alone: Java decodes the password in the locale's character set, as Gradle will
# when it signs, so a password with non-ASCII characters checks out here exactly when it works there.
export DUCKY_KEYSTORE_PASS
keytool_en() { keytool -J-Duser.language=en -J-Duser.country=US "$@"; }
KEYS="$(keytool_en -list -keystore "$STORE" -storepass:env DUCKY_KEYSTORE_PASS 2>/dev/null)" \
    || die "the keystore or its password did not check out"
# "alias, date, PrivateKeyEntry," per key. Keystores store aliases in lowercase.
ALIASES="$(sed -n 's/^\([^,]*\),.*PrivateKeyEntry.*/\1/p' <<<"$KEYS")"
[[ -n "$ALIASES" ]] || die "no private key in the keystore"
if [[ -n "${DUCKY_KEYSTORE_KEYALIAS:-}" ]]; then
    ALIAS="$DUCKY_KEYSTORE_KEYALIAS"
    # Keystore lookups ignore case, so MyKey names the key listed as mykey.
    grep -qixF "$ALIAS" <<<"$ALIASES" || die "no key named '$ALIAS' in the keystore (it holds: $(paste -sd, - <<<"$ALIASES"))"
elif [[ "$(grep -c . <<<"$ALIASES")" == 1 ]]; then
    ALIAS="$ALIASES"
elif grep -qixF ducky <<<"$ALIASES"; then
    ALIAS=ducky
else
    die "the keystore holds several keys ($(paste -sd, - <<<"$ALIASES")); set DUCKY_KEYSTORE_KEYALIAS to one"
fi
# The build uses the same password for the key. Unlocking the key proves it (the request
# keytool writes is thrown away).
keytool_en -certreq -keystore "$STORE" -alias "$ALIAS" -storepass:env DUCKY_KEYSTORE_PASS \
    -keypass:env DUCKY_KEYSTORE_PASS >/dev/null 2>&1 \
    || die "the password does not unlock the key '$ALIAS' (the key's password must equal the keystore's)"
echo "release: signing with key '$ALIAS'"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git -c advice.detachedHead=false clone --quiet --no-local --depth 1 --branch "$TAG" "file://$REPO" "$WORK/src"
cd "$WORK/src"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
[[ "$TAG" == "v$VERSION" ]] || die "tag $TAG does not match versionName $VERSION (expected v$VERSION)"

export DUCKY_KEYSTORE_PATH="$STORE"
export DUCKY_KEYSTORE_KEYALIAS="$ALIAS"
export DUCKY_KEYSTORE_STOREPASSWORD="$DUCKY_KEYSTORE_PASS"
export DUCKY_KEYSTORE_KEYPASSWORD="$DUCKY_KEYSTORE_PASS"
unset DUCKY_KEYSTORE_PASS

echo "release: building $TAG ($(git rev-parse --short HEAD)) with R8"
./gradlew --no-daemon --no-configuration-cache --no-build-cache --console=plain -q :app:assembleRelease
unset DUCKY_KEYSTORE_STOREPASSWORD DUCKY_KEYSTORE_KEYPASSWORD

BUILT_APK=app/build/outputs/apk/release/app-release.apk
BUILT_MAPPING=app/build/outputs/mapping/release/mapping.txt
# Each R8 build renames differently, so only this build's mapping can retrace its stack traces.
[[ -s "$BUILT_MAPPING" ]] || die "R8 wrote no mapping at $BUILT_MAPPING; dist/ left as it was"

# Glance tells widget providers apart by class. If R8 merges the look-only widget classes,
# every widget draws the same picture (the keep rule is in app/proguard-rules.pro). Each class
# must map to itself.
for c in TimerWidget PondWidget BubblesWidget EggTimerWidget; do
    grep -q "^com.merrimentlabs.ducky.widget.$c -> com.merrimentlabs.ducky.widget.$c:" "$BUILT_MAPPING" \
        || die "R8 merged or renamed the widget class $c (see app/proguard-rules.pro); dist/ left as it was"
done

verify "$BUILT_APK" "$TAG_COMMIT" || die "verification FAILED; dist/ left as it was"

mkdir -p "$REPO/dist"
APK="ducky-$VERSION.apk"
MAPPING="ducky-$VERSION-mapping.txt"
cp "$BUILT_APK" "$REPO/dist/$APK"
cp "$BUILT_MAPPING" "$REPO/dist/$MAPPING"
cd "$REPO/dist"
if command -v sha256sum >/dev/null; then sha256sum "$APK" > SHA256SUMS; else shasum -a 256 "$APK" > SHA256SUMS; fi
cat SHA256SUMS
ls -l "$APK" | awk '{printf "%s  %.1f MB\n", $NF, $5/1048576}'
echo "release: wrote dist/$APK, dist/SHA256SUMS and dist/$MAPPING (keep the mapping; do not publish it)."
echo "release: certificate SHA-256: $EXPECTED_CERT"
