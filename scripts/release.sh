#!/usr/bin/env bash
# Build and sign a Ducky release APK from a clean checkout of a tag, then verify it.
#
#   scripts/release.sh v0.28.0            build, sign and verify
#   scripts/release.sh --check-tag <tag>  run the release-tag check only (git and ssh-keygen only)
#   scripts/release.sh --verify <apk>     verify an APK only (its recorded commit is not compared)
#
# Both need unzip and apksigner: the newest build-tools in ANDROID_HOME (or ANDROID_SDK_ROOT), or
# else the apksigner on PATH (Debian's apksigner package, for one).
#
# You supply the key, through the environment:
#   DUCKY_KEYSTORE_PATH      the keystore file (PKCS12 or JKS). Required to build.
#   DUCKY_KEYSTORE_KEYALIAS  the key's alias. Optional when the keystore holds one key (it is used
#                            whatever its alias), or a key named ducky.
#   DUCKY_KEYSTORE_PASS      the password, used for the store and the key. If unset, the script
#                            asks for it (so it needs a terminal).
#   DUCKY_TAG_KEY            the fingerprint (SHA256:...) of the SSH key that must have signed
#                            the tag. Default: the key that signs the published Ducky tags.
#   DUCKY_EXPECTED_CERT      the SHA-256 of the certificate the APK must be signed with.
#                            Default: the certificate of the published Ducky releases. Set it
#                            to your own certificate when you sign with your own key.
#
# The tag must be a tag object made for this tag's own name, carrying an SSH signature by the
# DUCKY_TAG_KEY key that verifies against the keys in the file git's gpg.ssh.allowedSignersFile
# setting names; the script refuses anything else (see check_tag below), before it reads the
# keystore and again just before it writes dist/, so a tag moved during the build is refused too.
#
# The password and the alias are checked against the keystore, and the password must unlock the
# key, before anything is fetched or built, so a wrong one fails at once. The tag is checked out
# into a temporary directory and built there, so uncommitted changes and untracked files in this
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
# The SSH key that signs official release tags, by its fingerprint (ssh-keygen -lf).
OFFICIAL_TAG_KEY="SHA256:sxKlr/5ruHyIbd2mZcZCEW4L0hVedAq35LioP5GuUBE"
EXPECTED_TAG_KEY="${DUCKY_TAG_KEY:-$OFFICIAL_TAG_KEY}"
[[ "$EXPECTED_TAG_KEY" =~ ^SHA256:[A-Za-z0-9+/]{43}$ ]] \
    || { echo "release: DUCKY_TAG_KEY is not an SSH key fingerprint (SHA256:... as ssh-keygen -lf prints it)" >&2; exit 1; }
VCS_ENTRY=META-INF/version-control-info.textproto

die() { echo "release: $*" >&2; exit 1; }

