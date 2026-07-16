# BikeTracker

An offline-first Android app for recording and reviewing bike rides. GPS tracking,
vector maps, and ride history — no API keys, no accounts. Ride data never leaves the
device: Android cloud backup and device-to-device transfer are disabled
(`allowBackup="false"` plus a full data-extraction-rules opt-out), so the only copies
are the timestamped ZIP backups you create yourself in Settings
(`Documents/BikeTracker`).

## Features

- **Recording** — GPS tracking via a foreground service (FusedLocationProvider) with
  Kalman filtering, accuracy/speed-jump rejection, and auto-pause on standstill. Rides
  are saved incrementally, so one interrupted by process death is recovered at the next
  launch. Pause/Resume and Stop controls live in the notification too.
- **Live screen** — large, sunlight-readable speed, distance, time, average speed,
  altitude, and clock. GPS warnings (weak / no signal) and an auto-pause banner appear in
  fixed slots so the layout never shifts.
- **Map** — MapLibre vector tiles (OpenFreeMap; one light style in both themes), a live
  position puck that rotates to the heading, an optional follow mode that rotates the map
  so the direction of travel points up, live speed and ride time in the top bar, tap-to-zoom
  buttons, and offline download of the last viewed area for use without internet.
- **Speed chart** — a pull-out panel on the Map tab plots smoothed GPS speed over distance
  or time for the live ride and rides opened from History; tapping or dragging pins a marker
  to the track and reads out that point's speed, distance, and clock time, with pinch-zoom
  and one-finger pan after a long-press. Tapping the map near the track moves the marker too.
- **History** — rides browsed through an expandable year → month → day tree, with
  rolling 7/30/365-day and all-time summaries and per-ride details (distance, time, pace,
  average and max speed, elevation gain, calorie estimate). Each ride opens its track on the
  Map tab; a per-ride ⋮ menu names/comments the ride, opens extended statistics, or exports
  GPX. The ride currently on the Map tab is marked with an inverted card.
- **Extended statistics** — a full-screen per-ride view: an elevation profile with a km
  axis, a histogram of time spent in each speed band, and figures for descent, min/max
  altitude, stops, and estimated calories, all computed from the ride's track points.
- **Records** — a Records button lists personal bests (longest ride by distance and by time,
  fastest average, top speed, biggest climb, best single day); each entry expands the History
  tree and scrolls to that ride or day.
- **GPX export** — a ride's track is written to a GPX file (segments split at pauses, the
  ride name and comment carried into the track) under `Documents/BikeTracker/GPX-export` and
  opened straight into the system share sheet.
- **Settings** — in-app language (English / Ukrainian / Russian) via per-app locales,
  light/dark/system theme, accent color, your weight for the calorie estimate, a Recording
  section to configure auto-pause (speed threshold, hold time, auto-save timeout), and full
  backup/restore to a timestamped ZIP.

## Tech stack

- Kotlin, Jetpack Compose (Material 3), Navigation Compose
- Room (+ KSP) for trips and track points
- FusedLocationProvider (`play-services-location`) for GPS
- MapLibre for vector maps
- Coroutines

Targets: `minSdk` 33 (Android 13), `targetSdk`/`compileSdk` 36, Java 17.
Application id: `xx.biketracker`.

## Project structure

```
app/src/main/java/xx/biketracker/
├── MainActivity.kt   # Scaffold, 4-tab navigation, About dialog
├── Common.kt         # shared constants and formatting helpers
├── tracking/         # live tracking screen, recording service, state
├── map/              # MapLibre map, offline area download
├── history/          # ride tree, summaries, ride detail, records, stats, GPX export
├── settings/         # language, theme, accent, backup/restore
├── data/             # Room database, entities, migrations
└── ui/               # theme, colors, type, shared UI components
```

Strings live in `app/src/main/res/values{,-uk,-ru}/`; English is the base locale.

## Building

Standard Gradle Android build. Helper scripts at the repo root wrap the common flows:

- `05-MakeDebug.sh` — build a debug APK
- `10-MakeRelease.sh` — build a signed release (needs a keystore config)
- `20-MakeTag.sh` / `21-PushTag.sh` — version tagging

### Verification

Before a release, run:

- `./gradlew :app:lintDebug` — must pass with zero errors
- `./gradlew :app:testDebugUnitTest` — JVM tests (GPS math, state, persistence, backup policy)
- `./gradlew :app:connectedDebugAndroidTest` — Room schema migration tests (needs a device/emulator)

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

## License

GPL-3.0 — see [LICENSE](LICENSE).
