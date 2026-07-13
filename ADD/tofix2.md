# BikeTracker code audit: LLM fix prompts

Audit date: 2026-07-13. Paths are relative to the repository root. Each item below is a standalone prompt that can be given to an LLM. Priorities describe user impact, not implementation order.

This audit was compared against `ADD/tofix1.md`. Its fixed stop/callback race, fresh-service command crash, `stopping` reset, incremental draft design, and active-ride backup guard are not repeated here. Items 2, 6, and 7 are follow-up defects in those same subsystems; their prompts state what remains broken after the `tofix1` fixes.

## 1. FIXED: make database restore atomic and cancellation-safe

**Prompt:**

Audit and fix the restore path in `app/src/main/java/xx/biketracker/data/Backup.kt` and `app/src/main/java/xx/biketracker/settings/SettingsScreen.kt`. `SettingsScreen` launches `restoreDatabase()` from a `rememberCoroutineScope`, so changing tabs disposes the composable and can cancel the coroutine after `AppDatabase.closeAndReset()` or while `dbFile.outputStream()` is truncating and copying the only live database. The current validation only checks that two table names exist; an incompatible or corrupt SQLite file can pass, replace the live database, and then fail when Room reopens it. There is no rollback. The ZIP extraction also has no uncompressed-size limit.

Implement an atomic, recoverable restore protocol. Stage the candidate on the same filesystem as the database, enforce a reasonable compressed/uncompressed size limit, run `PRAGMA integrity_check`, validate `user_version` and the complete Room-compatible schema/migration path, and do not irreversibly remove the current database until the candidate has been proven usable. Keep a rollback copy until Room successfully opens the replacement; restore the original on every failure. Make the short close/swap/reopen section non-cancellable while preserving normal coroutine cancellation elsewhere. Move operation ownership to an activity/ViewModel or application-level coordinator so leaving Settings cannot abandon it. Prevent duplicate restore launches and expose a visible busy/result state. Preserve the existing rule that restore is refused during a ride. Add tests for cancellation at every phase, malformed schemas, unsupported newer versions, corrupt databases, oversized ZIP entries, copy failure, Room-open failure, rollback success, and a valid old-schema migration.

## 2. FIXED: close the residual backup race left after `tofix1` item 5

**Prompt:**

Fix database maintenance coordination across `app/src/main/java/xx/biketracker/data/Backup.kt`, `AppDatabase.kt`, `Recovery.kt`, `tracking/TrackingService.kt`, `history/RideDialog.kt`, and the Settings UI. `ADD/tofix1.md` item 5 was addressed by refusing backup/restore while `TrackingState` is active; preserve that guard, but do not mistake it for database serialization. `backupDatabase()` still checkpoints WAL and then copies the main file while Room remains open. A recovery update, history deletion, new draft creation, or other write can occur during the copy; the one-time state check also does not prevent a ride from starting after the check. Restore has the same check-then-act race. A failed MediaStore write also leaves a partial backup entry.

Introduce one process-wide maintenance coordinator with explicit idle/running state and mutual exclusion. Ensure backup obtains a database-consistent snapshot using a supported SQLite snapshot/backup strategy rather than checkpoint-then-copy of a writable live file. Serialize restore against recovery and every database writer, and atomically refuse new ride starts or destructive history actions while restore owns the maintenance lock. Do not hold a UI thread lock. Publish operation progress/state so Settings disables conflicting actions, and clean up partial MediaStore rows on failure or cancellation. Add concurrency tests that race backup/restore against recovery, ride start/finalization, and trip deletion, then open the produced database and verify integrity plus exact row/point counts.

## 3. FIXED: process every location in a `LocationResult`, not only the newest one

**Prompt:**

Fix `app/src/main/java/xx/biketracker/tracking/TrackingService.kt`. The location callback calls `result.lastLocation?.let(::onLocation)` and silently discards earlier fixes when Fused Location delivers a batch. `LocationResult.locations` is ordered oldest to newest. Dropping intermediate fixes undercounts curved routes and distance, loses track detail, distorts moving time and auto-pause behavior, and makes draft persistence incomplete.

Process every location in `result.locations` in chronological order on the service's serialized state thread. Stop processing immediately if shutdown begins, and keep the existing duplicate/non-monotonic timestamp protection. Avoid publishing and rebuilding the notification once per item in a large batch: update state for the batch and publish efficiently without changing the numerical result. Add deterministic tests with multi-fix batches, out-of-order/duplicate timestamps, a stop arriving during a batch, and a curved route where using only the last fix would measurably undercount distance.

