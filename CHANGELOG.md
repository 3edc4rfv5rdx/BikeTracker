# Changelog

Newest entries on top.

## Unreleased

- Fixed rides being lost on stop: the save now finishes before the service shuts down, so it no longer races with cancellation.
- Prevented a duplicate ride when a manual Stop races with the pause auto-save.
- Fixed the pause auto-save racing with resume/recording: it now decides and stops on the main thread, avoiding a crash and lost ride.
- Fixed a late GPS fix after stop leaving the UI stuck in recording state and re-posting the dismissed notification.
- Fixed a foreground-timeout crash when Pause/Resume reached a freshly created service instance.
- Backup and restore are refused while a ride is active, so they can no longer corrupt the database mid-save.

- Ride dialog: replaced average GPS speed with elevation gain (total ascent).

- Tracking screen: clock seconds render smaller than the HH:mm.
- Exit button now shows only on the Tracking tab; the top bar title reflects the current tab.
- History tree: tighter row spacing; ride cards sit at the day's indent and are tinted to read as buttons.
- Ride dialog: unit shown as a small caption under each value so labels no longer wrap awkwardly.
- Dialogs: filled (colored) buttons throughout; the ride dialog places Delete and OK in opposite corners.

- Ride details: added pace (min/km), average GPS speed, and elevation gain alongside average and max speed.
- History: ride details now open as a dialog (with Delete/OK) instead of a separate screen, so Back behaves predictably.
- History: Back collapses the most recently opened year/month/day node before leaving the screen.
- Database schema v2/v3: track points store GPS altitude, and trips store average GPS speed and elevation gain, all computed once at save (nullable, empty for older/imported rides); migrations preserve existing and backup data.
- History: rides are now browsed through an expandable year > month > day tree, each node showing its total distance and time; tap a day's ride to open it.
- Fixed backup writing an empty database: the WAL checkpoint cursor was never read, so it never ran and the write-ahead log was not flushed into the copied file.

- Settings screen: in-app language picker (English/Ukrainian/Russian, system default) via per-app locales, and light/dark/system theme.
- App-wide minimum text size of 18sp, and distinct window vs dialog/menu backgrounds; theme colors and sizes centralized in ui/Color.kt and ui/Type.kt.
- Raised minSdk to 33 (Android 13).
- Settings screen: backup the whole ride database to a timestamped ZIP in Documents/BikeTracker, and restore from one; restore validates the file and replaces all current data.
- Tracking screen: live distance now shows two decimals.
- History screen: week/month/year/all-time distance and time summary, list of saved rides, tap opens ride details (distance, time, avg and max speed) with delete.
- Tracking screen: large sunlight-readable live stats (speed, distance, time, avg speed, altitude, clock with seconds), stable Start/Pause/Resume + Stop controls, screen kept awake during a ride.
- GPS recording via a foreground service: FusedLocationProvider with accuracy and speed-jump filtering, auto-pause on standstill, auto-save after a long pause.
- Room storage for trips and track points; rides saved on stop.
- Ukrainian and Russian localization with English as the default.
- Initial Gradle project scaffold: Compose + Room + osmdroid + FusedLocationProvider dependencies, 4-tab navigation shell (Tracking/Map/History/Settings), release scripts ported from myplayer.
