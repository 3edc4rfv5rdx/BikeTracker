# Changelog

Newest entries on top.

## Unreleased

- The Start/Pause button goes grey and stops taking taps from the moment a ride is stopped until it has been written to the database, and keeps its usual colors the rest of the time.

- The start and finish of a track on the map are now marked at every zoom, and a ride that never got far enough to have a start direction is marked with a plain disc instead of being left without a start marker at all.

- An exported GPX now stays readable whatever a ride's name and note contain: characters XML forbids are dropped instead of written into the file, and a point with impossible coordinates is left out rather than exported as `NaN`.

- The start-direction arrow on the map now reads the direction from the first recorded segment only: a ride paused right after it began no longer points its arrow across the jump to wherever riding resumed.

- The lint script now pauses at the end instead of reporting a missing `delay` command.

- The debug and release build scripts now run from their own directory, so launching them from anywhere no longer misses the wrapper and starts a fresh build number.

- Cancelling an offline map download now takes effect while the area is still being prepared, instead of leaving the dialog stuck at 0% and starting the download anyway; deleting all areas no longer leaves a freshly created one behind.

## v0.6.20260804+175

- Dropped a null assertion the Map selection change made redundant, which the compiler warned about.
- A GPX holding several tracks is now named after the file rather than after its first track; all of its tracks are shown, still separated where the file separates them.
- The "track cut short" message now declines the point count properly in every language.
- The lint script now prints its findings when lint reports an error, instead of aborting before the report — the one run where the report matters most.
- The History tab's Today, Collapse-all and Records buttons no longer do nothing when tapped just as the ride list changes underneath them.
- Documented the ride column that has never been read (`Trip.avgGpsSpeedMps`) as dead schema in SPEC.md, with why removing it is not worth the migration.
- A ride shown on the Map tab, or open in the extended statistics, now follows the ride itself: renaming it shows through at once, and deleting it closes the view instead of leaving a ride on screen that no longer exists.
- Recording no longer claims a save failed when two of its periodic saves land out of order; they are now handed over one at a time, and an overtaken one is recognised as already stored.
- Smoothing a track for display now keeps each point's time, speed and segment flag instead of resetting them, so nothing read off a smoothed track can quietly come out wrong.
- Importing a GPX now offers GPX and XML files rather than everything on the device, turns down a file far too large to be a track, and reads it as a stream instead of holding the whole document twice over; a track past 100 000 points is shown cut short and says so.
- A GPX whose elements carry a namespace prefix now imports like any other file; it was refused outright before.
- A long ride no longer costs more battery the longer it gets: the Map tab re-derives only what each new fix added instead of the whole track and chart, and the chart builds nothing at all while it is pulled down.
- A ride that fails to open no longer leaves the tracker running invisibly behind it, and pressing anything but Stop while a ride opens from standby now returns to standby instead of shutting the tracker down.
- Start now says why it refused instead of doing nothing at all: a backup or restore in progress, or missing location permission, each get their own message, and a second refused tap is reported like the first.
- A ride's maximum speed is now the fastest it actually got somewhere over three seconds, so a single stray fix can no longer credit a bicycle with 90 km/h; it is read off the track alone, so a receiver reporting no speed at all still yields one, and it reads a little below an instantaneous peak.
- A ride's statistics no longer count a stretch as "stopped" just because the fixes reported no speed: a stretch whose positions kept covering ground is credited as riding, at the pace those positions imply, exactly as the tracker itself judges movement.
- A paused ride waiting for the GPS to come back before it auto-saves now gives up after fifteen minutes and saves anyway, instead of holding the tracker open indefinitely on a phone left without a signal.
- Setting auto-save to 0 now also calls off a save that is already waiting for the signal, not only one still counting down.
- An imported GPX is now labelled with the track's own name, falling back to the file's; the name of whoever exported the file can no longer end up on it.
- An imported GPX is now segmented only where the file itself says the recording stopped, so a track logged once every few minutes is drawn as the one ride it is instead of vanishing; a step nobody could have ridden still breaks the line, and no longer sets the speed chart's whole scale.
- Standby now recognises that you have set off again even where the signal is poor enough that fixes arrive tens of seconds apart; it could previously never confirm it, and shut down after half an hour instead of recording the ride.
- Fixes arriving tens of seconds apart no longer count as a break in the recording: a ride under a jammed signal now keeps its distance, its time and its drawn track instead of coming out empty, while a genuine outage past two minutes still adds nothing.
- A stored ride and an imported GPX track now end in a red dot marking the finish, shown at the same zooms as the start chevron; a live ride still ends at the puck.
- Zoomed out far enough that the track carries no direction chevrons, a single white chevron on a black disc now sits at its start, pointing the way the ride set off.
- A ride recovered after the app was killed mid-ride is now held to the same 200 m minimum as the auto-save.
- A ride auto-saved after a long pause is now only kept if it covered at least 200 m, so the few dozen metres a jammed receiver wanders while the bike stands still no longer land in the history as rides of their own.
- Standby now waits for a second fix to confirm you have left the spot before it opens a new ride, so a single spoofed jump can no longer split a ride in two.

