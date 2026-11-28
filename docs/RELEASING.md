# Releasing Ducky

Ducky is released as a signed APK on GitHub. Every release is built from a clean checkout of its
tag and signed on the maintainer's machine with the app's release key, by `scripts/release.sh`.
CI never sees the key and never builds a release; it builds and tests only.

## The release key

- One key signs every release, so each one installs as an update over the last. A lost key or
  password means no more updates to installed copies, so the keystore is backed up on a second
  machine and its password lives in a password manager, never in a file. The keystore and the
  key share that one password; `scripts/release.sh` asks for it once and uses it for both.
- Certificate SHA-256 (published in the README and in every release's notes so anyone can check
  an APK):
  `4F:86:15:6A:55:92:2A:CD:96:98:F0:46:7D:00:09:DD:57:CD:1F:4E:9E:D1:4E:05:7D:24:86:D4:E2:6A:4D:E2`
  This is the form `keytool` and `scripts/release.sh --verify` print. `apksigner verify
  --print-certs` prints the same value in lowercase without colons:
  `4f86156a55922acd9698f0467d0009dd57cd1f4e9ed14e057d2486d4e26a4de2`
- Never commit a keystore (`*.jks`, `*.keystore`, `*.p12` and `*.pfx` are gitignored) and never
  put the password in a file, `local.properties`, a command line or a log.

## The release-tag key

Release tags are signed with the maintainer's SSH key, and `scripts/release.sh` builds only a tag
signed by it. The script pins the key by its fingerprint (what `ssh-keygen -lf <key>.pub`
prints), never by an email address:
`SHA256:sxKlr/5ruHyIbd2mZcZCEW4L0hVedAq35LioP5GuUBE`

The maintainer's git setup, once per machine (the key's public half, listed in an allowed-signers
file under any principal: it need not be the email git commits with, because the script finds the
signer by key):

```sh
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/<signing key>.pub
git config --global gpg.ssh.allowedSignersFile ~/.ssh/allowed_signers
```

`scripts/release.sh --check-tag vX.Y.Z` runs the tag check alone, with no keystore and no build,
and prints the key and the principal that signed. It is the check to run on a tag before
building, and on an old tag to see that it still passes.

How Gradle gets the key: `app/build.gradle.kts` reads `ducky.keystore.{path,storePassword,
keyAlias,keyPassword}` from `local.properties`, then Gradle properties, then the environment as
`DUCKY_KEYSTORE_PATH`, `DUCKY_KEYSTORE_STOREPASSWORD`, `DUCKY_KEYSTORE_KEYALIAS` and
`DUCKY_KEYSTORE_KEYPASSWORD`. Without them a release build stops at `preReleaseBuild` on purpose.
`scripts/release.sh` uses the environment, so nothing is written to disk.
`-PallowDebugSigning=true` allows a debug-signed R8 build for local testing; such a build must
never be distributed.

## Signing your own build

You can build and sign Ducky with your own key. Android will not install it over an official
release (the signatures differ), so uninstall the official copy first; your timer settings start
fresh.

1. Set `versionName` in `app/build.gradle.kts` to your version and commit. The tag must be `v`
   plus that version: for the tag `v0.28.1-mine` in step 4, `versionName` is `0.28.1-mine`.
2. Let git check your tag's signature. The script builds only a tag with an SSH signature, by
   the key you name in step 4, that the file git's `gpg.ssh.allowedSignersFile` setting names
   lists. An OpenPGP or X.509 signature is refused. The name at the start of the line is any
   principal you like (it need not be your git email), and the line may carry options such as
   `namespaces="git"` between the name and the key, as ssh-keygen allows:

   ```sh
   mkdir -p ~/.config/git
   echo "you@example.com $(cat ~/.ssh/id_ed25519.pub)" >> ~/.config/git/allowed_signers
   git config gpg.ssh.allowedSignersFile ~/.config/git/allowed_signers
   git config gpg.format ssh
   git config user.signingkey ~/.ssh/id_ed25519.pub
   ```

3. Find your certificate's SHA-256. The script refuses an APK signed with any other certificate,
   and without this it expects the official one:

   ```sh
   keytool -list -v -keystore ~/keys/mykey.p12 | grep 'SHA256:'
   ```

4. Tag, check the tag with your key pinned, and build:

   ```sh
   git tag -s v0.28.1-mine -m "Ducky 0.28.1-mine"
   export DUCKY_TAG_KEY="$(ssh-keygen -lf ~/.ssh/id_ed25519.pub | awk '{print $2}')"
   scripts/release.sh --check-tag v0.28.1-mine
   DUCKY_KEYSTORE_PATH=~/keys/mykey.p12 \
   DUCKY_KEYSTORE_KEYALIAS=<your key's alias> \
   DUCKY_EXPECTED_CERT=<the SHA256 value from step 3> \
   scripts/release.sh v0.28.1-mine
   ```

The script asks for the password. Without a terminal, set `DUCKY_KEYSTORE_PASS` in the
environment instead. It uses that one password for the keystore and the key, so your key password
must equal your store password (keytool makes PKCS12 keystores that way). You can leave out
`DUCKY_KEYSTORE_KEYALIAS` when your keystore holds a single key, or a key named `ducky`. Before it
fetches or builds anything, the script opens the keystore with that password, looks for the key
(the alias is matched without regard to case, as keystores do) and unlocks the key with the same
password. A wrong password or alias, or a key whose password differs from the keystore's, stops
it at once, without printing the password.

## Every release

1. **Version.** Raise `versionCode` by one and set `versionName` in `app/build.gradle.kts`. An
   installed copy refuses an update whose `versionCode` is not higher.