## 4. FIXED: use monotonic time for ride intervals and pause timers

**Prompt:**

Replace wall-clock interval arithmetic in `app/src/main/java/xx/biketracker/tracking/TrackingService.kt`, `TrackingState.kt`, and `TrackingScreen.kt` with monotonic elapsed time. The service currently calculates `dt`, GPS-gap detection, and auto-pause debounce from `Location.time`, while the UI derives live durations from `System.currentTimeMillis()`. Automatic clock correction, a manual clock change, or a timezone-related wall-clock adjustment can make `dt <= 0` for a long period, create a false outage, jump displayed duration backward/forward, or prevent auto-pause.

Keep epoch milliseconds only for persisted `Trip.startTime`, `Trip.endTime`, `TrackPoint.time`, and human-readable clocks. Use `Location.elapsedRealtimeNanos` for intervals between fixes and `SystemClock.elapsedRealtime()` for service/UI timers. Add the necessary monotonic timestamps to the live snapshot without confusing them with persisted wall time. Define safe handling for a reboot or an invalid/missing elapsed timestamp. Add pure tests for backward and forward wall-clock jumps, normal monotonic progression, pause/resume, a long GPS gap, and duration display updates.

## 5. FIXED: reject untrusted fixes before they mutate live telemetry or resume a ride

**Prompt:**

Refactor GPS validation in `app/src/main/java/xx/biketracker/tracking/TrackingService.kt`. `onLocation()` currently writes `currentSpeedMps`, altitude, accuracy, and bearing before `recordLocation()` rejects poor-accuracy or implausible fixes. While paused, `maybeAutoResume()` uses that unvalidated speed directly. A single inaccurate fix can therefore display an absurd speed/altitude, rotate the puck, and auto-resume a ride. A coordinate-plausible fix with reported speed above `MAX_PLAUSIBLE_SPEED_MPS` is still stored in `TrackPoint.speedMps` and corrupts `avgGpsSpeedMps`; only the max-speed aggregate is guarded.

Create one explicit validation pipeline shared by recording and auto-resume. Validate completeness, finite coordinate/telemetry values, horizontal accuracy, monotonic timestamp, coordinate jump speed, and reported speed before any trusted live field, Kalman state, route point, auto-pause state, or persisted point is mutated. Decide and document whether weak fixes may update only a separate signal-quality field. Never auto-resume from an untrusted fix. Preserve the last trusted altitude/bearing rather than replacing them with rejected data. Add tests for inaccurate fixes, missing optional fields, NaN/infinite values, excessive reported speed with plausible coordinates, a GPS jump, and a valid resume fix.

## 6. FIXED: harden the draft design from `tofix1` item 4 against write failure

**Prompt:**

Harden incremental persistence in `app/src/main/java/xx/biketracker/tracking/TrackingService.kt`. `ADD/tofix1.md` item 4 introduced draft rows and chained flushes to survive process death; keep that design. The remaining defect is I/O failure handling: `draftTrip` and chained `lastFlushJob` run under a `SupervisorJob`, but their failures are not observed during normal recording. `flushedCount` advances before the database transaction succeeds, so a failed flush is treated as persisted. During final save, exceptions from `draft.await()`, a prior flush, or the final transaction reach `finally`, which resets the UI and stops the service as if saving succeeded. Disk-full, database corruption, or Room-close failures can silently lose the whole ride or its last batches.

Model persistence success/failure explicitly. Advance the durable point cursor only after a successful transaction, retain failed batches for retry/finalization, and make subsequent flushes respect a failed predecessor. A Stop-and-save action must report whether the commit succeeded; it must not present an idle/saved result indistinguishable from success. Preserve as much recoverable draft data as possible and provide a retry or clear error path without creating duplicate points. Ensure Discard remains intentional and deterministic. Add fault-injection tests for initial draft insert failure, middle flush failure, final transaction failure, disk-full behavior, retry, process death after a failed flush, and exactly-once point persistence.

## 7. FIXED: clean up GPS startup failure after the `tofix1` command fix

**Prompt:**

Fix startup failure handling in `app/src/main/java/xx/biketracker/tracking/TrackingService.kt`. This is not the fresh-instance Pause/Resume/Stop problem fixed by `ADD/tofix1.md` item 2. Here, a valid START command enters `startTracking()`, which creates the foreground notification, marks status RECORDING, starts the draft insert, and publishes state before location registration is known to have succeeded. `requestUpdates()` only catches a synchronous `SecurityException` and calls `stopSelf()`; it does not reset `TrackingState`, remove the foreground notification, or delete/cancel the empty draft. The returned Play Services `Task` can also fail asynchronously and is ignored. Permission revocation, disabled location prerequisites, or provider failure can leave stale RECORDING UI, an orphan draft, or an incorrect notification.

