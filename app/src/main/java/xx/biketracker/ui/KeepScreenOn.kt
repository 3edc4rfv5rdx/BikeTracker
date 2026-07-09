package xx.biketracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the screen awake while [active] and this composable is in composition — used by the
 * Tracking and Map tabs during a ride (readable in sunlight, no missed taps). Leaving the
 * screen or [active] going false releases the flag.
 */
@Composable
fun KeepScreenOnWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}
