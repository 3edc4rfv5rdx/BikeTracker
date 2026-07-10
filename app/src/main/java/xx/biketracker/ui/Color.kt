package xx.biketracker.ui

import androidx.compose.ui.graphics.Color

/**
 * Named colors for the app theme. Window backgrounds are a distinct tone from the container
 * roles (dialogs, menus) so overlays stand out against the screen behind them.
 */

// Bright red-orange accent shared by the map track and the paused Start/Pause button:
// high contrast in sunlight and against both light and dark map styles.
val AccentOrange = Color(0xFFFF3D00)

// Light theme: a faint grey window with pure-white containers.
val WindowLight = Color(0xFFF1F2F4)
val ContainerLight = Color.White

// Dark theme: near-black window with progressively lighter elevated containers.
val WindowDark = Color(0xFF121212)
val ContainerDarkLowest = Color(0xFF1A1A1A)
val ContainerDarkLow = Color(0xFF1F1F1F)
val ContainerDark = Color(0xFF242424)
val ContainerDarkHigh = Color(0xFF2A2A2A)
val ContainerDarkHighest = Color(0xFF303030)
