# BikeTracker

An offline-first Android app for recording and reviewing bike rides. GPS tracking,
vector maps, and ride history — no API keys, no accounts.

## Features

- **Recording** — GPS tracking via a foreground service (FusedLocationProvider) with
  Kalman filtering, accuracy/speed-jump rejection, and auto-pause on standstill. Rides
  are saved incrementally, so one interrupted by process death is recovered at the next
  launch. Pause/Resume and Stop controls live in the notification too.
- **Live screen** — large, sunlight-readable speed, distance, time, average speed,
  altitude, and clock. GPS warnings (weak / no signal) and an auto-pause banner appear in
  fixed slots so the layout never shifts.
- **Map** — MapLibre vector tiles (OpenFreeMap), dark style that follows the app theme, a
  live position puck that rotates to the heading, and offline download of the last viewed
  area for use without internet.
- **History** — rides browsed through an expandable year → month → day tree, with
  week/month/year/total summaries and per-ride details (distance, time, pace, average and
  max speed, elevation gain). Each ride opens its track on the Map tab.
- **Settings** — in-app language (English / Ukrainian / Russian) via per-app locales,
  light/dark/system theme, accent color, and full backup/restore to a timestamped ZIP.

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
├── history/          # ride tree, summaries, ride detail dialog
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

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

## License

GPL-3.0 — see [LICENSE](LICENSE).
