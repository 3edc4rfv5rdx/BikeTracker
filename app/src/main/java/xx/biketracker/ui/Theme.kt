package xx.biketracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import xx.biketracker.settings.ThemeMode

// Window background is a distinct tone from the container roles, so dialogs and menus (which use
// surfaceContainer*) stand out against the screen behind them instead of blending in.
private val LightColors = lightColorScheme(
    background = WindowLight,
    surface = WindowLight,
    surfaceContainerLowest = ContainerLight,
    surfaceContainerLow = ContainerLight,
    surfaceContainer = ContainerLight,
    surfaceContainerHigh = ContainerLight,
    surfaceContainerHighest = ContainerLight,
)

private val DarkColors = darkColorScheme(
    background = WindowDark,
    surface = WindowDark,
    surfaceContainerLowest = ContainerDarkLowest,
    surfaceContainerLow = ContainerDarkLow,
    surfaceContainer = ContainerDark,
    surfaceContainerHigh = ContainerDarkHigh,
    surfaceContainerHighest = ContainerDarkHighest,
)

/** True when the app renders dark for the given theme choice; shared by the theme and the map. */
@Composable
fun isDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Applies the light or dark Material color scheme according to the user's theme choice. */
@Composable
fun BikeTrackerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
