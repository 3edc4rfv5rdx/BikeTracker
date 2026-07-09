package xx.biketracker.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Minimum readable body size for the whole app: no text renders below this (outdoor legibility). */
val MinTextSize = 18.sp

/**
 * Bottom-navigation labels stay small — the app-wide 18sp minimum would wrap/clip them under the
 * four tabs. Metrics match Material's default labelMedium, which the global typography overrides.
 */
val NavLabelStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.5.sp,
    fontWeight = FontWeight.Medium,
)

/** Line height applied when a style is bumped up to [MinTextSize]. */
private val MinLineHeight = 25.sp

private fun TextStyle.atLeastMin(): TextStyle =
    if (fontSize.value < MinTextSize.value) copy(fontSize = MinTextSize, lineHeight = MinLineHeight) else this

/** Material typography with every style below [MinTextSize] bumped up; larger titles are untouched. */
val AppTypography: Typography = Typography().run {
    copy(
        titleMedium = titleMedium.atLeastMin(),
        titleSmall = titleSmall.atLeastMin(),
        bodyLarge = bodyLarge.atLeastMin(),
        bodyMedium = bodyMedium.atLeastMin(),
        bodySmall = bodySmall.atLeastMin(),
        labelLarge = labelLarge.atLeastMin(),
        labelMedium = labelMedium.atLeastMin(),
        labelSmall = labelSmall.atLeastMin(),
    )
}