## v0.5.20260803+156

- The unit-test runner now waits three seconds before exiting.
- A ride's maximum speed now also counts the pace of the recorded track, so a receiver that reports no speed at all can no longer leave the maximum below the ride's average.
- The track on the map is now marked with small chevrons pointing the way you rode.
- A ride's maximum speed is now only taken from fixes whose speed the recorded track backs up, so the tens of km/h a jammed receiver invents for a standing bike no longer become the ride's top speed.
- The ride timer now runs no further than the next fix is due, so with fixes tens of seconds apart it no longer climbs ten seconds and falls back on every one.
- Fixed two anchor-distance tests that moved their fix 200 m in one second, which the validator rightly called a jump.
- An auto-paused ride now resumes (and standby starts a new one) as soon as the rider has left the spot, even if the fixes report no speed at all — a jammed signal could previously leave a ride paused for the whole trip and then auto-save it as a few metres.
- Auto-pause no longer triggers while the recorded positions are still covering ground, however slow the fixes read.
- Fixed the build failing on a missing import in the tracking service.
- Auto-save can now be turned off by setting it to 0 minutes, so nothing but Stop ever ends a ride.
- The ride timer no longer runs on through a GPS outage and then jumps backwards when the signal returns; it stops where recording stops counting.
- Fixes with a horizontal accuracy of 25-50 m are now recorded instead of dropped, and shown as "Weak GPS signal"; only above 50 m is a fix discarded.
- Auto-pause no longer fires on the first fix after a gap in the signal, so a jammed or blocked GPS can't pause a moving bike.
- The long-pause auto-save now waits for the GPS to come back before closing the ride, so a lost signal alone can never end a recording.
- Recording now recovers from a bogus reference fix (GPS spoofing): a fix that contradicts a reference which has had no accepted successor for 30 s re-anchors the track instead of being rejected for hours.
- The auto-resume buzz is now 400 ms instead of 200, so it is felt once the bike is already moving.
- The History ride menu (⋮) now has a Delete action, with the same confirmation as the ride's summary dialog.
- Opening the History tab now scrolls to the ride currently shown on the Map (the highlighted row).
- Renamed the helper scripts 00-DebugWiFiConn.sh and 01-MakeDebug.sh to 02- and 03-.
- The map's ride label now uses the same name as the GPX track (rider's title, else a "yyyy-MM-dd HH:mm" stamp), shared with export/import.
- Top-bar action icons are now plain icons without the surrounding circle frame.
- Raised the default auto-pause hold time from 10 to 15 seconds.
- Speed-chart gridlines are now brighter (1.dp instead of a hairline) and add vertical lines at the X-axis ticks.
- The top-bar tab title no longer wraps letter-by-letter into a column when the actions are wide.

## v0.5.20260720+144

