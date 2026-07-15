package xx.biketracker.ui

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The device's vibrator, or null where there is none. Compose's [androidx.compose.ui.platform.LocalHapticFeedback]
 * ends up in View.performHapticFeedback(), which the system silently drops whenever the phone's
 * touch-feedback setting is off (FLAG_IGNORE_GLOBAL_SETTING has been ignored since API 33), so a
 * gesture whose only confirmation is the buzz would engage with no feedback at all. Driving the
 * vibrator directly is not subject to that setting.
 */
@Composable
fun rememberVibrator(): Vibrator? {
    val context = LocalContext.current
    return remember(context) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    }
}

/** A gesture has just taken the finger over. Long enough to register while the hand is busy
 *  holding the phone; the predefined click effects are too faint for that on some devices. */
fun Vibrator?.gestureBuzz() {
    this?.vibrate(VibrationEffect.createOneShot(GESTURE_BUZZ_MS, GESTURE_BUZZ_AMPLITUDE))
}

private const val GESTURE_BUZZ_MS = 45L
/** Full strength (1..255); ignored on devices without amplitude control, which just buzz. */
private const val GESTURE_BUZZ_AMPLITUDE = 255
