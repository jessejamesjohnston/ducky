# How Ducky works

Ducky is one Gradle module: Kotlin, Jetpack Compose for the app, Glance for the widgets,
DataStore for what it remembers. There is no dependency injection and no layering beyond what a
timer needs. This note is for someone about to change the code: the rules that keep the timer
correct, and why each one exists.

## What Ducky keeps

Everything is in the app's private storage. Nothing is written anywhere else, and nothing is sent
anywhere.

| File | Written by | What it holds |
|---|---|---|
| `files/datastore/timer.preferences_pb` | `TimerStore` (DataStore) | The running timer (end time, total, paused or ringing) and every setting |
| `shared_prefs/ducky_ui.xml` | `NotificationAsk`, `FullScreenTip` | Three screen hints: whether the notification permission has been asked for, whether "Not now" was chosen before it was, and when the full-screen alarm tip was hidden |
| `files/datastore/GlanceAppWidgetManager.preferences_pb`, `files/datastore/appWidgetLayout-<id>` | Glance | Which widget types are placed, and each placed widget's layout cache |
| `no_backup/androidx.work.workdb` | WorkManager, for Glance | Glance's widget-update jobs |
| `files/profileInstalled`, `files/profileinstaller_*` | AndroidX profile installer | Markers for the ahead-of-time compilation profile |
| `cache/`, `code_cache/` | Android and its libraries | Temporary files the system may clear at any time; never backed up |

`app/src/main/res/xml/data_extraction_rules.xml` lets Android copy all of these except
`no_backup/` and the caches in a device backup or a transfer to a new phone, which is Android's default.

## The two sources of truth

**The system alarm decides when the timer rings. The saved end time decides what every screen
shows.**

Adding time writes the new end time to DataStore, then schedules it with
`AlarmManager.setAlarmClock`, which fires on time in Doze and with the app closed. The pond, the
notification countdown and the four widgets only read the saved end time. None of them can move
or delay the alarm, and none of them keeps its own copy of the timer.

`TimerController` is the only writer. Every change goes DataStore, then AlarmManager, then the
notification, then a widget refresh request, in that order, so a crash part way through leaves
the alarm consistent with what was saved. Taps arrive from the app, from widget buttons and from
notification actions on different threads, so every change runs under one `Mutex`, as a
read-compute-write over a fresh snapshot. The mutex is not reentrant: public entry points take
it once and call private `*Locked` functions that never take it again.

## Ringing

At zero, `TimerReceiver` asks the controller to ring. The ring is saved first, then
`AlarmRingService` (a foreground service) plays the alarm sound on the alarm stream until the
user chooses.

- **Every surface that shows a ringing alarm offers exactly Stop and +1 min**: the full-screen
  alarm, the notification, the app and the widgets.
- **Each Stop and +1 min carries the ring it was drawn for** (`ringingSinceMillis`). The
  controller acts only if that is still the ring in progress, so a late tap on an old
  notification can never silence a newer alarm, and a doubled +1 min can't add two minutes.
- **No path clears a ringing state without silencing that ring.** A Cancel that arrives behind a
  ring stops the sound too. There is no unconditional "clear everything".
- If the service fails to start, a retry is armed five seconds later, and a retry that finds a
  saved ring with no service running starts the same ring session again.
- Settings, Test alarm is a real ring session, offered only when no timer is set.

## When the alarm was lost

Android can drop a scheduled alarm. Ducky re-derives everything from the saved end time when
that happens:

- **Reboot** (`BOOT_COMPLETED`): a running timer is re-armed. A deadline or ring that passed
  while the phone was off rings only if it is less than an hour old; older ones are cleared.
  Android 15 and newer also send `BOOT_COMPLETED` when a force-stopped app is opened again.
- **App update** (`MY_PACKAGE_REPLACED`): the same rule, after canceling every alarm the app
  holds, because an alarm that outlived an update can point at a component the new build no
  longer has. The launcher icon is put back on the user's color.
- **Wall-clock change** (`TIME_SET`): the deadline stays the truth (a timer set for 12:10 still
  fires at 12:10 on the new clock). Paused and ringing timers are left alone.
