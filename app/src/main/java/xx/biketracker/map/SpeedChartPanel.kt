package xx.biketracker.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.GeoPoint
import xx.biketracker.MPS_TO_KMH
import xx.biketracker.R
import xx.biketracker.formatClock
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatSpeedKmh
import xx.biketracker.haversineMeters
import xx.biketracker.ui.AccentOrange
import xx.biketracker.ui.ScrubBlue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// --- Chart tuning ---
/** Centered moving-average window (points) over raw GPS speeds; per-fix speed is noisy. */
private const val SPEED_SMOOTH_WINDOW = 7
/** The expanded panel (handle included) is the screen height divided by this. */
private const val PANEL_SCREEN_DIVISOR = 3

/**
 * One chart sample per route point, so a chart index is also an index into the route —
 * the scrub selection travels to the map as that index. [distanceMeters] is cumulative
 * along the recorded track; like the trip totals, a pause/outage gap adds nothing.
 */
internal class SpeedSample(
    val distanceMeters: Double,
    val timeMillis: Long,
    val speedMps: Float, // smoothed for display
)

internal fun buildSpeedSamples(route: List<GeoPoint>): List<SpeedSample> {
    if (route.size < 2) return emptyList()
    val half = SPEED_SMOOTH_WINDOW / 2
    val samples = ArrayList<SpeedSample>(route.size)
    var distance = 0.0
    for (i in route.indices) {
        if (i > 0 && !isRecordingGap(route[i - 1].timeMillis, route[i].timeMillis)) {
            distance += haversineMeters(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        }
        val from = max(0, i - half)
        val to = min(route.lastIndex, i + half)
        var sum = 0f
        for (j in from..to) sum += route[j].speedMps
        samples += SpeedSample(distance, route[i].timeMillis, sum / (to - from + 1))
    }
    return samples
}

/** Same discontinuity rule as [xx.biketracker.splitRouteSegments]: no points are recorded
 *  during a pause or GPS outage, so a long wall-time gap is a break, not riding. */
private fun isRecordingGap(prevTimeMillis: Long, timeMillis: Long): Boolean =
    prevTimeMillis > 0 && timeMillis > 0 && timeMillis - prevTimeMillis > GPS_STALE_MS

/**
 * Bottom panel of the Map tab: the ride's speed over distance or time. The handle strip
 * toggles the chart between hidden and a fixed third of the screen. Dragging or tapping the
 * plot scrubs: the picked point is reported as a route index so the map can mark it, and its
 * figures show above the plot. Works identically for the live ride and a stored one.
 */
@Composable
fun SpeedChartPanel(
    route: List<GeoPoint>,
    expanded: Boolean,
    onToggle: () -> Unit,
    scrubIndex: Int?,
    onScrub: (Int) -> Unit,
) {
    // Cumulative distances and smoothing recompute over the whole track on every live fix;
    // off the main thread so a multi-hour ride can't jank the UI (same as the map smoothing).
    var samples by remember { mutableStateOf<List<SpeedSample>>(emptyList()) }
    LaunchedEffect(route) {
        samples = withContext(Dispatchers.Default) { buildSpeedSamples(route) }
    }

    var axisDistance by rememberSaveable { mutableStateOf(true) }
    val panelHeight = (LocalConfiguration.current.screenHeightDp / PANEL_SCREEN_DIVISOR).dp
    val chartLabel = stringResource(R.string.map_speed_chart)

    Surface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.height(panelHeight) else Modifier),
        ) {
            // Handle strip: the only control that stays when the chart is pulled down.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clickable(onClick = onToggle)
                    .semantics { contentDescription = chartLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
                )
            }
            if (expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScrubReadout(scrubIndex?.let { samples.getOrNull(it) }, Modifier.weight(1f))
                    AxisToggle(axisDistance) { axisDistance = it }
                }
                SpeedChart(
                    samples = samples,
                    axisDistance = axisDistance,
                    scrubIndex = scrubIndex,
                    onScrub = onScrub,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/** Figures of the scrubbed point: "18.4 km/h · 5.2 km · 10:45". */
@Composable
private fun ScrubReadout(sample: SpeedSample?, modifier: Modifier) {
    val text = if (sample == null) "" else {
        "${formatSpeedKmh(sample.speedMps.toDouble())} ${stringResource(R.string.unit_kmh)} · " +
            "${formatKm(sample.distanceMeters)} ${stringResource(R.string.unit_km)} · " +
            formatClock(sample.timeMillis)
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
    )
}

@Composable
private fun AxisToggle(axisDistance: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.height(30.dp)) {
        SegmentedButton(
            selected = axisDistance,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {},
        ) {
            Icon(
                Icons.Filled.Straighten,
                contentDescription = stringResource(R.string.stat_distance),
                modifier = Modifier.size(18.dp),
            )
        }
        SegmentedButton(
            selected = !axisDistance,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = stringResource(R.string.stat_time),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SpeedChart(
    samples: List<SpeedSample>,
    axisDistance: Boolean,
    scrubIndex: Int?,
    onScrub: (Int) -> Unit,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val scrubLineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = AccentOrange
    val dotColor = ScrubBlue
    val kmUnit = stringResource(R.string.unit_km)

    // Gestures read the freshest data without restarting: a live fix every 1.5 s must not
    // cancel a scrub drag in progress.
    val currentSamples by rememberUpdatedState(samples)
    val currentAxis by rememberUpdatedState(axisDistance)
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    scrubTo(currentSamples, currentAxis, offset.x, size.width.toFloat(), onScrub)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    scrubTo(currentSamples, currentAxis, change.position.x, size.width.toFloat(), onScrub)
                }
            },
    ) {
        if (samples.size < 2) return@Canvas
        drawSpeedChart(
            samples, axisDistance, scrubIndex,
            textMeasurer, labelStyle, gridColor, lineColor, dotColor, scrubLineColor, kmUnit,
        )
    }
}