2. **Changelog.** Write the release's section in `CHANGELOG.md`.
3. **Green.** `./gradlew createDebugUnitTestCoverageReport lintDebug` and
   `python3 scripts/coverage.py --min 95`, the same checks CI runs.
4. **Tag.** Commit, then make a signed tag `vX.Y.Z` (`git tag -s vX.Y.Z -m "Ducky X.Y.Z"`),
   check it with `scripts/release.sh --check-tag vX.Y.Z`, and push it. A tag starts no release
   build.
5. **Build.** `DUCKY_KEYSTORE_PATH=<keystore> scripts/release.sh vX.Y.Z`. It first checks the
   tag, before it reads the keystore:
   - the tag is a tag object (not a lightweight tag) made for this tag's own name, so a signed
     tag for another version cannot be moved under this name. It is always looked up as
     `refs/tags/<tag>`, and a branch with the same name is refused;
   - it carries an SSH signature. An OpenPGP or X.509 signature is refused before git sees it,
     since git would check one against whatever keyring is at hand;
   - `git verify-tag` accepts the signature against the allowed-signers file git's
     `gpg.ssh.allowedSignersFile` names, with ssh-keygen forced as the verifier (a
     `gpg.ssh.program` in any git config is ignored). git has ssh-keygen find the principal
     the file lists for the signing key and verify it, so options on the key's line are read as ssh-keygen
     reads them, and the signer is found by key, not by git's `user.email`;
   - the signing key's fingerprint is the release-tag key above (`DUCKY_TAG_KEY` replaces it
     for your own builds).

   It prints the key and the principal that signed. It then checks out the tag's commit in a
   temporary directory, builds `:app:assembleRelease` with R8 there, and checks the result:
   - R8 kept the four widget classes apart (each maps to itself in the mapping file), so every
     widget still draws its own picture;
   - the commit the Android Gradle Plugin records in `META-INF/version-control-info.textproto`
     is the tag's commit;
   - the certificate SHA-256 equals the expected one, and the APK is not signed with an Android
     debug key.

   Last, it runs the tag check again and requires the same tag object, so a tag moved or
   replaced during the build is refused. Only then does it write `dist/ducky-X.Y.Z.apk`,
   `dist/SHA256SUMS` and `dist/ducky-X.Y.Z-mapping.txt`.

   To check an APK the script did not just build (a download from the releases page, or a copy
   someone sent you), run `scripts/release.sh --verify path/to/ducky.apk`. It needs no keystore
   and no tag, only `unzip` and `apksigner` (the newest build-tools under `ANDROID_HOME` or
   `ANDROID_SDK_ROOT`, else the one on `PATH`), and stops at once, saying which is missing. It
   checks the signature and the certificate SHA-256 (the official one, or
   `DUCKY_EXPECTED_CERT`), refuses a debug-signed APK, and prints the commit the APK records,
   which you can compare with the release's tag.

   The mapping file is R8's map from the release build's shortened class and method names back
   to the source names. A crash report from a release install is unreadable without it, and
   every build renames differently, so only the mapping from the build you shipped will do. Keep
   it with your copy of the release (`dist/` is gitignored), and retrace a stack trace with
   `retrace` from the SDK's command-line tools:
   `retrace dist/ducky-X.Y.Z-mapping.txt stacktrace.txt`. It is not a release asset.
6. **Walk the release build.** Install the APK on an emulator with no debug build of Ducky and
   walk: add time, let a timer ring and stop it, add each of the four widgets to the home screen
   and check each draws its own picture, then Settings, About, Open-source notices. R8 runs in
   release builds only, so this walk is what catches a class it stripped or merged. Watch logcat
   for `FATAL EXCEPTION`, `ClassNotFound` and `NoSuchMethod`.
7. **Publish.** Create the release as a draft from the tag, attach the APK and `SHA256SUMS`
   (never the mapping file), paste the changelog section and the certificate SHA-256 into the
   notes, then publish. Give the certificate in both forms, the colon form above and the
   lowercase form `apksigner verify --print-certs` prints, and name
   `scripts/release.sh --verify ducky-X.Y.Z.apk` as the easy check:

   ```sh
   gh release create vX.Y.Z dist/ducky-X.Y.Z.apk dist/SHA256SUMS --draft \
     --title "Ducky X.Y.Z" --notes-file <notes.md>
   gh release edit vX.Y.Z --draft=false
   ```

   Ducky never goes online, so it tells nobody about a release; people find it through the
   releases page, a GitHub release watch, or an updater such as Obtainium.

## Testing the tag check

`tools/test-release-tag-check.sh` runs `scripts/release.sh` on scratch repositories with
throwaway SSH and OpenPGP keys, a throwaway keystore and a stub build, and prints one line per
case: a correctly signed tag passes (also when the signer's git email differs from the principal
the file lists, when the key's line carries an options field, and when git config names the file
with `~/`); the same tag under the default pin, a lightweight tag, an unsigned tag, an
OpenPGP-signed tag (gpg is never run), a key the file does not list, a listed key that is not the
pinned one, a signed tag object made for another name, a tag moved during the build, a tag
signed by an unlisted key while git config points `gpg.ssh.program` at a verifier that calls
everything good, and a branch named like the tag (refused before any build) are each
refused with their own message and leave `dist/` unwritten. It needs git, ssh-keygen, gpg,
keytool and python3, works in a temporary directory with its own HOME (your keys, keyring and git
settings are never read), and exits non-zero if any case gives the wrong result. The Gradle gate
does not run it; run it after any change to `scripts/release.sh`:

```sh
tools/test-release-tag-check.sh
```

## CI

`.github/workflows/android.yml` runs the unit tests with coverage, the coverage gate, lint, a
check that neither manifest asks for a network permission, and a debug build on every push to
`main` and on pull requests. It has read-only permissions, pins every action to a commit, holds
no secrets and has no release job.
