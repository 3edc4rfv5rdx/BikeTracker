package xx.biketracker.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.biketracker.PREFS_NAME

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

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Load persisted settings into memory. Call once at startup before the UI reads them. */
    fun load(context: Context) {
        val stored = prefs(context).getString(KEY_THEME, null)
        _themeMode.value = stored?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        prefs(context).edit { putString(KEY_THEME, mode.name) }
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