private fun scrubTo(
    samples: List<SpeedSample>,
    axisDistance: Boolean,
    x: Float,
    width: Float,
    onScrub: (Int) -> Unit,
) {
    if (samples.size < 2 || width <= 0f) return
    val fraction = (x / width).coerceIn(0f, 1f).toDouble()
    val index = if (axisDistance) {
        nearestSampleIndex(samples, samples.last().distanceMeters * fraction) { it.distanceMeters }
    } else {
        val t0 = samples.first().timeMillis
        nearestSampleIndex(samples, t0 + (samples.last().timeMillis - t0) * fraction) { it.timeMillis.toDouble() }
    }
    onScrub(index)
}

/** Binary search over a monotonically non-decreasing axis value for the sample nearest [target]. */
private inline fun nearestSampleIndex(
    samples: List<SpeedSample>,
    target: Double,
    axisValue: (SpeedSample) -> Double,
): Int {
    var lo = 0
    var hi = samples.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (axisValue(samples[mid]) < target) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && target - axisValue(samples[lo - 1]) < axisValue(samples[lo]) - target) return lo - 1
    return lo
}

/** Y ceiling and gridline step (km/h) chosen so the chart gets 2–4 round-valued gridlines. */
private fun gridStepKmh(maxKmh: Double): Double = when {
    maxKmh <= 15 -> 5.0
    maxKmh <= 30 -> 10.0
    maxKmh <= 60 -> 20.0
    else -> 50.0
}

private fun DrawScope.drawSpeedChart(
    samples: List<SpeedSample>,
    axisDistance: Boolean,
    scrubIndex: Int?,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
    lineColor: Color,
    dotColor: Color,
    scrubLineColor: Color,
    kmUnit: String,
) {
    val w = size.width
    val h = size.height
    val topPad = 6.dp.toPx() // keeps the line's peak off the panel's controls
    val labelPad = 3.dp.toPx()

    val maxKmh = samples.maxOf { it.speedMps } * MPS_TO_KMH
    val step = gridStepKmh(maxKmh)
    val yMaxKmh = max(ceil(max(maxKmh, 1.0) / step) * step, step)

    // X mapping for the current axis; both domains are non-decreasing over the samples.
    val t0 = samples.first().timeMillis
    val xDomain = if (axisDistance) samples.last().distanceMeters else (samples.last().timeMillis - t0).toDouble()
    fun xOf(s: SpeedSample): Float {
        val v = if (axisDistance) s.distanceMeters else (s.timeMillis - t0).toDouble()
        return if (xDomain > 0) (v / xDomain * w).toFloat() else 0f
    }
    fun yOf(speedMps: Float): Float =
        (h - (speedMps * MPS_TO_KMH / yMaxKmh) * (h - topPad)).toFloat()

    // Gridlines with their km/h value sitting just above each line.
    var grid = step
    while (grid <= yMaxKmh) {
        val y = yOf((grid / MPS_TO_KMH).toFloat())
        drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        val label = textMeasurer.measure(AnnotatedString(grid.toInt().toString()), labelStyle)
        drawText(label, topLeft = Offset(labelPad, y - label.size.height - 1f))
        grid += step
    }

    // The speed line, broken at recording gaps. Vertices are thinned to roughly one per pixel
    // column, but every consecutive pair is still checked so no gap is skipped over.
    val vertexStride = max(1, samples.size / max(1, w.toInt()))
    val path = Path()
    var started = false
    var gapSinceLastVertex = false
    var lastVertex = -1
    for (i in samples.indices) {
        if (i > 0 && isRecordingGap(samples[i - 1].timeMillis, samples[i].timeMillis)) {
            gapSinceLastVertex = true
        }
        if (i != 0 && i != samples.lastIndex && i - lastVertex < vertexStride) continue
        val x = xOf(samples[i])
        val y = yOf(samples[i].speedMps)
        if (!started || gapSinceLastVertex) path.moveTo(x, y) else path.lineTo(x, y)
        started = true
        gapSinceLastVertex = false
        lastVertex = i
    }
    drawPath(
        path, lineColor,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // X extent caption in the bottom-right corner: total distance or total wall-clock span.
    val extent = if (axisDistance) {
        "${formatKm(samples.last().distanceMeters)} $kmUnit"
    } else {
        formatDuration(samples.last().timeMillis - t0)
    }
    val extentLabel = textMeasurer.measure(AnnotatedString(extent), labelStyle)
    drawText(
        extentLabel,
        topLeft = Offset(w - extentLabel.size.width - labelPad, h - extentLabel.size.height - labelPad),
    )

    // Scrub cursor: a vertical hairline with a dot on the curve, twinned with the map marker.
    val scrub = scrubIndex?.let { samples.getOrNull(it) } ?: return
    val x = xOf(scrub)
    drawLine(scrubLineColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
    val center = Offset(x, yOf(scrub.speedMps))
    drawCircle(dotColor, radius = 5.dp.toPx(), center = center)
    drawCircle(Color.White, radius = 5.dp.toPx(), center = center, style = Stroke(width = 1.5f.dp.toPx()))
}