- Speed-chart panel height now tracks the actual window size (correct in split-screen/multi-window) instead of the whole display.
- Silenced two false-positive lint warnings with documented suppressions (intentional weekday-array caching, application-context offline region).
- Renamed the debug build script 05-MakeDebug.sh to 01-MakeDebug.sh.
- Fixed an off-by-one in a ride-stats test that expected four intervals from four points instead of three.
- Fixed the GPX export tests to check track-point times only, not the document's metadata time, which had made three of them fail.
- Added unit tests for elevation gain, route smoothing, the GPS Kalman filter, and duration/pace formatting, plus a lint runner (05-Lint.sh) and a unit-test runner (06-Test.sh) that print findings as text.
- Added unit tests for the timing helpers (monotonicStepMillis, isRecordingGap, isSegmentBoundary) and the ride formulas (avgSpeedMps, cyclingMet, caloriesKcal, haversineMeters).
- README Features now list the read-only GPX import.
- The Map tab can now import a GPX file to view its track (drawn in dark green) and speed chart read-only, without saving it; a top-bar button opens the file, and its name and a close button appear like an opened ride.
- README Recording section now covers the post-long-pause standby and auto-restart behaviour.
- GPX export now fails safely and cleans up if MediaStore publication doesn't complete, and stamps monotonic point times so a mid-ride clock change can't make exported timestamps run backward.
- History Week/Month/Year totals now re-anchor immediately on a timezone or clock change instead of waiting for the next midnight.
- Elevation ascent, descent, and the profile chart now reset at each recording boundary, so an altitude jump across a pause or outage no longer counts as a climb/descent or draws a vertical cliff.
- A ride auto-started from standby now waits for its draft and GPS registration before showing Recording, and falls back to standby on failure instead of a dead Recording state.
- Extended ride stats now use monotonic time, never count a pause/outage gap as a stop, and bucket brief low-speed motion so the speed zones sum to moving time.
- Dropped an always-false null check on non-null offline-region metadata, clearing a release compiler warning.
- Pinned the kotlinx-serialization BOM so the migration instrumentation tests no longer crash on a split serialization-core/json runtime.
- Auto-resume speed is now a shared derived helper, repairing the JVM test suite that referenced the removed threshold constant.
- Speed-chart time axis and scrubbing now use a monotonic elapsed-time clock, so a mid-ride wall-clock change can't distort riding time or the axis.
- Speed smoothing no longer bleeds across a pause or GPS outage: each recording segment is averaged on its own.
- A short pause with movement now correctly splits the track, chart, and distance instead of being drawn as continuous, via a persisted segment-boundary flag.
- Speed-chart X axis now keeps a bounded, round tick spacing on arbitrarily long rides instead of crowding labels.
- History summary now totals the current calendar Week, Month, and Year instead of rolling 7/30/365-day windows.
- The paused Stop button's light grey is now a shared named color.
- Exiting the app is no longer blocked during standby, and the map top-bar live stats hide there; ride-active checks share one helper.
- Stop pressed while the long-pause auto-save is still writing now ends tracking instead of dropping into standby.
- A draft that fails to open on a ride auto-started from standby now shows the save-failure banner instead of being silently ignored.
- Standby now keeps high-accuracy GPS at its slower interval, so movement detection can't be starved by speed-less network fixes.
- Fixed a rare race where a failed standby GPS registration could shut down a ride just started from standby.
- After a long pause auto-saves the ride, tracking now waits in standby (lighter GPS) and auto-starts a fresh ride when you set off again, shutting down only after 30 motionless minutes.
- The Stop button is now light red while recording, distinct from the paused-state orange Resume.
- The Stop button is now light grey while paused, set off from the orange Resume beside it.
- Auto-resume now gives a single buzz, distinct from the double buzz on auto-pause.
- Picking a point on the map now scrolls the zoomed speed chart to it, and zooming the chart keeps the blue marker in view instead of letting it slide out of the window.
- Map zoom buttons now keep the blue scrub dot on screen by zooming around it while it is visible.
- Speed chart labels its axes with units: km/h at the top-right, and km or min above the axis per the current mode.
- The ride ⋮ menu's background is now a higher-contrast surface so it stands out from the tinted rows.
- The ride ⋮ menu gained a Summary item that opens the ride details, the same as tapping the row.
- README Features refreshed to cover the speed chart, extended statistics, records, GPX export, ride name/comment, calorie estimate, and configurable auto-pause.

## v0.5.20260716+121

- Each ride can now be given a name and a comment from its ⋮ menu; the name leads the History row and ride details, the comment shows in the details, and both flow into the exported GPX as the track's name and description.
- Settings gained a Recording section with an Auto-pause dialog: toggle auto-pause and set its speed threshold, hold time, and the auto-save timeout; the tracker applies changes on the next fix, and auto-resume stays 1 km/h above the threshold.

- A Records button in History's top bar shows personal bests — longest ride, longest by time, fastest average, top speed, biggest climb, and best single day — each tappable: it expands the History tree and scrolls to that ride (the best day, to that day), where map/details/statistics are one tap away.
- Each History ride gained a ⋮ actions menu holding Statistics and Export GPX (the map keeps its own button next to it).
- Export GPX writes the ride's track to a GPX file, opened straight into the system share sheet (Strava, Komoot, email, …) and kept under Documents/BikeTracker/GPX-export; pauses split it into separate track segments.

- A chart button on each History ride opens a full-screen extended-statistics screen: an elevation profile with a km axis, a histogram of time spent in each speed band, and figures for descent, min/max altitude, stops, and estimated calories — all computed from the ride's track points; switching tabs or Back closes it.
- Settings gained a "Your weight" field, used for the ride's MET-based calorie estimate (defaults to 73 kg).
- History's day rows name the weekday by a three-letter abbreviation ("22 Sat", "22 пнд") instead of spelling it out.
- History marks the ride currently opened on the Map tab with an inverted card — near-black on the light theme, near-white on the dark one — so it is no longer lost among the identically purple rows.
- The speed chart's long-press hand-over to pan mode buzzes through the vibrator at full strength, so it is unmistakable and works on phones with the system's touch-feedback setting turned off.
- Speed chart controls sit further apart on the readout row, so the round buttons no longer crowd each other.