- **Opening the timer screen** re-arms a running timer. On Android 14 a force-stop removes the
  alarm but keeps its PendingIntent, and the next launch gets no `BOOT_COMPLETED`, so "the
  PendingIntent exists" does not prove the alarm is armed there.

## Widgets

Glance renders the four widgets (water tank, lily pads, bubbles, egg timer) from the same
DataStore file the app uses, through `TimerWidgetState`. There is no second copy of the state
and no separately collected flow. The widget's own Glance state location is a placeholder file,
so removing a widget can never delete the shared timer file.

- **Widget delivery is outside the controller's lock.** Rendering a widget is inter-process work
  in the launcher; it can be slow or fail. The controller only records that a render is wanted,
  and one job renders the newest saved state, so an old render can never overwrite a newer one.
  Broadcast receivers wait a few seconds for that render before finishing, so Android doesn't
  reclaim the process with the widget showing an old picture.
- **The display tick is display-only.** A non-waking alarm chain re-renders the widgets while a
  timer runs. It never wakes the device and stops itself when the timer stops. Each tick writes
  a heartbeat to DataStore (which recomposes a live widget session) and calls `updateAll`
  (which starts a stopped one); both are needed.
- **The clock is a system Chronometer in every state**, running or frozen, so the digits keep
  the same metrics through pause, resume and ringing.
- **Widgets step instead of flowing.** The tick can't fire more often than every 5 seconds, and a
  render can land up to about a second late. So the water drops in steps, the duck hops between
  pads, and bubbles pop or eggs hatch one at a time, with at most one item per 7 seconds.
  [docs/widgets/](widgets/README.md) has the design reasoning.
- **R8 and widget classes.** Glance tells widgets apart by their class, and R8 merges classes
  that differ only in a constructor argument. `app/proguard-rules.pro` keeps every widget class
  under its own name, and `scripts/release.sh` checks the R8 mapping. A widget change is checked
  on a minified build, not only a debug one.
- The widget provider keeps a 30-minute `updatePeriodMillis` and an `initialLayout`, so a
  widget whose first render was lost gets a system retry instead of "Can't load widget".

## The pond

The pond is drawn on a Compose canvas from `PondSim`, a small simulation advanced once per
frame. The water level is recomputed from the saved end time on every frame; only transitions
(a rise when you add time, the refill on cancel) are animated, as an offset that springs back to
zero. So the picture can never lag behind the real time.

The duck glides along the surface as a one-dimensional rigid body (`DuckGlide`): the tilt pulls
it, water drag slows it, and the pond's edges bounce it back with most of its speed taken away.
It runs in fixed 1/120 s steps with the frame time clamped, so it behaves the same at any frame
rate and never tunnels through a wall. Splashes, bubbles, ripples and Zs live in fixed pools in
`PondParticles`, so a frame allocates nothing. With animations turned off in Android's settings,
the pond holds still at the right level and the motion sensor is never read.

## Notifications

`TimerNotifications` builds the ongoing countdown and paused cards. On Android 16 and newer it
uses the platform `Notification.Builder` with a `ProgressStyle`, because the compat builder drops
the progress segment colors, and asks for promotion to a Live Update. Promoted notifications
must not be colorized, so the theme reaches them only as the accent color and the segment
colors.

## Launcher icon

The icon follows the user's color through twelve activity aliases, one enabled at a time. The
alias changes when the user leaves the app, never while it is open, because disabling the alias
a launch came through closes that launch. R8 renames helper classes, so component names are
always built from a manifest component, never from a class's runtime name. Every icon file is
generated by `tools/icons/icon_gen.py`.

## Tests

About 400 unit tests run on the JVM with JUnit and Robolectric: the controller's state changes
and ringing rules, boot, update and clock-change recovery, the pond's level and motion math,
widget layout and rendering at every size, launcher aliases and the license list. Compose
screens are tested with a manual clock, because the pond never goes idle. JaCoCo measures line
coverage over the Robolectric run, and CI fails under 95%.
