# Contributing to Ducky

Thanks for taking a look. Ducky is maintained by one person in spare time, so there is no
support promise and replies can take a while.

## Issues

Bug reports and ideas are welcome as [issues](../../issues/new/choose). For an alarm that did
not ring, say what the timer was set to, what the phone was doing (locked, in another app, in
battery saver) and the Android version. Security problems go through
[SECURITY.md](SECURITY.md), not a public issue.

## Pull requests

Pull requests are read. This public repository is a copy of a private working repository, so a
change that fits is applied there and carried back here as its own commit, credited to you in
the commit message. Small, focused changes with a test are the easiest to take. Before a larger
change, open an issue so we can agree on the shape first. There is no contributor agreement;
contributions are accepted under the [Apache License 2.0](LICENSE).

## Building and testing

You need JDK 21 and the Android SDK with platform 36. Point Gradle at the SDK with
`ANDROID_HOME` or `sdk.dir` in `local.properties`.

```sh
./gradlew assembleDebug
./gradlew createDebugUnitTestCoverageReport lintDebug
python3 scripts/coverage.py --min 95
```

CI runs the same commands on every pull request. Line coverage below 95% fails the build, and
so does any Kotlin compiler warning, in app or test code, or any lint warning (`app/lint.xml`
lists the few checks that are off, and why). The first
test run downloads Robolectric's Android runtime, a few hundred megabytes.

The launcher icons and the README images are generated: see [tools/](tools/README.md). Widget
and launcher-icon changes are checked on a minified release build too
(`./gradlew assembleRelease -PallowDebugSigning=true`), because R8 can merge or rename the
classes they depend on.

## What the code holds to

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains these rules and the reasons behind them.

- The system alarm decides when the timer rings; the saved end time decides what every screen
  shows. Nothing that draws the time can move or delay the alarm.
- Every surface that shows a ringing alarm offers exactly Stop and +1 min, and each of them acts
  only on the ring it was drawn for.
- Ducky requests no network permission, and the build fails if any APK would carry one
  (`checkDebugManifestPermissions`, `checkReleaseManifestPermissions`). A change that needs the
  network is a change to what the app is, and to its privacy policy.
- All user-facing text lives in `strings.xml`.
- Tests read as behavior: a test's name says what the app does. Fixtures are invented, never
  real accounts or personal data.
- Comments say why, for a stranger reading the code later.
- A refactor changes no behavior and lands apart from fixes.
