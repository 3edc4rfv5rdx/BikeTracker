package xx.biketracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xx.biketracker.data.finalizeAbandonedTrips
import xx.biketracker.data.recoveryJob
import xx.biketracker.data.DatabaseRestoreCoordinator
import xx.biketracker.data.RestoreOperationState
import xx.biketracker.history.HistoryCommands
import xx.biketracker.history.HistoryScreen
import xx.biketracker.map.MapScreen
import xx.biketracker.map.MapSelection
import xx.biketracker.settings.AppSettings
import xx.biketracker.settings.SettingsScreen
import xx.biketracker.tracking.TrackingScreen
import xx.biketracker.tracking.TrackingState
import xx.biketracker.tracking.TrackingStatus
import xx.biketracker.ui.BikeTrackerTheme
import xx.biketracker.ui.DialogButton
import xx.biketracker.ui.NavLabelStyle

private sealed class Destination(val route: String) {
    data object Tracking : Destination("tracking")
    data object Map : Destination("map")
    data object History : Destination("history")
    data object Settings : Destination("settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        // Rescue any ride a process death left as an unfinished draft; the cutoff keeps a ride
        // started right after launch out of reach.
        recoveryJob = lifecycleScope.launch {
            finalizeAbandonedTrips(this@MainActivity, startedBefore = System.currentTimeMillis())
        }
        enableEdgeToEdge()
        setContent {
            // Observing the theme here re-colors the whole app the moment it changes.
            val themeMode by AppSettings.themeMode.collectAsState()
            val restoreState by DatabaseRestoreCoordinator.state.collectAsState()
            LaunchedEffect(restoreState) {
                when (restoreState) {
                    RestoreOperationState.Succeeded -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.backup_restored),
                            Toast.LENGTH_LONG,
                        ).show()
                        DatabaseRestoreCoordinator.acknowledgeResult()
                        recreate()
                    }
                    RestoreOperationState.Failed -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.backup_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                        DatabaseRestoreCoordinator.acknowledgeResult()
                    }
                    RestoreOperationState.Idle,
                    RestoreOperationState.Running -> Unit
                }
            }
            BikeTrackerTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BikeTrackerApp(onExit = { finishAndRemoveTask() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikeTrackerApp(onExit: () -> Unit) {
    val navController = rememberNavController()
    val tabs = listOf(Destination.Tracking, Destination.Map, Destination.History, Destination.Settings)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTab = tabs.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }
    // The exit button lives only on the Tracking tab; other tabs leave the slot empty.
    val onTrackingTab = currentTab == Destination.Tracking

    // The About button lives only on the Settings tab.
    var showAbout by remember { mutableStateOf(false) }

    // Exit is blocked while a ride is active: the task would disappear yet the foreground
    // service would keep recording, which reads as either a lost ride or a stuck app.
    val trackingSnapshot by TrackingState.snapshot.collectAsState()
    var showExitBlocked by remember { mutableStateOf(false) }
    val rideActive = trackingSnapshot.status != TrackingStatus.IDLE
    fun onExitRequested() {
        if (rideActive) showExitBlocked = true else onExit()
    }

    // Shared by the bottom bar and History's "show on map": switch tabs, keeping their state.
    fun navigateTo(tab: Destination) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = (currentTab ?: Destination.Tracking).labelRes())) },
                navigationIcon = {
                    if (onTrackingTab) {
                        IconButton(onClick = ::onExitRequested) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.action_exit))
                        }
                    }
                },
                actions = {
                    if (onTrackingTab) {
                        TopBarClock()
                    }
                    if (currentTab == Destination.Map) {
                        // The ride opened from History is named here, off the map itself.
                        val selectedTrip by MapSelection.trip.collectAsState()
                        selectedTrip?.let { trip ->
                            Text(
                                text = "${formatDate(trip.startTime)} · ${formatClock(trip.startTime)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            IconButton(onClick = { MapSelection.clear() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.map_close_ride))
                            }
                        }
                    }
                    if (currentTab == Destination.History) {
                        TopBarButton(Icons.Default.Today, stringResource(id = R.string.history_today)) {
                            HistoryCommands.send(HistoryCommands.Command.OPEN_TODAY)
                        }
                        TopBarButton(Icons.Default.UnfoldLess, stringResource(id = R.string.history_collapse_all)) {
                            HistoryCommands.send(HistoryCommands.Command.COLLAPSE_ALL)
                        }
                    }
                    if (currentTab == Destination.Settings) {
                        TopBarButton(Icons.Default.Info, stringResource(id = R.string.about_title)) {
                            showAbout = true
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateTo(tab) },
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        label = { Text(stringResource(id = tab.labelRes()), style = NavLabelStyle) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Tracking.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Tracking.route) { TrackingScreen() }
            composable(Destination.Map.route) { MapScreen() }
            composable(Destination.History.route) {
                HistoryScreen(
                    onShowRideOnMap = { trip ->
                        MapSelection.select(trip)
                        navigateTo(Destination.Map)
                    },
                )
            }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }

    // Dismissing the dialog never exits: it closes on OK, on tap-outside, and — via rideActive
    // below — quietly when the ride ends while it is still open.
    if (showExitBlocked && rideActive) {
        AlertDialog(
            onDismissRequest = { showExitBlocked = false },
            title = { Text(stringResource(id = R.string.exit_blocked_title)) },
            text = { Text(stringResource(id = R.string.exit_blocked_text) + ".") },
            confirmButton = {
                DialogButton(stringResource(id = R.string.action_ok), onClick = { showExitBlocked = false })
            }
        )
    }

    if (showAbout) {
        val context = LocalContext.current
        val pkg = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(id = R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(id = R.string.app_name))
                    Text("${stringResource(id = R.string.about_version)} ${pkg.versionName}")
                    Text("${stringResource(id = R.string.about_build)} ${pkg.longVersionCode}")
                }
            },
            confirmButton = {
                DialogButton(stringResource(id = R.string.action_ok), onClick = { showAbout = false })
            }
        )
    }
}

/** Ticking wall clock in the top bar; HH:mm large, the trailing seconds smaller. */
@Composable
private fun TopBarClock() {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }
    val full = formatClock(nowMillis, withSeconds = true) // "HH:mm:ss"
    val hoursMinutes = full.substringBeforeLast(':')
    val seconds = full.substringAfterLast(':')
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontSize = 42.sp)) { append(hoursMinutes) }
            withStyle(SpanStyle(fontSize = 26.sp)) { append(":$seconds") }
        },
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = 16.dp),
    )
}

/** Outlined top-bar icon action: framed so it reads as a button, not a plain label. */
@Composable
private fun TopBarButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

private fun Destination.icon() = when (this) {
    Destination.Tracking -> Icons.Default.LocationOn
    Destination.Map -> Icons.Default.Map
    Destination.History -> Icons.Default.DateRange
    Destination.Settings -> Icons.Default.Settings
}

private fun Destination.labelRes() = when (this) {
    Destination.Tracking -> R.string.nav_tracking
    Destination.Map -> R.string.nav_map
    Destination.History -> R.string.nav_history
    Destination.Settings -> R.string.nav_settings
}
