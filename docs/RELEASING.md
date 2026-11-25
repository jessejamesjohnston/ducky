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
- Never commit a keystore (`*.jks` and `*.keystore` are gitignored) and never put the password in
  a file, `local.properties`, a command line or a log.

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
   plus that version: for the tag `v0.28.0-mine` in step 3, `versionName` is `0.28.0-mine`.
2. Find your certificate's SHA-256. The script refuses an APK signed with any other certificate,
   and without this it expects the official one:

   ```sh
   keytool -list -v -keystore ~/keys/mykey.p12 | grep 'SHA256:'
   ```

3. Tag and build:

   ```sh
   git tag v0.28.0-mine
   DUCKY_KEYSTORE_PATH=~/keys/mykey.p12 \
   DUCKY_KEYSTORE_KEYALIAS=<your key's alias> \
   DUCKY_EXPECTED_CERT=<the SHA256 value from step 2> \
   scripts/release.sh v0.28.0-mine
   ```

The script asks for the password. Without a terminal, set `DUCKY_KEYSTORE_PASS` in the
environment instead. It uses that one password for the keystore and the key, so your key password
must equal your store password (keytool makes PKCS12 keystores that way). You can leave out
`DUCKY_KEYSTORE_KEYALIAS` when your keystore holds a single key, or a key named `ducky`. Before it
clones or builds anything, the script opens the keystore with that password, looks for the key
(the alias is matched without regard to case, as keystores do) and unlocks the key with the same
password. A wrong password or alias, or a key whose password differs from the keystore's, stops
it at once, without printing the password.

## Every release

1. **Version.** Raise `versionCode` by one and set `versionName` in `app/build.gradle.kts`. An
   installed copy refuses an update whose `versionCode` is not higher.
2. **Changelog.** Write the release's section in `CHANGELOG.md`.
3. **Green.** `./gradlew createDebugUnitTestCoverageReport lintDebug` and
   `python3 scripts/coverage.py --min 95`, the same checks CI runs.
4. **Tag.** Commit, then make a signed tag `vX.Y.Z` and push it. A tag starts no release build.
5. **Build.** `scripts/release.sh vX.Y.Z`. It clones the tag into a temporary directory, builds
   `:app:assembleRelease` with R8 there, and checks the result:
   - R8 kept the four widget classes apart (each maps to itself in the mapping file), so every
     widget still draws its own picture;
   - the commit the Android Gradle Plugin records in `META-INF/version-control-info.textproto`
     is the tag's commit;
   - the certificate SHA-256 equals the expected one, and the APK is not signed with an Android
     debug key.

   Only then does it write `dist/ducky-X.Y.Z.apk`, `dist/SHA256SUMS` and
   `dist/ducky-X.Y.Z-mapping.txt`.

   To check an APK the script did not just build (a download from the releases page, or a copy
   someone sent you), run `scripts/release.sh --verify path/to/ducky.apk`. It needs no keystore
   and no tag: it checks the signature and the certificate SHA-256 (the official one, or
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
   notes, then publish:

   ```sh
   gh release create vX.Y.Z dist/ducky-X.Y.Z.apk dist/SHA256SUMS --draft \
     --title "Ducky X.Y.Z" --notes-file <notes.md>
   gh release edit vX.Y.Z --draft=false
   ```

   Ducky never goes online, so it tells nobody about a release; people find it through the
   releases page, a GitHub release watch, or an updater such as Obtainium.

## CI

`.github/workflows/android.yml` runs the unit tests with coverage, the coverage gate, lint, a
check that neither manifest asks for a network permission, and a debug build on every push to
`main` and on pull requests. It has read-only permissions, pins every action to a commit, holds
no secrets and has no release job.
