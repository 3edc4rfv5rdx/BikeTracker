package xx.biketracker.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import xx.biketracker.ui.DialogButton
import xx.biketracker.ui.KeepScreenOnWhile
import xx.biketracker.ui.PausedOrange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.PREFS_NAME
import xx.biketracker.R
import xx.biketracker.avgSpeedMps
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatSpeedKmh
import kotlin.math.roundToInt

/** Fixed height of the two empty fields around the speed: a status banner (~40dp: a 24sp line plus
 *  6dp padding top and bottom) with 2dp of slack above and below. Fixed so swapping the content
 *  inside (banner ↔ km/h) never shifts the layout. */
private val BANNER_FIELD_HEIGHT = 44.dp

/** Disables the extra font padding Android reserves above/below the glyphs. */
private val NO_FONT_PADDING = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Physically crops a single-line text box down to the visible glyphs: from the cap height at the
 *  top to the baseline at the bottom, dropping the font's ascent slack and descent. This is what
 *  actually removes the tall empty box around huge numbers — LineHeightStyle.Trim cannot, since
 *  that slack is the font's own ascent/descent, not line leading. [capHeightFraction] is the
 *  glyph height as a fraction of the font size (~0.72 for Roboto digits). */
private fun Modifier.cropToGlyphs(fontSize: TextUnit, capHeightFraction: Float = 0.72f) =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val baseline = placeable[FirstBaseline]
        if (baseline == AlignmentLine.Unspecified) {
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val top = (baseline - (fontSize.toPx() * capHeightFraction).roundToInt()).coerceAtLeast(0)
        val bottom = (placeable.height - baseline).coerceAtLeast(0)
        val height = (placeable.height - top - bottom).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, -top) }
    }