# The release-tag check. Builds nothing and reads no keystore; run first, and again just before
# anything is written to dist/. Only a tag signed with the release-tag key passes:
# 1. it is a tag object (a lightweight tag carries no signature);
# 2. the object names this tag, so a signed tag made for another version cannot be moved here;
# 3. its signature is an SSH signature. An OpenPGP or X.509 signature is refused before git sees
#    it, because git would check one against whatever keyring the releaser happens to have;
# 4. git verify-tag accepts it against the allowed-signers file git's gpg.ssh.allowedSignersFile
#    names, with ssh-keygen forced as the verifier. git asks it (-Y find-principals, then -Y verify in the "git" namespace at the
#    tag's own date), so the file is read as ssh-keygen documents it, options such as
#    namespaces="git", valid-after=... and cert-authority included, and the signer is found by
#    its key, never by git's user.email;
# 5. the signing key's fingerprint is EXPECTED_TAG_KEY.
# Prints the key and the principal that signed. Sets TAG_OBJECT and TAG_COMMIT.
check_tag() {
    local text named signers verified good principal key
    # The tag is always named in full (refs/tags/<tag>), and a branch of the same name is refused,
    # so nothing here or in a later checkout can take the branch for the tag.
    git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no tag $TAG in $REPO"
    ! git -C "$REPO" show-ref -q --verify "refs/heads/$TAG" \
        || die "a branch is also named $TAG; rename or delete it, so the tag cannot be mistaken for it"
    TAG_OBJECT="$(git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG")"
    [[ "$(git -C "$REPO" cat-file -t "$TAG_OBJECT")" == tag ]] || die "tag $TAG is a lightweight tag, so it carries no signature"
    text="$(git -C "$REPO" cat-file tag "$TAG_OBJECT")"
    named="$(sed -n '1,/^$/s/^tag //p' <<<"$text")"
    [[ "$named" == "$TAG" ]] || die "the tag object at $TAG was made for another name ($named)"
    ! grep -qE -- '^-----BEGIN (PGP SIGNATURE|PGP MESSAGE|SIGNED MESSAGE)-----' <<<"$text" \
        || die "tag $TAG carries an OpenPGP or X.509 signature, which this script does not accept (only SSH)"
    grep -qx -- '-----BEGIN SSH SIGNATURE-----' <<<"$text" || die "tag $TAG has no SSH signature (it is unsigned)"
    signers="$(git -C "$REPO" config --type=path --get gpg.ssh.allowedSignersFile || true)"
    [[ -n "$signers" && -f "$signers" ]] \
        || die "set gpg.ssh.allowedSignersFile (git config) to the file of keys allowed to sign release tags"
    # The verifier is forced to ssh-keygen: a gpg.ssh.program in any git config could otherwise
    # point git at a program that calls every signature good.
    verified="$(git -C "$REPO" -c gpg.format=ssh -c gpg.ssh.program=ssh-keygen \
        -c gpg.ssh.allowedSignersFile="$signers" verify-tag --raw "$TAG_OBJECT" 2>&1)" \
        || die "the SSH signature on tag $TAG does not verify against $signers (a key it does not list, or an altered tag)"
    # ssh-keygen reports: Good "git" signature for <principal> with <TYPE> key SHA256:<fingerprint>
    # (a certificate adds its ID and CA after the fingerprint). Without a listed principal git
    # still reports a good signature, but not "for" anyone, and that is refused.
    good="$(grep -m1 '^Good "git" signature for .* with [^ ]* key SHA256:' <<<"$verified" || true)"
    principal="$(sed -n 's/^Good "git" signature for \(.*\) with [^ ]* key SHA256:.*$/\1/p' <<<"$good")"
    key="$(sed -n 's/^Good "git" signature for .* with [^ ]* key \(SHA256:[A-Za-z0-9+/]*\).*$/\1/p' <<<"$good")"
    [[ -n "$principal" && -n "$key" ]] || die "tag $TAG is signed by a key that $signers does not list"
    [[ "$key" == "$EXPECTED_TAG_KEY" ]] \
        || die "tag $TAG is signed by $key ($principal), not by the release-tag key $EXPECTED_TAG_KEY (set DUCKY_TAG_KEY to build with your own key)"
    TAG_COMMIT="$(git -C "$REPO" rev-parse "$TAG_OBJECT^{commit}")"
    echo "release: tag $TAG is signed by $key, listed for $principal in $signers"
}

REPO="$(cd "$(dirname "$0")/.." && pwd)"

if [[ "${1:-}" == "--check-tag" ]]; then
    TAG="${2:-}"
    [[ -n "$TAG" ]] || { echo "usage: scripts/release.sh --check-tag <tag>" >&2; exit 2; }
    check_tag
    echo "release: tag $TAG passes the release-tag check (commit $TAG_COMMIT)"
    exit 0
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKSIGNER=""
if [[ -n "$SDK" ]]; then
    APKSIGNER="$( (ls -d "$SDK"/build-tools/* 2>/dev/null || true) | sort -V | tail -1)/apksigner"
fi
[[ -x "$APKSIGNER" ]] || APKSIGNER="$(command -v apksigner || true)"
[[ -n "$APKSIGNER" ]] || die "no apksigner: set ANDROID_HOME to an Android SDK with build-tools, or put apksigner on PATH"
command -v unzip >/dev/null || die "unzip not on PATH"

# Every check a release APK must pass. $2, when given, is the commit the APK must record.
# Prints what it checked; returns non-zero on any failure.
verify() {
    local apk="$1" want="${2:-}" fail=0 out rc sha dn rev
    [[ -f "$apk" ]] || die "no APK at $apk"

    echo "== Signature"
    # One apksigner run: its exit status says whether the signature verifies at all (a modified
    # APK does not), and its output names the signing certificate.
    rc=0
    out="$("$APKSIGNER" verify --verbose --print-certs "$apk" 2>&1)" || rc=$?
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
[[ -n "$TAG" ]] || { echo "usage: scripts/release.sh <tag> | --check-tag <tag> | --verify <apk>" >&2; exit 2; }
check_tag

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
# Fetch only the tag and check out its commit. "git clone --branch <tag> --depth 1" would work
# too, but warns "refs/tags/<tag> ... is not a commit!" for every annotated or signed tag.
git init --quiet "$WORK/src"
cd "$WORK/src"
git fetch --quiet --depth 1 --no-tags "file://$REPO" "refs/tags/$TAG"
git -c advice.detachedHead=false checkout --quiet FETCH_HEAD
[[ "$(git rev-parse HEAD)" == "$TAG_COMMIT" ]] || die "the checkout is not the commit tag $TAG names"

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

# The tag could have been moved or replaced while the build ran: check it again, and that it is
# still the object this build came from.
BUILT_OBJECT="$TAG_OBJECT"
check_tag
[[ "$TAG_OBJECT" == "$BUILT_OBJECT" ]] \
    || die "tag $TAG moved during the build (it was $BUILT_OBJECT, now $TAG_OBJECT); dist/ left as it was"

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
