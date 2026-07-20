package xx.biketracker.ui

import androidx.compose.ui.graphics.Color

/**
 * Named colors for the app theme. Window backgrounds are a distinct tone from the container
 * roles (dialogs, menus) so overlays stand out against the screen behind them.
 */

// Bright red-orange accent for the map track: high contrast in sunlight and against
// both light and dark map styles.
val AccentOrange = Color(0xFFFF3D00)

// Softer paused-state orange, matching the paused map arrow (ic_map_puck_paused) — kept
// yellower than AccentOrange so it never reads as the red GPS-trouble tint.
val PausedOrange = Color(0xFFFF9800)

// Light red for the Stop button while recording — kept lighter than the reddish accents so it
// stays clearly distinct from the paused-state PausedOrange next to it.
val StopRed = Color(0xFFFF5252)

// Scrub-marker blue, shared by the speed chart's dot and the matching marker on the map
// track: must stand out against the orange track line and both map styles.
val ScrubBlue = Color(0xFF1E88E5)

// Light theme: a faint grey window with pure-white containers.
val WindowLight = Color(0xFFF1F2F4)
val ContainerLight = Color.White

// Tonal button (Stop) fill in the dark theme: the stock tonal container is nearly invisible
// on the near-black window, so it gets a clearly lighter grey.
val TonalButtonDark = Color(0xFF4A4A4A)

// Dark theme: near-black window with progressively lighter elevated containers.
val WindowDark = Color(0xFF121212)
val ContainerDarkLowest = Color(0xFF1A1A1A)
val ContainerDarkLow = Color(0xFF1F1F1F)
val ContainerDark = Color(0xFF242424)
val ContainerDarkHigh = Color(0xFF2A2A2A)
val ContainerDarkHighest = Color(0xFF303030)
