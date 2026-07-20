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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
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
import xx.biketracker.R
import xx.biketracker.millisUntilNextMidnight
import xx.biketracker.startOfMonthMillis
import xx.biketracker.startOfWeekMillis
import xx.biketracker.startOfYearMillis
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
import xx.biketracker.map.MapSelection
import java.util.Calendar

@Composable
fun HistoryScreen(onShowRideOnMap: (Trip) -> Unit, onShowRideStats: (Trip) -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).tripDao() }
    val scope = rememberCoroutineScope()
    val gpxShareTitle = stringResource(R.string.gpx_export)
    val gpxFailedMessage = stringResource(R.string.gpx_export_failed)

    // Read once per context (i.e. re-read after a locale change recreates the activity), so the
    // grouping isn't invalidated by the fresh array getStringArray hands back each recomposition.
    val weekdayNames = remember(context) { context.resources.getStringArray(R.array.weekday_short).toList() }

    val trips by remember { dao.observeTrips() }.collectAsState(initial = emptyList())
    val years = remember(trips, weekdayNames) { groupByDate(trips, weekdayNames) }

    // The ride currently shown on the Map tab, so its row can be told apart from the rest.
    val mappedTrip by MapSelection.trip.collectAsState()

    // Tapping a ride opens its details as a dialog over this screen (no navigation, so Back
    // just dismisses the dialog). The Trip already carries every figure the dialog shows.
    var selectedTrip by remember { mutableStateOf<Trip?>(null) }

    // Tapping "Edit" on a ride's menu opens the name/comment editor over the tree.
    var editingTrip by remember { mutableStateOf<Trip?>(null) }

    // The Records top-bar button (in the activity) toggles this; the dialog reduces over `trips`.
    var showRecords by remember { mutableStateOf(false) }

    // Opening a record scrolls the tree to a node once it has been expanded and laid out. The key
    // is the ride row ("t-<id>") or a day node; cleared after the scroll runs.
    val listState = rememberLazyListState()
    var scrollTargetKey by remember { mutableStateOf<String?>(null) }

    // Calendar Week/Month/Year totals: the cutoffs are the local-midnight starts of the current
    // calendar week, month, and year, so the labels name real periods rather than rolling spans.
    // The anchor re-arms at every local midnight (all three boundaries fall on a midnight), so the
    // windows advance even when this screen stays alive across a day, week, month, or year rollover;
    // day granularity keeps the Room flows from being recreated more often than they can change. A
    // timezone change is picked up at the next rollover (or by recomposition when the tab is revisited).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextMidnight(System.currentTimeMillis()))
            now = System.currentTimeMillis()
        }
    }
    val empty = RideTotals(0.0, 0, 0)
    val week by remember(now) { dao.observeTotals(startOfWeekMillis(now)) }.collectAsState(initial = empty)
    val month by remember(now) { dao.observeTotals(startOfMonthMillis(now)) }.collectAsState(initial = empty)
    val year by remember(now) { dao.observeTotals(startOfYearMillis(now)) }.collectAsState(initial = empty)
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
                HistoryCommands.Command.SHOW_RECORDS -> showRecords = true
            }
        }
    }

    // After a record expands its branch, scroll the freshly laid-out tree to the target node.
    LaunchedEffect(scrollTargetKey, years) {
        val key = scrollTargetKey ?: return@LaunchedEffect
        val index = orderedItemKeys(years, expanded).indexOf(key)
        if (index >= 0) listState.animateScrollToItem(index)
        scrollTargetKey = null
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "summary") {
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
                                        onMap = trip.id == mappedTrip?.id,
                                        onClick = { selectedTrip = trip },
                                        onShowStats = { onShowRideStats(trip) },
                                        onEdit = { editingTrip = trip },
                                        onExport = {
                                            launchGpxShare(context, scope, trip, gpxShareTitle, gpxFailedMessage)
                                        },
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

    editingTrip?.let { trip ->
        RideEditDialog(
            trip = trip,
            onDismiss = { editingTrip = null },
            onSaved = { editingTrip = null },
        )
    }

    if (showRecords) {
        // A record navigates to its ride in the tree: open the day's branch and scroll to the row
        // (or the day node, for the best-day total), leaving map/details/stats to the user there.
        fun openInTree(dayMillis: Long, targetKey: String) {
            showRecords = false
            expanded.clear()
            expanded.addAll(dayNodeKeys(dayMillis))
            scrollTargetKey = targetKey
        }
        RecordsDialog(
            trips = trips,
            onOpenRide = { trip -> openInTree(trip.startTime, "t-${trip.id}") },
            onOpenDay = { millis -> openInTree(millis, dayNodeKeys(millis).last()) },
            onDismiss = { showRecords = false },
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
private fun groupByDate(trips: List<Trip>, weekdayNames: List<String>): List<YearNode> {
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
                    label = formatDayLabel(dayTrips.first().startTime, weekdayNames),
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

/** The item keys the LazyColumn emits, in order, for the current [expanded] set — lets a node key
 *  be resolved to a scroll index. Mirrors the tree rendering: the summary card first, then each
 *  open year > month > day > ride node. */
private fun orderedItemKeys(years: List<YearNode>, expanded: List<String>): List<String> {
    val keys = ArrayList<String>()
    keys += "summary"
    for (yearNode in years) {
        keys += yearNode.key
        if (yearNode.key in expanded) for (monthNode in yearNode.months) {
            keys += monthNode.key
            if (monthNode.key in expanded) for (dayNode in monthNode.days) {
                keys += dayNode.key
                if (dayNode.key in expanded) for (trip in dayNode.trips) keys += "t-${trip.id}"
            }
        }
    }
    return keys
}

/** The year > month > day expansion keys for the day containing [millis], matching groupByDate's
 *  key scheme, so opening History onto an arbitrary day reuses the same tree nodes. */
private fun dayNodeKeys(millis: Long): List<String> {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val yearKey = "y${cal.get(Calendar.YEAR)}"
    val monthKey = "$yearKey-m${cal.get(Calendar.MONTH)}"
    val dayKey = "$monthKey-d${cal.get(Calendar.DAY_OF_YEAR)}"
    return listOf(yearKey, monthKey, dayKey)
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

/** A selectable ride under an expanded day: start time plus the trip's summary line, a map button
 *  and an overflow (⋮) menu with the rest of the ride's actions at the right edge. Indented to the
 *  day row's level (not the deeper text) and tinted so it reads as a button. The ride currently
 *  shown on the Map tab inverts to near-black on light / near-white on dark, so it stands out from
 *  the identically tinted rows around it. */
@Composable
private fun RideRow(
    trip: Trip,
    onMap: Boolean,
    onClick: () -> Unit,
    onShowStats: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (onMap) MaterialTheme.colorScheme.inverseSurface
            else MaterialTheme.colorScheme.primaryContainer,
        ),
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
                // A named ride leads with its name; the start time then joins the summary line so it
                // stays visible. Without a name the clock is the bold identity, as before.
                val title = trip.title?.takeIf { it.isNotBlank() }
                Text(
                    text = title ?: formatClock(trip.startTime),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = if (title != null) "${formatClock(trip.startTime)} · ${tripSummaryLine(trip)}"
                    else tripSummaryLine(trip),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onShowOnMap) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = stringResource(R.string.history_show_on_map),
                )
            }
            RideActionsMenu(onSummary = onClick, onShowStats = onShowStats, onEdit = onEdit, onExport = onExport)
        }
    }
}

/** Overflow menu at the right edge of a ride row: a Summary shortcut (same as tapping the row),
 *  statistics and GPX export (map has its own button; more actions land here as they arrive). The
 *  menu's container is a high tonal surface so it reads clearly against the tinted rows behind it. */
@Composable
private fun RideActionsMenu(onSummary: () -> Unit, onShowStats: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(end = 4.dp)) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.history_actions),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_summary)) },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                onClick = {
                    open = false
                    onSummary()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_stats)) },
                leadingIcon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                onClick = {
                    open = false
                    onShowStats()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ride_name_label)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    open = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gpx_export)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    open = false
                    onExport()
                },
            )
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
            // Rule separating the period columns from the all-time total; full-contrast
            // (black on light, white on dark) — the default hairline tint was invisible.
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 5.dp),
                thickness = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
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