Treat service startup as a state transition that commits only after all required steps succeed. Observe both synchronous exceptions and asynchronous `requestLocationUpdates` task failure. Route every partial-start failure through one idempotent main-thread cleanup path that removes updates/foreground notification, resets live state, removes any empty draft after its insert resolves, and stops the correct service start. Surface a localized actionable error to the UI. Add tests for failure before foreground promotion, synchronous registration failure, asynchronous task failure, cancellation during draft creation, and a successful start.

## 8. FIXED (without migration: segments are derived from stored point-time gaps at display time): preserve route segment boundaries across pauses and GPS outages

**Prompt:**

Fix route geometry across discontinuities in `app/src/main/java/xx/biketracker/tracking/TrackingService.kt`, the Room entities/migrations, `Common.kt`, and `map/RouteMap.kt`. Manual/automatic pause and a `GPS_STALE_MS` outage set or treat `lastPoint` as disconnected for distance and moving time, but live/stored routes remain one flat point list. MapLibre therefore draws a straight line from the last pre-pause fix to the first post-resume fix, and from one side of a long outage to the other. Display smoothing also averages across those boundaries. This shows travel that the tracker explicitly did not record.

Represent segment boundaries explicitly in persisted and live route data, with a Room migration and backward-compatible behavior for old trips. Draw and smooth each segment independently, never synthesize a connecting line, and make future GPX export emit separate track segments. Keep distance/moving-time behavior unchanged. Add tests for a short manual pause with movement, automatic pause/resume, a long GPS outage, old-schema data, single-point segments, and rendering input containing multiple segments.

## 9. FIXED (permission removed as unnecessary: the location FGS always starts from a visible activity, so while-in-use suffices): implement a valid API 33+ background-location permission flow

**Prompt:**

Correct the permission UX in `app/src/main/java/xx/biketracker/tracking/TrackingScreen.kt` and related string resources/manifest declarations. The app targets API 36 and has minSdk 33, but the custom dialog's Allow button calls `RequestPermission(ACCESS_BACKGROUND_LOCATION)`. On Android 11+ the runtime dialog does not offer "Allow all the time"; users must enable it on the app's location settings page. The code nevertheless marks `asked_background_location=true` before knowing the result and never offers help again, so the Allow action is effectively a dead end. Recording also starts before this flow finishes.

First decide from current Android foreground-service rules whether this app genuinely needs `ACCESS_BACKGROUND_LOCATION` when a location foreground service is started from a visible activity. If it is unnecessary, remove the permission and misleading flow. If it is required for supported workflows, implement the documented API 33+ educational screen and explicit navigation to app location settings, use the localized system label from `getBackgroundPermissionOptionLabel()`, re-check permission in `onResume`, allow decline, and provide a repeatable Settings entry instead of a one-shot flag. Do not claim background capability that was not granted. Keep English, Ukrainian, and Russian resources synchronized and add permission-state tests.

## 10. FIXED: block Exit while a ride is active as required by the product behavior

**Prompt:**

Implement the active-ride exit guard in `app/src/main/java/xx/biketracker/MainActivity.kt`. The top-bar Exit button currently calls `finishAndRemoveTask()` unconditionally. This contradicts `SPEC.md`, which requires a warning and blocks exit until tracking is completed, and it is misleading because the foreground service can keep recording after the task disappears.

Observe `TrackingState.snapshot` at the app-shell level. When status is RECORDING or PAUSED, show a localized dialog explaining that the ride must be stopped/saved or discarded first, and do not remove the task. When idle, retain the current exit behavior. Also handle system Back/task-removal semantics consistently enough that the UI does not promise an exit guard only on one button. Keep all three locales synchronized and add Compose/state tests for idle, recording, paused, and rapid stop-then-exit transitions.

## 11. FIXED (policy: platform backup disabled entirely; explicit ZIP backup is the only data exit): align Android Auto Backup with the app's no-cloud privacy claim

**Prompt:**

Resolve the privacy/configuration mismatch between `README.md`, `app/src/main/AndroidManifest.xml`, and `app/src/main/res/xml/data_extraction_rules.xml`. The README says ride data has "no cloud", but `android:allowBackup="true"` plus empty `<cloud-backup />` and `<device-transfer />` sections enables default backup of almost all private app data, including the Room database containing precise location history. The app already provides explicit user-controlled ZIP backup.

