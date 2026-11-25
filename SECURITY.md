# Security

## Reporting a problem

Please report security problems privately, not in a public issue: use GitHub's
[private vulnerability reporting](../../security/advisories/new) for this repository, or write
to hello@merrimentlabs.com. Say what you found, how to reproduce it, and which version
(Settings, About) you tested. You will get an answer as soon as I can manage; this is a
one-person project.

## What is in scope

Ducky requests no network permission of any kind and talks to no server, so its surface is small:

- Ducky's own broadcast receivers (the timer receiver and the four widget receivers) and the
  ringing service, all declared `exported="false"`: another app should not be able to start,
  stop or extend a timer.
- `MainActivity` and the twelve launcher aliases in front of it (one per icon color, one
  enabled at a time), which are exported because a launcher must start them: they open the
  timer screen and take no input from the intent.
- Four exported components that AndroidX libraries add to the merged manifest. Each requires a
  permission that only the system (and, for `DUMP`, `adb`) holds, so no app can reach them:
  - Glance's `GlanceRemoteViewsService`, which supplies widget list items to the launcher,
    requires `android.permission.BIND_REMOTEVIEWS`.
  - WorkManager's `SystemJobService`, which the system job scheduler starts, requires
    `android.permission.BIND_JOB_SERVICE`.
  - WorkManager's `DiagnosticsReceiver` and the profile installer's `ProfileInstallReceiver`
    require `android.permission.DUMP`.

  No other library component is exported.
- Links the app hands to other apps (Settings, About): fixed https pages and one `mailto:`
  address.
- Backups: Android may copy the timer, the settings and the screen hints in a device backup
  (`app/src/main/res/xml/data_extraction_rules.xml`).

## Supported versions

Only the latest release gets fixes.
