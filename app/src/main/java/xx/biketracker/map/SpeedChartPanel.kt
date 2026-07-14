package xx.biketracker.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.withTimeoutOrNull
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
/** Hard ceiling for zooming the X axis (pinch or buttons). */
private const val CHART_MAX_ZOOM = 10f
/** X-zoom factor of one +/- button tap, around the window center. */
private const val BUTTON_ZOOM_STEP = 1.5f
/** Horizontal plot inset: the line's start and end stay off the screen edges. */
private val PLOT_H_PAD = 12.dp
/** X-axis tick ladders (1-2-5-ish) and the most ticks a window may get. */
private val DISTANCE_TICK_STEPS_KM =
    doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
private val TIME_TICK_STEPS_MIN =
    doubleArrayOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 240.0)
private const val MAX_X_TICKS = 6

/**
 * One chart sample per route point, so a chart index is also an index into the route —
 * the scrub selection travels to the map as that index. [distanceMeters] and
 * [movingTimeMillis] are cumulative along the recorded track; like the trip totals, a
 * pause/outage gap adds nothing to either.
 */
internal class SpeedSample(
    val distanceMeters: Double,
    val timeMillis: Long,
    val movingTimeMillis: Long,
    val speedMps: Float, // smoothed for display
)

internal fun buildSpeedSamples(route: List<GeoPoint>): List<SpeedSample> {
    if (route.size < 2) return emptyList()
    val half = SPEED_SMOOTH_WINDOW / 2
    val samples = ArrayList<SpeedSample>(route.size)
    var distance = 0.0
    var movingMillis = 0L
    for (i in route.indices) {
        if (i > 0 && !isRecordingGap(route[i - 1].timeMillis, route[i].timeMillis)) {
            distance += haversineMeters(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
            movingMillis += (route[i].timeMillis - route[i - 1].timeMillis).coerceAtLeast(0L)
        }
        val from = max(0, i - half)
        val to = min(route.lastIndex, i + half)
        var sum = 0f
        for (j in from..to) sum += route[j].speedMps
        samples += SpeedSample(distance, route[i].timeMillis, movingMillis, sum / (to - from + 1))
    }
    return samples
}

/** Same discontinuity rule as [xx.biketracker.splitRouteSegments]: no points are recorded
 *  during a pause or GPS outage, so a long wall-time gap is a break, not riding. */
private fun isRecordingGap(prevTimeMillis: Long, timeMillis: Long): Boolean =
    prevTimeMillis > 0 && timeMillis > 0 && timeMillis - prevTimeMillis > GPS_STALE_MS

/**
 * Bottom panel of the Map tab: the ride's speed over distance or time. The handle strip
 * toggles the chart between hidden and a fixed third of the screen. One finger scrubs (the
 * picked point is reported as a route index so the map can mark it), a long-press hands the
 * finger over to panning the zoomed window, and two fingers pinch-zoom the X axis. The ⋮
 * button in the corner unfolds a strip with the axis pick and +/- zoom buttons. Works
 * identically for the live ride and a stored one.
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
    var menuOpen by remember { mutableStateOf(false) }

    // Pinch-zoom window over the X domain, as fractions of the whole ride. Keyed to the
    // track's identity (its first fix time), so another ride never inherits a stale zoom;
    // held here, not in the chart, so collapsing the panel keeps it.
    val trackKey = route.firstOrNull()?.timeMillis
    var viewStart by remember(trackKey) { mutableFloatStateOf(0f) }
    var viewWidth by remember(trackKey) { mutableFloatStateOf(1f) }
    fun applyZoom(factor: Float) {
        val (start, width) = zoomedView(viewStart, viewWidth, factor, focus = 0.5f, panFraction = 0f)
        viewStart = start
        viewWidth = width
    }

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
                // Readout line: figures, then the fold-out controls unfolding in-line to the
                // left of the ⋮ trigger, so they share this row and never cover the plot.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScrubReadout(
                        sample = scrubIndex?.let { samples.getOrNull(it) },
                        modifier = Modifier.weight(1f),
                    )
                    if (menuOpen) {
                        ChartMenuButton(
                            icon = Icons.Filled.Straighten,
                            label = stringResource(R.string.stat_distance),
                            active = axisDistance,
                        ) { axisDistance = true }
                        ChartMenuButton(
                            icon = Icons.Filled.Schedule,
                            label = stringResource(R.string.stat_time),
                            active = !axisDistance,
                        ) { axisDistance = false }
                        ChartMenuButton(
                            icon = Icons.Filled.Add,
                            label = stringResource(R.string.map_zoom_in),
                        ) { applyZoom(BUTTON_ZOOM_STEP) }
                        ChartMenuButton(
                            icon = Icons.Filled.Remove,
                            label = stringResource(R.string.map_zoom_out),
                        ) { applyZoom(1f / BUTTON_ZOOM_STEP) }
                    }
                    ChartMenuButton(
                        icon = Icons.Filled.MoreVert,
                        label = stringResource(R.string.chart_menu),
                        active = menuOpen,
                    ) { menuOpen = !menuOpen }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 4.dp),
                ) {
                    SpeedChart(
                        samples = samples,
                        axisDistance = axisDistance,
                        scrubIndex = scrubIndex,
                        onScrub = onScrub,
                        // Scrubbing collapses the strip, so the readout regains the full row.
                        onInteract = { menuOpen = false },
                        viewStart = viewStart,
                        viewWidth = viewWidth,
                        onViewChange = { start, width ->
                            viewStart = start
                            viewWidth = width
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Figures of the scrubbed point: "18.4 km/h · 5.2 km · 42:15 · 10:45" (riding time, clock). */
@Composable
private fun ScrubReadout(sample: SpeedSample?, modifier: Modifier) {
    val text = if (sample == null) "" else {
        "${formatSpeedKmh(sample.speedMps.toDouble())} ${stringResource(R.string.unit_kmh)} · " +
            "${formatKm(sample.distanceMeters)} ${stringResource(R.string.unit_km)} · " +
            "${formatDuration(sample.movingTimeMillis)} · " +
            formatClock(sample.timeMillis)
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
    )
}

/**
 * One round button of the chart's controls. Colors avoid the theme's low-contrast purple:
 * an inactive button is a high-contrast neutral disc (near-white on dark, near-black on
 * light) that stays readable in sunlight; the active axis is filled with the app's orange
 * accent instead.
 */
@Composable
private fun ChartMenuButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (active) AccentOrange else MaterialTheme.colorScheme.onSurface
    val content = if (active) Color.White else MaterialTheme.colorScheme.surface
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

/** Colors, text machinery, and localized bits the draw pass needs, bundled to keep it tidy. */
private class ChartStyle(
    val textMeasurer: TextMeasurer,
    val labelStyle: TextStyle,
    val gridColor: Color,
    val lineColor: Color,
    val dotColor: Color,
    val axisColor: Color,
)

/** What the current chart gesture is; decided once per gesture and never downgraded. */
private enum class ChartGesture { UNDECIDED, SCRUB, PAN, PINCH }

@Composable
private fun SpeedChart(
    samples: List<SpeedSample>,
    axisDistance: Boolean,
    scrubIndex: Int?,
    onScrub: (Int) -> Unit,
    onInteract: () -> Unit,
    viewStart: Float,
    viewWidth: Float,
    onViewChange: (Float, Float) -> Unit,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = ChartStyle(
        textMeasurer = textMeasurer,
        labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
        gridColor = MaterialTheme.colorScheme.outlineVariant,
        lineColor = AccentOrange,
        dotColor = ScrubBlue,
        axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val haptics = LocalHapticFeedback.current

    // Gestures read the freshest data without restarting: a live fix every 1.5 s must not
    // cancel a scrub, pan, or pinch in progress.
    val currentSamples by rememberUpdatedState(samples)
    val currentAxis by rememberUpdatedState(axisDistance)
    val currentViewStart by rememberUpdatedState(viewStart)
    val currentViewWidth by rememberUpdatedState(viewWidth)
    val currentOnInteract by rememberUpdatedState(onInteract)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val hPad = PLOT_H_PAD.toPx()
            fun plotWidth() = size.width - 2 * hPad
            fun scrubAt(x: Float) {
                scrubTo(
                    currentSamples, currentAxis, currentViewStart, currentViewWidth,
                    x - hPad, plotWidth(), onScrub,
                )
            }
            fun panBy(pixels: Float) {
                val (start, width) = zoomedView(
                    currentViewStart, currentViewWidth,
                    zoom = 1f, focus = 0.5f, panFraction = pixels / plotWidth(),
                )
                onViewChange(start, width)
            }

            // One finger scrubs; holding the first finger still past the long-press timeout
            // hands it over to panning the zoomed window instead (with a haptic tick). A
            // second pointer at any point turns the whole gesture into pinch-zoom/pan and
            // keeps it there, so losing one finger mid-pinch doesn't fling the scrub marker.
            awaitEachGesture {
                val down = awaitFirstDown()
                currentOnInteract() // any touch on the plot dismisses the fold-out strip
                var mode = ChartGesture.UNDECIDED
                var lastSingle = down.position

                // Decide the gesture within the long-press timeout.
                val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (mode == ChartGesture.UNDECIDED) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> mode = ChartGesture.PINCH
                            pressed.isEmpty() -> return@withTimeoutOrNull // quick tap
                            else -> {
                                val change = pressed.first()
                                lastSingle = change.position
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    mode = ChartGesture.SCRUB
                                    scrubAt(change.position.x)
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                if (decided == null) {
                    mode = ChartGesture.PAN
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                if (mode == ChartGesture.UNDECIDED) {
                    scrubAt(lastSingle.x) // plain tap
                    return@awaitEachGesture
                }

                // Run the decided gesture until all fingers lift.
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) mode = ChartGesture.PINCH
                    when {
                        mode == ChartGesture.PINCH && pressed.size >= 2 -> {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan.x != 0f) {
                                val focus = ((event.calculateCentroid().x - hPad) / plotWidth())
                                    .coerceIn(0f, 1f)
                                val (start, width) = zoomedView(
                                    currentViewStart, currentViewWidth,
                                    zoom, focus, pan.x / plotWidth(),
                                )
                                onViewChange(start, width)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        mode == ChartGesture.SCRUB && pressed.size == 1 -> {
                            val change = pressed.first()
                            if (change.positionChanged()) {
                                scrubAt(change.position.x)
                                change.consume()
                            }
                        }
                        mode == ChartGesture.PAN && pressed.size == 1 -> {
                            val change = pressed.first()
                            if (change.positionChanged()) {
                                panBy(change.position.x - change.previousPosition.x)
                                change.consume()
                            }
                        }
                    }
                    if (event.changes.none { it.pressed }) break
                }
            }
        },
    ) {
        if (samples.size < 2) return@Canvas
        drawSpeedChart(samples, axisDistance, scrubIndex, viewStart, viewWidth, style)
    }
}

/**
 * New (start, width) fractions of the X window after zooming by [zoom] around the screen
 * fraction [focus] and panning by [panFraction] (a plot-width fraction; dragging right
 * moves the window towards the ride's start).
 */
private fun zoomedView(start: Float, width: Float, zoom: Float, focus: Float, panFraction: Float): Pair<Float, Float> {
    val newWidth = (width / zoom).coerceIn(1f / CHART_MAX_ZOOM, 1f)
    val anchor = start + focus * width // domain fraction under the fingers stays under them
    val newStart = (anchor - focus * newWidth - panFraction * newWidth)
        .coerceIn(0f, 1f - newWidth)
    return newStart to newWidth
}

/** Map a plot-relative x (pixels) to the nearest sample of the visible window and report it. */
private fun scrubTo(
    samples: List<SpeedSample>,
    axisDistance: Boolean,
    viewStart: Float,
    viewWidth: Float,
    plotX: Float,
    plotWidth: Float,
    onScrub: (Int) -> Unit,
) {
    if (samples.size < 2 || plotWidth <= 0f) return
    val windowFraction = (plotX / plotWidth).coerceIn(0f, 1f)
    val fraction = (viewStart + windowFraction * viewWidth).toDouble()
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

/** X tick step in domain units (meters or millis) for the visible span: the largest ladder
 *  step is taken only when even it would overflow [MAX_X_TICKS]. */
private fun xTickStep(axisDistance: Boolean, windowSpan: Double): Double {
    return if (axisDistance) {
        val spanKm = windowSpan / 1000.0
        (DISTANCE_TICK_STEPS_KM.firstOrNull { spanKm / it <= MAX_X_TICKS }
            ?: DISTANCE_TICK_STEPS_KM.last()) * 1000.0
    } else {
        val spanMin = windowSpan / 60_000.0
        (TIME_TICK_STEPS_MIN.firstOrNull { spanMin / it <= MAX_X_TICKS }
            ?: TIME_TICK_STEPS_MIN.last()) * 60_000.0
    }
}

private fun DrawScope.drawSpeedChart(
    samples: List<SpeedSample>,
    axisDistance: Boolean,
    scrubIndex: Int?,
    viewStart: Float,
    viewWidth: Float,
    style: ChartStyle,
) {
    val w = size.width
    val h = size.height
    val hPad = PLOT_H_PAD.toPx()
    val plotWidth = w - 2 * hPad
    val topPad = 6.dp.toPx() // keeps the line's peak off the panel's controls
    val labelPad = 3.dp.toPx()

    // The X axis sits above a bottom band tall enough for a tick mark plus its label.
    val tickLen = 3.dp.toPx()
    val labelHeight = style.textMeasurer.measure(AnnotatedString("0"), style.labelStyle).size.height
    val axisY = h - (tickLen + labelHeight + 2f)

    // Y scale stays that of the whole ride, so pinch-zooming never rescales the curve.
    val maxKmh = samples.maxOf { it.speedMps } * MPS_TO_KMH
    val step = gridStepKmh(maxKmh)
    val yMaxKmh = max(ceil(max(maxKmh, 1.0) / step) * step, step)

    // Visible X window in domain units; both domains are non-decreasing over the samples.
    val t0 = samples.first().timeMillis
    val xDomain = if (axisDistance) samples.last().distanceMeters else (samples.last().timeMillis - t0).toDouble()
    val windowStart = viewStart * xDomain
    val windowSpan = (viewWidth * xDomain).coerceAtLeast(1e-9)
    fun domainOf(s: SpeedSample): Double =
        if (axisDistance) s.distanceMeters else (s.timeMillis - t0).toDouble()
    fun xOf(s: SpeedSample): Float =
        hPad + ((domainOf(s) - windowStart) / windowSpan * plotWidth).toFloat()
    fun yOf(speedMps: Float): Float =
        (axisY - (speedMps * MPS_TO_KMH / yMaxKmh) * (axisY - topPad)).toFloat()

    // Gridlines with their km/h value sitting just above each line.
    var grid = step
    while (grid <= yMaxKmh) {
        val y = yOf((grid / MPS_TO_KMH).toFloat())
        drawLine(style.gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        val label = style.textMeasurer.measure(AnnotatedString(grid.toInt().toString()), style.labelStyle)
        drawText(label, topLeft = Offset(labelPad, y - label.size.height - 1f))
        grid += step
    }

    // X axis with distance/time ticks at round steps of the visible window.
    drawLine(style.axisColor, Offset(0f, axisY), Offset(w, axisY), strokeWidth = 1.dp.toPx())
    val tickStep = xTickStep(axisDistance, windowSpan)
    var tick = ceil(windowStart / tickStep) * tickStep
    while (tick <= windowStart + windowSpan) {
        val x = hPad + ((tick - windowStart) / windowSpan * plotWidth).toFloat()
        drawLine(style.axisColor, Offset(x, axisY), Offset(x, axisY + tickLen), strokeWidth = 1.dp.toPx())
        val text = if (axisDistance) {
            formatKm(tick, decimals = if (tickStep < 1000.0) 1 else 0)
        } else {
            formatDuration(tick.toLong())
        }
        val label = style.textMeasurer.measure(AnnotatedString(text), style.labelStyle)
        val labelX = (x - label.size.width / 2f).coerceIn(0f, w - label.size.width)
        drawText(label, topLeft = Offset(labelX, axisY + tickLen + 1f))
        tick += tickStep
    }

    // The speed line, broken at recording gaps. Vertices are thinned to roughly one per
    // visible pixel column, but every consecutive pair is still checked so no gap is
    // skipped over; points outside the zoom window fall off the canvas edge harmlessly.
    val vertexStride = max(1, (samples.size * viewWidth / max(1f, plotWidth)).toInt())
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
        path, style.lineColor,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Scrub cursor: a vertical hairline with a dot on the curve, twinned with the map marker.
    val scrub = scrubIndex?.let { samples.getOrNull(it) } ?: return
    val x = xOf(scrub)
    if (x < hPad || x > w - hPad) return // scrubbed point currently outside the zoom window
    drawLine(style.axisColor, Offset(x, 0f), Offset(x, axisY), strokeWidth = 1.dp.toPx())
    val center = Offset(x, yOf(scrub.speedMps))
    drawCircle(style.dotColor, radius = 5.dp.toPx(), center = center)
    drawCircle(Color.White, radius = 5.dp.toPx(), center = center, style = Stroke(width = 1.5f.dp.toPx()))
}