## v0.4.20260714+103

- Speed chart controls moved into a fold-out menu behind a button on the readout line: it unfolds the distance/time axis pick and stepwise zoom buttons in-line along that row so they never cover the plot, closes when the plot is touched, and drops the low-contrast purple for high-contrast discs readable in sunlight; the chart also pans by one finger after a long-press.
- Speed chart: the X axis is raised off the bottom and carries round-step distance/time tick marks that adapt to the zoom window, the scrub readout adds the riding time at the point, and the axis toggle is two round buttons stacked at the chart's right edge.
- Tapping the map near the track selects the nearest track point: the marker lands on the track and the speed chart shows that point's cursor and figures.
- While scrubbing the chart, the map pans to keep the marker dot on screen (never while the map is being dragged by hand).
- Speed chart: the plot is inset from the screen edges with the visible window's ends captioned in the corners, the axis-toggle icons are larger and darker, and the X axis pinch-zooms up to 10x with two-finger panning (one finger keeps scrubbing).
- Bottom tab bar is 64 dp instead of the stock 80, returning the difference to the map and chart.
- The Map tab gained a speed chart panel, pulled between hidden and a third of the screen by its handle: smoothed GPS speed over distance or time (toggle), where tapping or dragging pins a marker to the track and shows that point's speed, distance, and clock time — for the live ride and rides opened from History alike.
- Map follow mode: a compass button keeps the camera on the current fix and rotates the map so the direction of travel points up.
- Live speed and moving time of the active ride are shown in the Map tab's top bar.
- Starting a new ride clears a History ride from the Map tab, so the live track is visible right away.
- The GPS-trouble banner no longer flashes when a fix's timestamp is slightly ahead of the UI's coarser clock.
- History summary labels are Week/Month/Year again (the windows stay rolling 7/30/365 days), and a full-contrast rule separates them from the all-time total.
- Room schema migrations are covered by instrumentation tests that run the real migration path over the exported schema JSONs; README documents the pre-release verification workflow.
- Tracking screen: the average-speed label drops its unit so the RU/UK captions fit on one line.
- History summaries are defined as rolling 7/30/365-day windows, re-anchor themselves at every local midnight (DST-aware) so trips age out even when the screen stays open across days, and the labels now name the exact spans instead of Week/Month/Year.
- The map centers on every new live ride again: centering identity is now the ride's start timestamp instead of a once-per-composition flag, so a second ride no longer starts off-screen after the first one was viewed.
- Offline map downloads are now owned by a process-level manager: reopening the dialog reconnects to live progress, retrying resumes the matching incomplete region instead of piling up duplicates, transient network errors are left to MapLibre's own retries with an explicit Cancel instead of a forced failure, Delete-all cancels an active download first, and the dialog reports complete and incomplete areas separately.
- Platform backup is fully disabled to match the offline privacy promise: allowBackup is off and data-extraction rules exclude every domain from cloud backup and device transfer, so the location database can only leave the device as the user's own ZIP backup; a unit test guards the policy and README states the exact behavior.
- The Exit button now refuses to close the app while a ride is recording or paused, explaining that the ride must be saved or discarded first, as SPEC.md always required.
- Removed the ACCESS_BACKGROUND_LOCATION permission and its dead-end one-shot dialog: the location foreground service is always started from the visible Tracking screen, so while-in-use permission already keeps recording alive in background and with the screen off.
- Launcher icon: the adaptive foreground artwork is enlarged to 0.78 of the canvas.
- Removed the obsolete emulator start script.
- The map no longer draws a straight line across pauses or GPS outages: a track is split into recorded segments at time gaps, each segment is smoothed and drawn independently, and old rides get the same fix from their stored point times without any schema change.
- Android lint warnings and Compose hints are resolved across SDK guards, preferences, bitmap creation, state primitives, resources, adaptive icons, and the intentional portrait-only activity; dependency-update notices are isolated from the source-quality gate for separate migrations.
- Android lint errors are fixed at their source: Compose callbacks use configuration-aware resources, invariant app/language names are marked non-translatable, and location registration performs an immediate permission check with explicit security-failure handling.
- Tracking startup now becomes RECORDING only after both draft creation and Play Services location registration succeed; synchronous and asynchronous failures share one cleanup path that removes the empty draft, foreground notification, live state, and ride reservation while showing an actionable error.
- Draft persistence now advances its durable cursor only after a successful transaction, reconciles retries against stored point counts to prevent duplicates, preserves failed batches, and exposes a retry-or-discard error state instead of silently reporting a failed save as complete.
- GPS fixes are now fully validated before they can update live telemetry, the Kalman filter, route data, auto-pause, or auto-resume; invalid accuracy, coordinates, timestamps, reported speed, and coordinate jumps are rejected without disguising a stale GPS signal.
- Ride intervals, GPS outage detection, auto-pause debounce, live duration, and GPS staleness now use monotonic elapsed realtime, so system clock corrections cannot stall or jump tracking; persisted route order no longer depends on wall-clock timestamps.
- Batched Fused Location deliveries now process every fix oldest-to-newest instead of dropping all but the newest; UI state and the foreground notification are published once per batch.
- Database backup/restore now uses one maintenance gate shared with ride drafts, recovery, final saves, discards, and history deletion; backups copy a checkpointed write-frozen snapshot, partial MediaStore files are removed on failure/cancellation, and ride starts or stale destructive actions cannot race maintenance.
- Database restore is now cancellation-safe and atomic: backups are size/integrity/schema checked and migrated before use, the previous database is retained until Room opens the replacement, interrupted swaps roll back on the next launch, and leaving Settings no longer cancels an in-progress restore.
- Stop button: discard long-press slowed to 1.5 s, label is "Stop & save" centered on two lines, and the fill is a lighter grey in the dark theme.
- Tracking screen: the km/h unit joined the Speed caption above the digits, and the altitude block sits right under the stats with the same gap as between the stat rows.
- Fixed the app being killed by the system when opening the map early in a ride: framing a short route zoomed past the tile source's maxzoom, where MapLibre symbol layout multiplies street-label anchors by the overscale factor and allocates gigabytes; route centering is now capped at z16 and hand zoom at z19.
- Map arrow recolors via a layer property switch instead of a per-feature expression.
- MapLibre updated 11.8.1 to 11.13.5.
- Release builds are profileable by shell tools (heapprofd), for native-memory diagnostics.
- Paused-state orange on the tracking screen (auto-pause banner, Resume button) softened to match the paused map arrow.
- Map arrow shows ride state by color: orange while paused, red while the GPS signal is lost or weak.
- Top-bar clock on the Tracking tab enlarged to the stats size.
- Dialog buttons keep their labels on one line, so "Delete all" and "Download" no longer wrap.
- About button in Settings is an outlined icon button, matching the History top-bar actions.
- Ride date on the Map tab moved from a chip over the map into the top bar, in a lighter font.
- Wall clock moved into the Tracking top bar; altitude takes its wide slot, and a new "Total time" stat (elapsed including pauses) sits in the grid.
- "km/h" caption sits lower, clear of the speed digits.
- Launcher icon is adaptive now, so it fills the launcher circle instead of being shrunk onto a plate.
- New launcher icon: bike, route and location pin on a white disc (make_icon.py rebuilds it from the source artwork).
- Fixed a crash when opening the offline-map dialog right after launch, before the Map tab ever initialized MapLibre.
- Stop button gets an outline so its edge is visible against the background.
- History top-bar actions are outlined icon buttons: a calendar for Today, fold-up for collapse all.
- History top bar: a Today button jumps to today's branch and a collapse-all button folds the whole tree.
- History opens with today's branch expanded when there are rides today.
- Map follows the ride: when the arrow moves off the visible area, the map shifts to it at the current zoom, without disturbing manual panning.
- Removed the dark map style — it was unreadable; the map is always light regardless of theme.
- Fixed a crash when restoring a backup right after launch: restore now waits for the startup ride-recovery pass before closing the database.
- Dropped the 32-bit armeabi-v7a APK; minSdk 33 devices are all arm64.

## v0.3.20260710+65

- First public release.
- Added a project README and a GPL-3.0 license.

## v0.1.20260710+64

- About dialog uses the standard filled dialog button, matching the other dialogs.
- "Speed" caption above the speed digits, in the same slot the GPS banner uses.
- History "All time" summary renamed to "Total".
- About button in the Settings top bar showing the app version and build.
- Tracking screen rebuilt around fixed fields: the GPS and auto-pause banners sit in their own slots without overlapping the speed or shifting the layout, and the speed digits are trimmed of blank line-box space.
- Track smoothing for the map runs in the background, so long rides can no longer stutter the UI.
- Smoothed tracks keep their true start and finish points, so the live puck sits on the end of the drawn line.
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
