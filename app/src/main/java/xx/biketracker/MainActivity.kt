package xx.biketracker

import android.os.Bundle
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
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import xx.biketracker.data.finalizeAbandonedTrips
import xx.biketracker.data.recoveryJob
import xx.biketracker.history.HistoryCommands
import xx.biketracker.history.HistoryScreen
import xx.biketracker.map.MapScreen
import xx.biketracker.map.MapSelection
import xx.biketracker.settings.AppSettings
import xx.biketracker.settings.SettingsScreen
import xx.biketracker.tracking.TrackingScreen
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
                        IconButton(onClick = onExit) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.action_exit))
                        }
                    }
                },
                actions = {
                    if (currentTab == Destination.History) {
                        TextButton(onClick = { HistoryCommands.send(HistoryCommands.Command.OPEN_TODAY) }) {
                            Text(stringResource(id = R.string.history_today))
                        }
                        IconButton(onClick = { HistoryCommands.send(HistoryCommands.Command.COLLAPSE_ALL) }) {
                            Icon(
                                Icons.Default.UnfoldLess,
                                contentDescription = stringResource(id = R.string.history_collapse_all),
                            )
                        }
                    }
                    if (currentTab == Destination.Settings) {
                        IconButton(onClick = { showAbout = true }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(id = R.string.about_title))
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
