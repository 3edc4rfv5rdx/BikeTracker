package xx.biketracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xx.biketracker.tracking.TrackingScreen

private sealed class Destination(val route: String) {
    data object Tracking : Destination("tracking")
    data object Map : Destination("map")
    data object History : Destination("history")
    data object Settings : Destination("settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.action_exit))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        label = { Text(stringResource(id = tab.labelRes())) }
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
            composable(Destination.Map.route) { PlaceholderScreen(R.string.map_placeholder) }
            composable(Destination.History.route) { PlaceholderScreen(R.string.history_placeholder) }
            composable(Destination.Settings.route) { PlaceholderScreen(R.string.settings_placeholder) }
        }
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

@Composable
private fun PlaceholderScreen(textRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(id = textRes))
    }
}
