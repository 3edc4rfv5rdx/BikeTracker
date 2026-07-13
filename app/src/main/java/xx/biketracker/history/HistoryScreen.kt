package xx.biketracker.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xx.biketracker.DAY_MS
import xx.biketracker.R
import xx.biketracker.millisUntilNextMidnight
import xx.biketracker.avgSpeedMps
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.RideTotals
import xx.biketracker.data.Trip
import xx.biketracker.formatClock
import xx.biketracker.formatDayLabel
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatMonthName
import xx.biketracker.formatSpeedKmh
import java.util.Calendar

@Composable
fun HistoryScreen(onShowRideOnMap: (Trip) -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).tripDao() }

    val trips by remember { dao.observeTrips() }.collectAsState(initial = emptyList())
    val years = remember(trips) { groupByDate(trips) }

    // Tapping a ride opens its details as a dialog over this screen (no navigation, so Back
    // just dismisses the dialog). The Trip already carries every figure the dialog shows.
    var selectedTrip by remember { mutableStateOf<Trip?>(null) }

    // Rolling 7/30/365-day windows (the labels name the exact spans). The anchor re-arms at
    // every local midnight so trips age out even when this screen stays alive across a day
    // boundary; day granularity keeps the Room flows from being recreated more often than the
    // windows can actually change. A timezone change is picked up at the next rollover (or by
    // recomposition when the tab is revisited).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextMidnight(System.currentTimeMillis()))
            now = System.currentTimeMillis()
        }
    }
    val empty = RideTotals(0.0, 0, 0)
    val week by remember(now) { dao.observeTotals(now - 7 * DAY_MS) }.collectAsState(initial = empty)
    val month by remember(now) { dao.observeTotals(now - 30 * DAY_MS) }.collectAsState(initial = empty)
    val year by remember(now) { dao.observeTotals(now - 365 * DAY_MS) }.collectAsState(initial = empty)
    val all by remember { dao.observeTotals(0L) }.collectAsState(initial = empty)

    // Which year/month/day nodes are currently expanded, keyed by their stable node key.
    // Insertion order is expansion order, so the last entry is always the deepest open node.
    // Saveable so the open branches survive switching tabs (e.g. a ride sent to the Map).
    val expanded = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf<String>() }
    fun toggle(key: String) {
        if (expanded.remove(key)) {
            expanded.removeAll { it.startsWith("$key-") } // collapsing a node closes its descendants too
        } else {
            expanded.add(key)
        }
    }

    // Back collapses the most recently opened node before leaving the screen.
    BackHandler(enabled = expanded.isNotEmpty()) {
        expanded.removeAt(expanded.lastIndex)
    }

    // Open today's branch once, when the screen first shows data. Saveable, so returning to
    // the tab doesn't force the branch back open after a manual collapse.
    var autoOpenedToday by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(years) {
        if (!autoOpenedToday && years.isNotEmpty()) {
            autoOpenedToday = true
            todayNodeKeys(years)?.let { expanded.addAll(it.filterNot(expanded::contains)) }
        }
    }

    // The Today / Collapse-all top-bar buttons live in the activity; taps arrive as commands.
    LaunchedEffect(years) {
        HistoryCommands.commands.collect { command ->
            when (command) {
                HistoryCommands.Command.OPEN_TODAY -> todayNodeKeys(years)?.let {
                    expanded.clear()
                    expanded.addAll(it)
                }
                HistoryCommands.Command.COLLAPSE_ALL -> expanded.clear()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SummaryCard(week, month, year, all)
        }

        if (years.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        years.forEach { yearNode ->
            item(key = yearNode.key) {
                TreeRow(
                    level = 0,
                    label = yearNode.label,
                    distanceMeters = yearNode.distanceMeters,
                    movingTimeMillis = yearNode.movingTimeMillis,
                    expanded = yearNode.key in expanded,
                ) { toggle(yearNode.key) }
            }
            if (yearNode.key in expanded) {
                yearNode.months.forEach { monthNode ->
                    item(key = monthNode.key) {
                        TreeRow(
                            level = 1,
                            label = monthNode.label,
                            distanceMeters = monthNode.distanceMeters,
                            movingTimeMillis = monthNode.movingTimeMillis,
                            expanded = monthNode.key in expanded,
                        ) { toggle(monthNode.key) }
                    }
                    if (monthNode.key in expanded) {
                        monthNode.days.forEach { dayNode ->
                            item(key = dayNode.key) {
                                TreeRow(
                                    level = 2,
                                    label = dayNode.label,
                                    distanceMeters = dayNode.distanceMeters,
                                    movingTimeMillis = dayNode.movingTimeMillis,
                                    expanded = dayNode.key in expanded,
                                ) { toggle(dayNode.key) }
                            }
                            if (dayNode.key in expanded) {
                                items(dayNode.trips, key = { "t-${it.id}" }) { trip ->
                                    RideRow(
                                        trip = trip,
                                        onClick = { selectedTrip = trip },
                                        onShowOnMap = { onShowRideOnMap(trip) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTrip?.let { trip ->
        RideDialog(
            trip = trip,
            onDismiss = { selectedTrip = null },
            onDeleted = { selectedTrip = null },
        )
    }
}

// --- Date tree ---

private class YearNode(
    val key: String,
    val label: String,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val months: List<MonthNode>,
)
private class MonthNode(
    val key: String,
    val label: String,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val days: List<DayNode>,
)
private class DayNode(
    val key: String,
    val label: String,
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val trips: List<Trip>,
)

/**
 * Group trips (already newest-first) into a year > month > day tree, preserving that order at
 * every level via LinkedHashMap insertion order. Each node carries the summed distance and
 * moving time of the trips beneath it.
 */
private fun groupByDate(trips: List<Trip>): List<YearNode> {
    val cal = Calendar.getInstance()
    val years = LinkedHashMap<Int, LinkedHashMap<Int, LinkedHashMap<Int, MutableList<Trip>>>>()
    for (trip in trips) {
        cal.timeInMillis = trip.startTime
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        val d = cal.get(Calendar.DAY_OF_YEAR)
        years.getOrPut(y) { LinkedHashMap() }
            .getOrPut(m) { LinkedHashMap() }
            .getOrPut(d) { mutableListOf() }
            .add(trip)
    }
    return years.map { (y, months) ->
        val monthNodes = months.map { (m, days) ->
            val dayNodes = days.map { (d, dayTrips) ->
                DayNode(
                    key = "y$y-m$m-d$d",
                    label = formatDayLabel(dayTrips.first().startTime),
                    distanceMeters = dayTrips.sumOf { it.distanceMeters },
                    movingTimeMillis = dayTrips.sumOf { it.movingTimeMillis },
                    trips = dayTrips,
                )
            }
            MonthNode(
                key = "y$y-m$m",
                label = formatMonthName(days.values.first().first().startTime),
                distanceMeters = dayNodes.sumOf { it.distanceMeters },
                movingTimeMillis = dayNodes.sumOf { it.movingTimeMillis },
                days = dayNodes,
            )
        }
        YearNode(
            key = "y$y",
            label = y.toString(),
            distanceMeters = monthNodes.sumOf { it.distanceMeters },
            movingTimeMillis = monthNodes.sumOf { it.movingTimeMillis },
            months = monthNodes,
        )
    }
}

/** Expansion keys of today's year > month > day branch, or null when today has no rides. */
private fun todayNodeKeys(years: List<YearNode>): List<String>? {
    val cal = Calendar.getInstance()
    val yearKey = "y${cal.get(Calendar.YEAR)}"
    val monthKey = "$yearKey-m${cal.get(Calendar.MONTH)}"
    val dayKey = "$monthKey-d${cal.get(Calendar.DAY_OF_YEAR)}"
    val exists = years.firstOrNull { it.key == yearKey }
        ?.months?.firstOrNull { it.key == monthKey }
        ?.days?.any { it.key == dayKey } == true
    return if (exists) listOf(yearKey, monthKey, dayKey) else null
}

// --- Rows ---

/** One expandable node in the date tree; indentation and a chevron convey depth and state,
 *  the total distance and moving time beneath the node sit on the right. */
@Composable
private fun TreeRow(
    level: Int,
    label: String,
    distanceMeters: Double,
    movingTimeMillis: Long,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (level * 20).dp, top = 7.dp, bottom = 7.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            fontWeight = if (level == 0) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = "${formatKm(distanceMeters)} ${stringResource(R.string.unit_km)} · ${formatDuration(movingTimeMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (level == 0) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** A selectable ride under an expanded day: start time plus the trip's summary line, and a map
 *  button at the right edge that opens this ride's track on the Map tab. Indented to the day
 *  row's level (not the deeper text) and tinted so it reads as a button. */
@Composable
private fun RideRow(trip: Trip, onClick: () -> Unit, onShowOnMap: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 2.dp, bottom = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    text = formatClock(trip.startTime),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = tripSummaryLine(trip),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onShowOnMap, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = stringResource(R.string.history_show_on_map),
                )
            }
        }
    }
}

/** One-line "12.3 km · 1:05:00 · 18.4 km/h" — units appended here so strings stay clean. */
@Composable
private fun tripSummaryLine(trip: Trip): String {
    val km = stringResource(R.string.unit_km)
    val kmh = stringResource(R.string.unit_kmh)
    val distance = "${formatKm(trip.distanceMeters)} $km"
    val duration = formatDuration(trip.movingTimeMillis)
    val avg = "${formatSpeedKmh(avgSpeedMps(trip.distanceMeters, trip.movingTimeMillis))} $kmh"
    return "$distance · $duration · $avg"
}

@Composable
private fun SummaryCard(week: RideTotals, month: RideTotals, year: RideTotals, all: RideTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            // Three columns; the all-time total gets a full-width line below, where large
            // figures can't outgrow a narrow cell.
            Row {
                SummaryCell(R.string.summary_week, week, Modifier.weight(1f))
                SummaryCell(R.string.summary_month, month, Modifier.weight(1f))
                SummaryCell(R.string.summary_year, year, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.summary_all)}:  ",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "${formatKm(all.distanceMeters)} ${stringResource(R.string.unit_km)} · " +
                        formatDuration(all.movingTimeMillis),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SummaryCell(labelRes: Int, totals: RideTotals, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Text(
            text = formatKm(totals.distanceMeters),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.unit_km),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = formatDuration(totals.movingTimeMillis),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
