package xx.biketracker.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import xx.biketracker.R
import xx.biketracker.avgSpeedMps
import xx.biketracker.formatClock
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatSpeedKmh
import kotlin.math.roundToInt

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
    val view = LocalView.current
    val keepScreenOn = snapshot.status != TrackingStatus.IDLE
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Ride time ticks every second locally between the ~1.5 s GPS updates.
    val liveMovingMs = snapshot.movingTimeMillis +
        if (snapshot.status == TrackingStatus.RECORDING) {
            (nowMillis - snapshot.updatedAtWall).coerceAtLeast(0L)
        } else {
            0L
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Big current speed — pure number, with the unit as a caption below.
        Text(
            text = formatSpeedKmh(snapshot.currentSpeedMps),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.unit_kmh),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(6.dp))

        StatRow(
            left = Stat(withUnit(R.string.stat_distance, R.string.unit_km), formatKm(snapshot.distanceMeters)),
            right = Stat(stringResource(R.string.stat_time), formatDuration(liveMovingMs)),
        )
        StatRow(
            left = Stat(
                withUnit(R.string.stat_avg_speed, R.string.unit_kmh),
                formatSpeedKmh(avgSpeedMps(snapshot.distanceMeters, snapshot.movingTimeMillis)),
            ),
            right = Stat(
                withUnit(R.string.stat_altitude, R.string.unit_m),
                snapshot.altitudeMeters?.roundToInt()?.toString() ?: "—",
            ),
        )

        Spacer(Modifier.height(24.dp))

        // Wall clock, centered just above the controls.
        StatCell(
            stat = Stat(stringResource(R.string.stat_clock), formatClock(nowMillis, withSeconds = true)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            valueSize = 44.sp,
        )

        Spacer(Modifier.weight(1f))

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
        )

        Spacer(Modifier.height(24.dp))
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

@Composable
private fun Controls(
    status: TrackingStatus,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopSave: () -> Unit,
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
        BigButton(primaryText, primaryAction, Modifier.weight(1f))
        BigButton(
            text = stringResource(R.string.btn_stop_save),
            onClick = onStopSave,
            modifier = Modifier.weight(1f),
            tonal = true,
            enabled = status != TrackingStatus.IDLE,
        )
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
) {
    val label: @Composable () -> Unit = {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    if (tonal) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(68.dp)) { label() }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.height(68.dp)) { label() }
    }
}

@Composable
private fun BackgroundPermissionDialog(onAllow: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.perm_bg_title)) },
        text = { Text(stringResource(R.string.perm_bg_text)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.perm_bg_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.perm_bg_skip)) }
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
private const val PREFS_NAME = "biketracker_prefs"
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
