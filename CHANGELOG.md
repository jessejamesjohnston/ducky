# Changelog

All notable changes to Ducky are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow the app's
`versionName`. Earlier versions were private test builds and were not published.

## [Unreleased]

## [0.28.0] - 2026-11-24

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
