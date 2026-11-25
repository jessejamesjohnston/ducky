# Security

## Reporting a problem

Please report security problems privately, not in a public issue: use GitHub's
[private vulnerability reporting](../../security/advisories/new) for this repository, or write
to hello@merrimentlabs.com. Say what you found, how to reproduce it, and which version
(Settings, About) you tested. You will get an answer as soon as I can manage; this is a
one-person project.

## What is in scope

Ducky requests no network permission of any kind and talks to no server, so its surface is small:

- Ducky's own broadcast receivers and the ringing service, all declared `exported="false"`:
  another app should not be able to start, stop or extend a timer. The APK also carries two
  AndroidX library receivers (WorkManager's diagnostics and the profile installer) that are
  exported but require the `android.permission.DUMP` permission, which only the system and
  `adb` hold.
- The launcher aliases and `MainActivity` behind them, which are exported because a launcher
  must start them: they open the timer screen and take no input from the intent.
- Links the app hands to other apps (Settings, About): fixed https pages and one `mailto:`
  address.
- Backups: Android may copy the timer, the settings and the screen hints in a device backup
  (`app/src/main/res/xml/data_extraction_rules.xml`).

## Supported versions

Only the latest release gets fixes.