@Composable
fun TrackingScreen() {
    val context = LocalContext.current
    val snapshot by TrackingState.snapshot.collectAsState()

    var permissionDenied by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Recording works with foreground permission alone; this is best-effort. */ }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionDenied = false
            TrackingService.start(context)
            if (!hasBackgroundLocation(context) && !hasAskedBackground(context)) showBackgroundDialog = true
        } else {
            permissionDenied = true
        }
    }

    fun onStart() {
        if (hasFineLocation(context)) {
            TrackingService.start(context)
            if (!hasBackgroundLocation(context) && !hasAskedBackground(context)) showBackgroundDialog = true
        } else {
            foregroundLauncher.launch(foregroundPermissions())
        }
    }

    // Ticking wall clock, independent of GPS updates.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Keep the screen awake while a ride is active — readable in sunlight, no missed taps.
    KeepScreenOnWhile(snapshot.status != TrackingStatus.IDLE)

    // Ride time ticks every second locally between the ~1.5 s GPS updates.
    val liveMovingMs = snapshot.movingTimeMillis +
        if (snapshot.status == TrackingStatus.RECORDING) {
            (nowMillis - snapshot.updatedAtWall).coerceAtLeast(0L)
        } else {
            0L
        }

    // Total ride time including pauses: wall clock elapsed since the start.
    val liveTotalMs = if (snapshot.status != TrackingStatus.IDLE) {
        (nowMillis - snapshot.startTime).coerceAtLeast(0L)
    } else {
        0L
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val discardedMessage = stringResource(R.string.track_discarded)

    // GPS trouble: fixes rejected by the accuracy filter, or none arriving at all.
    val gpsAccuracy = snapshot.gpsAccuracyMeters
    val gpsStale = nowMillis - snapshot.updatedAtWall > GPS_STALE_MS
    val gpsTrouble = snapshot.hasGpsTrouble(nowMillis)

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top empty field: fixed height, so nothing below ever moves. Holds the "Speed" caption
        // (pinned right above the digits) by default, replaced in place by the GPS banner while
        // there is trouble — same slot, so nothing moves.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BANNER_FIELD_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (gpsTrouble) {
                StatusBanner(
                    if (gpsStale || gpsAccuracy == null) stringResource(R.string.gps_no_signal)
                    else stringResource(R.string.gps_weak),
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.onError,
                )
            } else {
                Text(
                    text = withUnit(R.string.stat_speed, R.string.unit_kmh),
                    style = MaterialTheme.typography.titleMedium.merge(NO_FONT_PADDING),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // Big current speed — pure number. The font's own leading above/below the digits is trimmed
        // so the visible glyphs sit tight against the fields, with just 2dp of slack.
        Text(
            text = formatSpeedKmh(snapshot.currentSpeedMps),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            style = NO_FONT_PADDING,
            modifier = Modifier
                .padding(vertical = 2.dp)
                .cropToGlyphs(120.sp),
        )

        // Bottom empty field: fixed height, empty by default (the unit lives in the caption
        // above the digits); the auto-pause banner appears here without shifting the layout.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BANNER_FIELD_HEIGHT),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (snapshot.status == TrackingStatus.PAUSED && snapshot.pausedAutomatically) {
                StatusBanner(
                    stringResource(R.string.track_auto_paused),
                    PausedOrange,
                    Color.Black,
                    Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Stats and clock, padded off the screen edges, with 2dp of slack above.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatRow(
                left = Stat(withUnit(R.string.stat_distance, R.string.unit_km), formatKm(snapshot.distanceMeters, decimals = 2)),
                right = Stat(stringResource(R.string.stat_time), formatDuration(liveMovingMs)),
            )
            StatRow(
                left = Stat(
                    withUnit(R.string.stat_avg_speed, R.string.unit_kmh),
                    formatSpeedKmh(avgSpeedMps(snapshot.distanceMeters, snapshot.movingTimeMillis)),
                ),
                right = Stat(stringResource(R.string.stat_total_time), formatDuration(liveTotalMs)),
            )

            // Altitude in the wide slot the wall clock used to occupy; the clock moved to the
            // top bar. The 2dp top padding matches the stat rows' own vertical padding, so the
            // gap above equals the gap between the two stat rows.
            StatCell(
                Stat(
                    withUnit(R.string.stat_altitude, R.string.unit_m),
                    snapshot.altitudeMeters?.roundToInt()?.toString() ?: "—",
                ),
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 8.dp),
            )
        }

        // Flexible field between the clock and the controls.
        Spacer(Modifier.weight(1f))

        // Controls pinned near the bottom, padded off the screen edges.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (permissionDenied) {
                Text(
                    text = stringResource(R.string.perm_denied),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Controls(
                status = snapshot.status,
                onStart = ::onStart,
                onPause = { TrackingService.pause(context) },
                onResume = { TrackingService.resume(context) },
                onStopSave = { TrackingService.stopAndSave(context) },
                onStopDiscard = {
                    TrackingService.discard(context)
                    scope.launch { snackbarHostState.showSnackbar(discardedMessage) }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp),
        )
    }

    if (showBackgroundDialog) {
        BackgroundPermissionDialog(
            onAllow = {
                showBackgroundDialog = false
                markAskedBackground(context)
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            onSkip = {
                showBackgroundDialog = false
                markAskedBackground(context)
            },
        )
    }
}

/** Full-width high-contrast status banner: bold text on a solid fill, readable in sunlight. */
@Composable
private fun StatusBanner(
    text: String,
    background: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    Text(
        text = text,
        color = textColor,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(background, shape)
            .padding(vertical = 6.dp),
    )
}

private data class Stat(val label: String, val value: String)

/** Composes a stat label with its unit, e.g. "Distance, km" — comma added here so the
 *  string resources stay punctuation-free. */
@Composable
private fun withUnit(labelRes: Int, unitRes: Int): String =
    "${stringResource(labelRes)}, ${stringResource(unitRes)}"

@Composable
private fun StatRow(left: Stat, right: Stat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        StatCell(left, Modifier.weight(1f))
        StatCell(right, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(stat: Stat, modifier: Modifier, valueSize: TextUnit = 42.sp) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stat.value,
            fontSize = valueSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Controls(
    status: TrackingStatus,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopSave: () -> Unit,
    onStopDiscard: () -> Unit,
) {
    // Two fixed buttons that never rearrange: a primary Start/Pause/Resume that morphs
    // with state, and a Stop that is always present (disabled while idle).
    val primaryText = when (status) {
        TrackingStatus.IDLE -> stringResource(R.string.btn_start)
        TrackingStatus.RECORDING -> stringResource(R.string.btn_pause)
        TrackingStatus.PAUSED -> stringResource(R.string.btn_resume)
    }
    val primaryAction = when (status) {
        TrackingStatus.IDLE -> onStart
        TrackingStatus.RECORDING -> onPause
        TrackingStatus.PAUSED -> onResume
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigButton(
            text = primaryText,
            onClick = primaryAction,
            modifier = Modifier.weight(1f),
            // Paused is easy to miss otherwise — flag it with the accent color.
            containerColor = if (status == TrackingStatus.PAUSED) PausedOrange else null,
        )
        // Tap saves; a long-press stops without saving. The transparent overlay carries both
        // gestures so the tonal button keeps its Material look and shaped ripple.
        Box(modifier = Modifier.weight(1f)) {
            BigButton(
                text = stringResource(R.string.btn_stop_save),
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                tonal = true,
                enabled = status != TrackingStatus.IDLE,
            )
            if (status != TrackingStatus.IDLE) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(ButtonDefaults.filledTonalShape)
                        .combinedClickable(onClick = onStopSave, onLongClick = onStopDiscard),
                )
            }
        }
    }
}

/** Large, high-contrast, filled button — sized for gloved taps and bright sunlight. */
@Composable
private fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color? = null,
) {
    val label: @Composable () -> Unit = {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    if (tonal) {
        // The tonal fill is close to the background; an outline makes the button read as one.
        val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.38f)
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            border = BorderStroke(2.dp, borderColor),
            modifier = modifier.height(68.dp),
        ) { label() }
    } else {
        val colors = if (containerColor != null) {
            ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = Color.Black)
        } else {
            ButtonDefaults.buttonColors()
        }
        Button(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier.height(68.dp)) { label() }
    }
}

@Composable
private fun BackgroundPermissionDialog(onAllow: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.perm_bg_title)) },
        text = { Text(stringResource(R.string.perm_bg_text)) },
        confirmButton = {
            DialogButton(stringResource(R.string.perm_bg_allow), onClick = onAllow)
        },
        dismissButton = {
            DialogButton(stringResource(R.string.perm_bg_skip), onClick = onSkip)
        },
    )
}

private fun foregroundPermissions(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocation(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

// We ask for background location at most once; after that the user manages it in system settings.
private const val KEY_ASKED_BACKGROUND = "asked_background_location"

private fun hasAskedBackground(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ASKED_BACKGROUND, false)

private fun markAskedBackground(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ASKED_BACKGROUND, true)
        .apply()
}
