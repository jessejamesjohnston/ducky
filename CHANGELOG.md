# Changelog

All notable changes to Ducky are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the app's
`versionName`. Earlier versions were private test builds and were not published.

## [0.28.1] - 2026-11-28

### Changed

- Settings has the same spacing as Merriment Labs' other apps: the title sits beside the back
  arrow, every card has a label above it (the first is now "Alarm"), rows are more compact, and in
  a large window the column is up to 720dp wide with wider margins.
- With a keyboard, the focused control shows a 3dp ring in the accent color (or in the text color,
  where a pale accent wouldn't stand out), not only a darker fill.
- Interface text is set in Figtree, the typeface of Merriment Labs' other apps; the clock keeps
  each style's own face.
- On a Chromebook or Googlebook the window asks for about 1040 x 700dp, its caption bar takes the
  page color, and Ctrl+comma (Settings) appears in the system's keyboard shortcuts list.
- Before Android asks for notifications, Ducky says why and waits for Continue (or Not now). A
  setting to turn on in Android now reads "Open Settings", and a second ask "Allow notifications".
- Open-source notices lists each component as a row that opens a page of its license text, which
  can be selected and copied.
- The custom color dialog uses the standard dialog buttons.
- Settings says what Android's device backup copies.
- A new widget lands at 3 × 2, the size and layout its preview shows, instead of a one-row strip.
  It can still be resized down to the strip.
- Links in Settings, About open in a browser tab over Ducky on phones, foldables and tablets, and
  as a normal tab in your browser on a Chromebook or Googlebook. Where no browser offers in-app
  tabs, they open in the browser as before.
- The tagline on the About screen uses a curly apostrophe, as it does on the website and in the
  README.

### Fixed

- The minus buttons under Time buttons line up in one column whatever the values beside them.
- A widget's stopped clock (no timer, paused, or ringing) told accessibility services a time that
  kept counting while it showed 00:00 or the paused time. It now reports the time it shows.
- A paused widget showed one second less than the app, the notification and the screen reader
  said (04:48 against "4 minutes 49 seconds"). All of them now round a part second up.

## [0.28.0] - 2026-11-25

The first public release.

### Added

- Set a timer by adding time: +30s, +1m and +5m start the countdown on the first tap and add to
  it after that. The three amounts can be changed in Settings.
- The pond: the water level is the time left, and a rubber duck rides it down. Pause and the
  duck dozes; cancel and the pond refills. Tilt the phone and the water tilts, and the duck
  glides to the low side and bumps the edge. On timers longer than five minutes, a diver, a
  submarine or a lake monster now and then swims by under the duck. As the water passes the
  time, the duck keeps clear of the digits and the water's highlight line fades behind them, so
  the time and the hint under it stay easy to read.
- Four home-screen widgets with add-time buttons: Water tank, Lily pads, Bubbles and Egg timer.
  Settings, Add widget shows all four, with previews of the same size and layout.
- The alarm rings on the alarm volume and vibrates until you stop it: full screen over the lock
  screen, or dropping down from the top while you use the phone. The alarm screen, the
  notification, the app and the widgets all offer the same two choices, Stop and +1 min.
- A live countdown in the notification with Pause and Cancel, promoted to a Live Update on
  Android 16 and newer, with Ducky riding a two-tone water progress bar.
- An alarm-setup card when notifications or full-screen alarms are turned off, with a shortcut
  to the setting, and a Test alarm in Settings.
- Four styles, twelve colors or any color from a wheel, and a high-contrast mode. The water and
  the app icon follow your color.
- Layouts for phones, tablets, foldables and desktop windows; screen-reader labels with spoken
  times in the app and on the widgets; reduced motion holds the pond still.
- Settings, About: the maker, Source code, Privacy, Open-source notices, License, Releases, Help
  and Contact. Releases opens the GitHub releases page in the browser; Ducky itself has no
  internet permission and never checks for updates.
- No network permission of any kind. A manifest merge rule removes the network-state permission
  that WorkManager would add, and every build checks the merged manifest and fails if a network
  permission appears.
- Wording across the app follows one style: no closing period on a single sentence, an ellipsis
  only on something in progress, a middle dot or a comma to join two parts, American spelling and
  natural contractions. A unit test checks every string against these rules and fails the build
  on a break.
