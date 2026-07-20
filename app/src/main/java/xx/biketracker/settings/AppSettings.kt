package xx.biketracker.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.biketracker.MPS_TO_KMH
import xx.biketracker.PREFS_NAME

/** Default rider weight (kg) until the user sets their own — a rough adult average. */
const val DEFAULT_WEIGHT_KG = 73

/** Auto-pause / auto-save defaults, mirroring the tracker's original hard-coded behaviour. */
const val DEFAULT_AUTO_PAUSE_SPEED_KMH = 2
const val DEFAULT_AUTO_PAUSE_HOLD_SEC = 10
const val DEFAULT_AUTO_SAVE_MIN = 10
/** Auto-resume speed is derived, not stored: this much above the pause threshold (hysteresis). */
const val AUTO_RESUME_MARGIN_KMH = 1

/** Resume/standby-start threshold in m/s, derived from the pause threshold plus the hysteresis margin. */
fun resumeSpeedMps(pauseSpeedKmh: Int): Double = (pauseSpeedKmh + AUTO_RESUME_MARGIN_KMH) / MPS_TO_KMH

/** Light/dark override; SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Supported UI languages. [tag] is a BCP-47 tag; SYSTEM means "follow the device locale". */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    UKRAINIAN("uk"),
    RUSSIAN("ru"),
}

/**
 * Process-wide app settings. Theme is a Compose concern, so it lives here as an observable
 * [StateFlow] backed by SharedPreferences. Language uses the framework per-app LocaleManager
 * (API 33+, pure AOSP): the system persists it and recreates the activity, so it is not
 * mirrored into state here.
 */
object AppSettings {
    private const val KEY_THEME = "theme_mode"
    private const val KEY_WEIGHT = "rider_weight_kg"
    private const val KEY_AUTOPAUSE_ENABLED = "autopause_enabled"
    private const val KEY_AUTOPAUSE_SPEED_KMH = "autopause_speed_kmh"
    private const val KEY_AUTOPAUSE_HOLD_SEC = "autopause_hold_sec"
    private const val KEY_AUTOSAVE_MIN = "autosave_min"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Rider body weight in kilograms, for the MET calorie estimate; defaults to [DEFAULT_WEIGHT_KG]. */
    private val _riderWeightKg = MutableStateFlow(DEFAULT_WEIGHT_KG)
    val riderWeightKg: StateFlow<Int> = _riderWeightKg.asStateFlow()

    // Auto-pause: pause a ride once the speed stays below [autoPauseSpeedKmh] for [autoPauseHoldSec];
    // resume [AUTO_RESUME_MARGIN_KMH] above that. A pause longer than [autoSaveMin] is saved. The
    // tracking service reads these live, so a change takes effect on the next fix without a restart.
    private val _autoPauseEnabled = MutableStateFlow(true)
    val autoPauseEnabled: StateFlow<Boolean> = _autoPauseEnabled.asStateFlow()
    private val _autoPauseSpeedKmh = MutableStateFlow(DEFAULT_AUTO_PAUSE_SPEED_KMH)
    val autoPauseSpeedKmh: StateFlow<Int> = _autoPauseSpeedKmh.asStateFlow()
    private val _autoPauseHoldSec = MutableStateFlow(DEFAULT_AUTO_PAUSE_HOLD_SEC)
    val autoPauseHoldSec: StateFlow<Int> = _autoPauseHoldSec.asStateFlow()
    private val _autoSaveMin = MutableStateFlow(DEFAULT_AUTO_SAVE_MIN)
    val autoSaveMin: StateFlow<Int> = _autoSaveMin.asStateFlow()

    /** Load persisted settings into memory. Call once at startup before the UI reads them. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _themeMode.value = prefs.getString(KEY_THEME, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
        _riderWeightKg.value = prefs.getInt(KEY_WEIGHT, DEFAULT_WEIGHT_KG)
        _autoPauseEnabled.value = prefs.getBoolean(KEY_AUTOPAUSE_ENABLED, true)
        _autoPauseSpeedKmh.value = prefs.getInt(KEY_AUTOPAUSE_SPEED_KMH, DEFAULT_AUTO_PAUSE_SPEED_KMH)
        _autoPauseHoldSec.value = prefs.getInt(KEY_AUTOPAUSE_HOLD_SEC, DEFAULT_AUTO_PAUSE_HOLD_SEC)
        _autoSaveMin.value = prefs.getInt(KEY_AUTOSAVE_MIN, DEFAULT_AUTO_SAVE_MIN)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        prefs(context).edit { putString(KEY_THEME, mode.name) }
    }

    fun setRiderWeightKg(context: Context, kg: Int) {
        val clamped = kg.coerceIn(0, 300)
        _riderWeightKg.value = clamped
        prefs(context).edit { putInt(KEY_WEIGHT, clamped) }
    }

    fun setAutoPauseEnabled(context: Context, enabled: Boolean) {
        _autoPauseEnabled.value = enabled
        prefs(context).edit { putBoolean(KEY_AUTOPAUSE_ENABLED, enabled) }
    }

    fun setAutoPauseSpeedKmh(context: Context, kmh: Int) {
        val clamped = kmh.coerceIn(1, 20)
        _autoPauseSpeedKmh.value = clamped
        prefs(context).edit { putInt(KEY_AUTOPAUSE_SPEED_KMH, clamped) }
    }

    fun setAutoPauseHoldSec(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(1, 120)
        _autoPauseHoldSec.value = clamped
        prefs(context).edit { putInt(KEY_AUTOPAUSE_HOLD_SEC, clamped) }
    }

    fun setAutoSaveMin(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(1, 120)
        _autoSaveMin.value = clamped
        prefs(context).edit { putInt(KEY_AUTOSAVE_MIN, clamped) }
    }

    /** The language currently applied via per-app locales. */
    fun currentLanguage(context: Context): AppLanguage {
        val tag = context.getSystemService(LocaleManager::class.java)
            .applicationLocales
            .takeUnless { it.isEmpty }
            ?.get(0)
            ?.language
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    /** Apply a language; the system persists it and recreates the activity to take effect. */
    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag)
            }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