Choose and document a deliberate policy. Prefer excluding the location database (and MapLibre caches) from cloud backup while optionally retaining appropriate device-to-device transfer and non-sensitive preferences, or disable platform backup entirely if that matches the product promise. Do not accidentally exclude the user's explicit MediaStore ZIP backups, which are outside app-private backup domains. Add a small manifest/resource verification test or CI check and update the README to state the exact behavior rather than an ambiguous "no cloud" claim.

## 12. FIXED: make offline-region failure, retry, and dialog lifecycle behavior correct

**Prompt:**

Refactor `app/src/main/java/xx/biketracker/map/OfflineMaps.kt` and `OfflineMapDialog.kt`. After five resource errors the code forces the region inactive even though MapLibre documents resource errors as potentially recoverable and already retries them with backoff/network restoration. The partial region is retained, but Retry always creates a new region with empty metadata rather than resuming the incomplete one, so repeated failures accumulate duplicate regions and storage. The operation state belongs to a disposable dialog; closing/reopening it forgets progress and permits another concurrent download. `countOfflineRegions()` also reports incomplete regions as downloaded areas.

Create a process-level offline-download manager that identifies regions with metadata, distinguishes complete/incomplete/active states, resumes the matching incomplete region, and prevents duplicate concurrent downloads for the same definition. Let MapLibre handle recoverable network errors; expose pause/cancel/retry explicitly and reserve failure for terminal conditions. Reconnect a reopened dialog to current status, report completed and partial regions accurately, and make Delete-all coordinate with active downloads. Add callback-driven tests for temporary offline periods followed by recovery, terminal tile-limit failure, dialog dismissal/reopen, retry without duplication, deletion during download, and process recreation.

## 13. FIXED: re-arm live-map centering for every new ride

**Prompt:**

Fix one-time centering in `app/src/main/java/xx/biketracker/map/MapScreen.kt` and `RouteMap.kt`. `RouteMap` stores `centeredOnce` with `remember(recenterKey)`, but the live route always passes `recenterKey = null`. After the first live ride has been centered, stopping it clears the route but does not reset `centeredOnce`; a later ride in the same composition is not framed when its first point arrives and may appear off-screen.

Give every live ride a stable identity (for example, a service-generated ride/session ID in `TrackingSnapshot`) and use it as the recenter key. Do not use route size or every GPS update as the key, because that would fight user panning. Preserve selected-history-trip behavior. Add Compose/state tests for two consecutive live rides, stop/start before the first fix, switching between a stored trip and the active ride, and manual panning during one ride.

## 14. Low: keep rolling history summaries current in long-lived UI sessions

**Prompt:**

Fix rolling summary windows in `app/src/main/java/xx/biketracker/history/HistoryScreen.kt`. The `now` value and the week/month/year DAO flows are created once with `remember`. If the app or saved History destination remains alive across a day boundary, old trips never age out of the 7/30/365-day windows until the composition is recreated. The labels imply current summaries, but their cutoff timestamps become stale.

Make the cutoff refresh at a well-defined boundary without re-querying every frame. Use lifecycle-aware periodic/day-boundary updates and `flatMapLatest` or equivalent to recreate the Room flows, and define whether these are rolling durations or calendar week/month/year totals. Match the implementation and labels to that definition. Use timezone-aware calendar calculations if calendar periods are chosen. Add tests with a fake clock for boundary rollover, timezone changes, leap year, and returning to a saved History tab after several days.

## 15. Build quality: make lint pass and add an actual test suite

**Prompt:**

Restore a meaningful build quality gate for this Android project. On the audited revision, `./gradlew :app:lintDebug` fails with 10 errors: Compose `LocalContextGetResourceValueCall` errors in `OfflineMapDialog.kt` and `SettingsScreen.kt`, plus missing-translation errors for intentionally shared `app_name` and language-name resources. `./gradlew :app:testDebugUnitTest` succeeds only as `NO-SOURCE`, so none of the GPS math, state transitions, Room migrations, backup/restore behavior, or date grouping is protected.

Fix the lint errors at their source: use configuration-aware Compose resource access (`stringResource`/`LocalResources`) and mark genuinely language-invariant strings non-translatable or define them consistently. Do not hide errors behind a blanket lint baseline or global suppression. Add focused JVM tests for pure tracking/formatting/route logic and date windows, Room migration/instrumentation tests for schemas 1 through the current version, and state/fault-injection tests for the critical service and backup/restore paths. Wire `lintDebug`, unit tests, and relevant instrumentation tests into the documented verification workflow. Keep `assembleDebug` and minified `assembleRelease` passing.
