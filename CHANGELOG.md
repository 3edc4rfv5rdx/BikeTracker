# Changelog

Newest entries on top.

## Unreleased

- A GPS outage (tunnel, indoors) no longer inflates moving time and distance: a gap over 10 s starts a new segment.
- Offline map download reports failure after repeated network errors instead of hanging forever.
- Long-press Stop to end a ride without saving it, confirmed by a "Not saved" snackbar.
- Live position puck on the map: an arrow at the current fix that rotates to the heading of travel.
- Pause/Resume and Stop buttons in the tracking notification, controllable without opening the app.
- New recordings are Kalman-filtered: the track no longer zigzags and standstill jitter no longer inflates the distance.
- Tracks are smoothed for display (moving average + simplification) — old rides look better too.

- History summary: week/month/year in three columns, the all-time total on its own full-width line so large figures fit.
- GPS warnings over the speed: weak signal (fixes rejected by the accuracy filter) and no signal for over 10 s.
- Auto-pause announces itself: an orange banner over the speed and a double vibration.
- Manual pause is sticky (only Resume ends it); auto-pause resumes by itself above 3 km/h and triggers after 10 s of standstill (was 5).
- Start/Pause button turns orange with black text while paused, so the paused state is obvious.
- The track on the map is a fixed bright orange instead of the pale theme color.

- Dark theme now uses a dark vector map style, switching live with the theme.

- Map migrated to vector tiles (MapLibre + OpenFreeMap, no keys): labels keep a constant size at any zoom.
- Offline map in Settings: download the last viewed area for use without internet; delete downloaded areas there too.

- History keeps its expanded tree branches when switching tabs (e.g. after sending a ride to the Map).
- Map tiles are scaled to the screen density, so labels are readable on high-dpi displays.

- The screen no longer dims on the Map tab while a ride is active (same as on Tracking).
- Map: zoom in/out buttons next to the re-center one.

- History: a map button on each ride opens its track on the Map tab; a chip there returns to the live view.
- Map tab: current ride's track on OpenStreetMap (osmdroid, no keys), dark-theme tiles, re-center button.
- Rides are now saved incrementally during recording; a ride interrupted by process death is recovered at the next launch.
- Backup and restore are refused while a ride is active, so they can no longer corrupt the database mid-save.
- Fixed a foreground-timeout crash when Pause/Resume reached a freshly created service instance.
- Fixed a late GPS fix after stop leaving the UI stuck in recording state and re-posting the dismissed notification.
- Fixed the pause auto-save racing with resume/recording: it now decides and stops on the main thread, avoiding a crash and lost ride.
- Prevented a duplicate ride when a manual Stop races with the pause auto-save.
- Fixed rides being lost on stop: the save now finishes before the service shuts down, so it no longer races with cancellation.

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
