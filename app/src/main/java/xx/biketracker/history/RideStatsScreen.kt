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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.biketracker.METERS_PER_KM
import xx.biketracker.R
import xx.biketracker.caloriesKcal
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.Trip
import xx.biketracker.distanceTickStepMeters
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.settings.AppSettings
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

    val weightKg by AppSettings.riderWeightKg.collectAsState()

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

                RideStatCells(current, caloriesKcal(trip.distanceMeters, trip.movingTimeMillis, weightKg))
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
private fun RideStatCells(stats: RideStats, caloriesKcal: Double) {
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
    val calories = Stat(
        stringResource(R.string.stat_calories),
        if (caloriesKcal > 0) caloriesKcal.roundToInt().toString() else dash,
        stringResource(R.string.unit_kcal),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatRow(descent, altRange)
        StatRow(stops, calories)
    }
}

// --- Elevation profile ---

private val PROFILE_HEIGHT = 150.dp
/** Most km ticks the profile's X axis may carry. */
private const val PROFILE_MAX_TICKS = 6

/** Altitude over distance: a filled line whose vertical scale spans exactly the ride's min-to-max
 *  altitude (both values captioned at their reference lines), over an X axis of round-step km
 *  ticks. A recording boundary (pause/outage) breaks the line into a separate segment, so an
 *  altitude jump the rider never rode is not drawn as a vertical cliff. */
@Composable
private fun ElevationProfile(stats: RideStats) {
    val profile = stats.elevationProfile
    val minAlt = stats.minAltitudeMeters ?: return
    val maxAlt = stats.maxAltitudeMeters ?: return
    val lineColor = AccentOrange
    val fillColor = AccentOrange.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)

    Canvas(modifier = Modifier.fillMaxWidth().height(PROFILE_HEIGHT)) {
        if (profile.size < 2) return@Canvas
        val w = size.width
        val topPad = 8f
        val tickLen = 3.dp.toPx()
        val labelHeight = textMeasurer.measure(AnnotatedString("0"), labelStyle).size.height
        val axisY = size.height - (tickLen + labelHeight + 2f)
        val plotH = axisY - topPad
        val maxDist = profile.last().distanceMeters.coerceAtLeast(1e-9)
        val span = (maxAlt - minAlt).coerceAtLeast(1e-6)
        fun xOf(d: Double) = (d / maxDist * w).toFloat()
        fun yOf(a: Double) = (topPad + (1.0 - (a - minAlt) / span) * plotH).toFloat()

        // Min and max altitude reference lines bound the plot band.
        drawLine(gridColor, Offset(0f, yOf(maxAlt)), Offset(w, yOf(maxAlt)), strokeWidth = 1f)
        drawLine(gridColor, Offset(0f, yOf(minAlt)), Offset(w, yOf(minAlt)), strokeWidth = 1f)

        // Draw each recording segment on its own, so the fill and line never bridge a boundary.
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        var from = 0
        while (from < profile.size) {
            var to = from + 1
            while (to < profile.size && !profile[to].segmentStart) to++
            val line = Path().apply {
                moveTo(xOf(profile[from].distanceMeters), yOf(profile[from].altitudeMeters))
                for (i in from + 1 until to) lineTo(xOf(profile[i].distanceMeters), yOf(profile[i].altitudeMeters))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(xOf(profile[to - 1].distanceMeters), axisY)
                lineTo(xOf(profile[from].distanceMeters), axisY)
                close()
            }
            drawPath(area, fillColor)
            drawPath(line, lineColor, style = stroke)
            from = to
        }

        // X axis with round-step km ticks (0 omitted; the axis line itself marks the origin).
        drawLine(axisColor, Offset(0f, axisY), Offset(w, axisY), strokeWidth = 1.dp.toPx())
        val step = distanceTickStepMeters(maxDist, PROFILE_MAX_TICKS)
        val decimals = if (step < METERS_PER_KM) 1 else 0
        var tick = step
        while (tick <= maxDist) {
            val x = xOf(tick)
            drawLine(axisColor, Offset(x, axisY), Offset(x, axisY + tickLen), strokeWidth = 1.dp.toPx())
            val label = textMeasurer.measure(AnnotatedString(formatKm(tick, decimals)), labelStyle)
            val labelX = (x - label.size.width / 2f).coerceIn(0f, w - label.size.width)
            drawText(label, topLeft = Offset(labelX, axisY + tickLen + 1f))
            tick += step
        }

        // Altitude captions sitting on their reference lines.
        drawText(textMeasurer.measure(AnnotatedString(maxAlt.roundToInt().toString()), labelStyle), topLeft = Offset(2f, yOf(maxAlt)))
        val minLabel = textMeasurer.measure(AnnotatedString(minAlt.roundToInt().toString()), labelStyle)
        drawText(minLabel, topLeft = Offset(2f, yOf(minAlt) - minLabel.size.height))
    }
}

// --- Speed histogram ---

/** Fixed side columns leave the bar (the weighted middle) shorter, and are wide enough that
 *  "10–20" and a "H:MM:SS" duration never wrap. */
private val HISTO_RANGE_WIDTH = 58.dp
private val HISTO_TIME_WIDTH = 68.dp
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
                    modifier = Modifier.width(HISTO_RANGE_WIDTH),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = false,
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
                    modifier = Modifier.width(HISTO_TIME_WIDTH),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
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
