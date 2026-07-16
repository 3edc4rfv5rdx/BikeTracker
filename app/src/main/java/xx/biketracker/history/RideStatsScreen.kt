package xx.biketracker.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.Trip
import xx.biketracker.formatDuration
import xx.biketracker.ui.AccentOrange
import xx.biketracker.ui.Stat
import xx.biketracker.ui.StatRow
import kotlin.math.roundToInt

/**
 * Full-screen extended statistics of one ride: elevation profile, a speed-zone histogram, and a
 * block of figures the details dialog omits. Everything is reduced from the ride's track points
 * ([computeRideStats]) on open — none of it lives on the [Trip] row — so the load happens once,
 * off the main thread. [onBack] and the system back gesture both close the screen; the hosting
 * activity also clears it when a bottom-bar tab is tapped.
 */
@Composable
fun RideStatsScreen(trip: Trip, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    var stats by remember(trip.id) { mutableStateOf<RideStats?>(null) }
    LaunchedEffect(trip.id) {
        val points = withContext(Dispatchers.IO) {
            AppDatabase.get(context).tripDao().getPoints(trip.id)
        }
        stats = withContext(Dispatchers.Default) { computeRideStats(points) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val current = stats
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (current.hasAltitude) {
                    SectionLabel("${stringResource(R.string.stat_altitude)}, ${stringResource(R.string.unit_m)}")
                    ElevationProfile(current)
                }

                SectionLabel(
                    "${stringResource(R.string.stats_speed_zones)}, ${stringResource(R.string.unit_kmh)}",
                )
                SpeedHistogram(current.speedZoneMillis)

                RideStatCells(current)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RideStatCells(stats: RideStats) {
    val meters = stringResource(R.string.unit_m)
    val dash = "—"
    val descent = Stat(
        stringResource(R.string.stat_descent),
        stats.descentMeters?.roundToInt()?.toString() ?: dash,
        meters,
    )
    val altRange = Stat(
        stringResource(R.string.stat_altitude_min_max),
        if (stats.hasAltitude) {
            "${stats.minAltitudeMeters!!.roundToInt()}–${stats.maxAltitudeMeters!!.roundToInt()}"
        } else dash,
        meters,
    )
    val stops = Stat(
        stringResource(R.string.stat_stops),
        if (stats.stopCount == 0) "0" else "${stats.stopCount} · ${formatDuration(stats.stoppedMillis)}",
    )
    StatRow(descent, altRange, stops)
}

// --- Elevation profile ---

private val PROFILE_HEIGHT = 140.dp

/** Altitude over distance: a filled line whose vertical scale spans exactly the ride's min-to-max
 *  altitude, with those two values labelled at the plot's edges. Recording gaps keep the same x
 *  (distance does not advance across them), so the line simply carries on. */
@Composable
private fun ElevationProfile(stats: RideStats) {
    val profile = stats.elevationProfile
    val minAlt = stats.minAltitudeMeters ?: return
    val maxAlt = stats.maxAltitudeMeters ?: return
    val lineColor = AccentOrange
    val fillColor = AccentOrange.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Box(modifier = Modifier.fillMaxWidth().height(PROFILE_HEIGHT)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (profile.size < 2) return@Canvas
            val topPad = 8f
            val h = size.height - topPad
            val w = size.width
            val maxDist = profile.last().distanceMeters.coerceAtLeast(1e-9)
            val span = (maxAlt - minAlt).coerceAtLeast(1e-6)
            fun xOf(d: Double) = (d / maxDist * w).toFloat()
            fun yOf(a: Double) = (topPad + (1.0 - (a - minAlt) / span) * h).toFloat()

            // Top and bottom reference lines (max and min altitude).
            drawLine(gridColor, Offset(0f, yOf(maxAlt)), Offset(w, yOf(maxAlt)), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, yOf(minAlt)), Offset(w, yOf(minAlt)), strokeWidth = 1f)

            val line = Path().apply {
                moveTo(xOf(profile.first().distanceMeters), yOf(profile.first().altitudeMeters))
                for (i in 1 until profile.size) lineTo(xOf(profile[i].distanceMeters), yOf(profile[i].altitudeMeters))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(xOf(profile.last().distanceMeters), topPad + h)
                lineTo(xOf(profile.first().distanceMeters), topPad + h)
                close()
            }
            drawPath(area, fillColor)
            drawPath(
                line, lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        // Min/max altitude captions, at the levels their reference lines sit.
        Text(
            text = maxAlt.roundToInt().toString(),
            modifier = Modifier.align(Alignment.TopStart),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = minAlt.roundToInt().toString(),
            modifier = Modifier.align(Alignment.BottomStart),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Speed histogram ---

private val HISTO_LABEL_WIDTH = 52.dp
private val HISTO_BAR_HEIGHT = 20.dp

/** Horizontal bars of moving time per speed bucket, each bar scaled to the busiest bucket and
 *  captioned with its duration. Built from plain layout, not a Canvas: it is just scaled boxes. */
@Composable
private fun SpeedHistogram(zoneMillis: LongArray) {
    val maxMillis = (zoneMillis.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in zoneMillis.indices) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = zoneLabel(i),
                    modifier = Modifier.width(HISTO_LABEL_WIDTH),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(HISTO_BAR_HEIGHT)
                        .background(trackColor, RoundedCornerShape(4.dp)),
                ) {
                    val fraction = (zoneMillis[i].toFloat() / maxMillis).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(HISTO_BAR_HEIGHT)
                                .background(AccentOrange, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (zoneMillis[i] > 0) formatDuration(zoneMillis[i]) else "",
                    modifier = Modifier.width(HISTO_LABEL_WIDTH),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Bucket caption from its bounds: "0–10", "10–20", … and the open top "30+". */
private fun zoneLabel(index: Int): String {
    val low = if (index == 0) 0 else SPEED_ZONE_BOUNDS_KMH[index - 1].toInt()
    return if (index == SPEED_ZONE_BOUNDS_KMH.size) "$low+"
    else "$low–${SPEED_ZONE_BOUNDS_KMH[index].toInt()}"
}
