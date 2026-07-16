package xx.biketracker.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xx.biketracker.R
import xx.biketracker.data.backupDatabase
import xx.biketracker.data.DatabaseRestoreCoordinator
import xx.biketracker.data.DatabaseMaintenance
import xx.biketracker.data.DatabaseMaintenanceOperation
import xx.biketracker.data.RestoreOperationState
import xx.biketracker.map.OfflineMapDialog
import xx.biketracker.tracking.TrackingState
import xx.biketracker.tracking.TrackingStatus
import xx.biketracker.ui.DialogButton

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showOfflineMapDialog by remember { mutableStateOf(false) }
    var showAutoPauseDialog by remember { mutableStateOf(false) }

    val themeMode by AppSettings.themeMode.collectAsState()
    val weightKg by AppSettings.riderWeightKg.collectAsState()
    val tracking by TrackingState.snapshot.collectAsState()
    val restoreState by DatabaseRestoreCoordinator.state.collectAsState()
    val restoreRunning = restoreState == RestoreOperationState.Running
    val maintenanceOperation by DatabaseMaintenance.operation.collectAsState()
    val maintenanceRunning = maintenanceOperation != DatabaseMaintenanceOperation.NONE
    val rideActiveMessage = stringResource(R.string.backup_ride_active)
    val backupSavedMessage = stringResource(R.string.backup_saved)
    val backupFailedMessage = stringResource(R.string.backup_failed)

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    // Backup copies the live DB file and restore replaces it; either racing an in-flight ride
    // save could corrupt data, so both are refused while a ride is active.
    fun refuseIfRideActive(): Boolean {
        val active = tracking.status != TrackingStatus.IDLE
        if (active) toast(rideActiveMessage)
        return active
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // Re-check after the picker: a ride could have started while it was open.
        if (uri != null && !refuseIfRideActive()) {
            DatabaseRestoreCoordinator.start(context, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_appearance))
        ValueRow(
            label = stringResource(R.string.settings_language),
            value = stringResource(languageLabel(AppSettings.currentLanguage(context))),
            onClick = { showLanguageDialog = true },
        )
        ValueRow(
            label = stringResource(R.string.settings_theme),
            value = stringResource(themeLabel(themeMode)),
            onClick = { showThemeDialog = true },
        )

        SectionHeader(stringResource(R.string.settings_profile))
        WeightRow(weightKg = weightKg, onChange = { AppSettings.setRiderWeightKg(context, it) })

        SectionHeader(stringResource(R.string.settings_recording_section))
        NavRow(
            label = stringResource(R.string.autopause_title),
            onClick = { showAutoPauseDialog = true },
        )

        SectionHeader(stringResource(R.string.settings_map_section))
        NavRow(
            label = stringResource(R.string.map_offline_title),
            onClick = { showOfflineMapDialog = true },
        )

        SectionHeader(stringResource(R.string.settings_data_section))
        NavRow(
            label = if (maintenanceOperation == DatabaseMaintenanceOperation.BACKUP) {
                stringResource(R.string.backup_in_progress)
            } else {
                stringResource(R.string.btn_backup)
            },
            enabled = !restoreRunning && !maintenanceRunning,
            onClick = {
                if (!refuseIfRideActive()) scope.launch {
                    runCatching { backupDatabase(context) }
                        .onSuccess { path -> toast("$backupSavedMessage\n$path") }
                        .onFailure { toast(backupFailedMessage) }
                }
            },
        )
        NavRow(
            label = if (restoreRunning) {
                stringResource(R.string.restore_in_progress)
            } else {
                stringResource(R.string.btn_restore)
            },
            enabled = !restoreRunning && !maintenanceRunning,
            onClick = { if (!refuseIfRideActive()) showRestoreConfirm = true },
        )
    }

    if (showLanguageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries.map { it to languageLabel(it) },
            selected = AppSettings.currentLanguage(context),
            onSelect = {
                showLanguageDialog = false
                AppSettings.setLanguage(context, it) // recreates the activity with the new locale
            },
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showThemeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to themeLabel(it) },
            selected = themeMode,
            onSelect = {
                showThemeDialog = false
                AppSettings.setThemeMode(context, it)
            },
            onDismiss = { showThemeDialog = false },
        )
    }
    // languageLabel/themeLabel below return string-resource ids (plain, not @Composable), so they
    // are safe to call inside the map lambdas above; ChoiceDialog resolves them with stringResource.

    if (showOfflineMapDialog) {
        OfflineMapDialog(onDismiss = { showOfflineMapDialog = false })
    }

    if (showAutoPauseDialog) {
        AutoPauseDialog(onDismiss = { showAutoPauseDialog = false })
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_text)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.btn_restore),
                    destructive = true,
                    onClick = {
                        showRestoreConfirm = false
                        restoreLauncher.launch(arrayOf("application/zip", "*/*"))
                    },
                )
            },
            dismissButton = {
                DialogButton(stringResource(R.string.action_cancel), onClick = { showRestoreConfirm = false })
            },
        )
    }
}

private fun languageLabel(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.UKRAINIAN -> R.string.language_ukrainian
    AppLanguage.RUSSIAN -> R.string.language_russian
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/** Group heading — larger, bold, tinted; separates settings blocks. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

/** A row that opens a dialog/screen: label on the left, trailing chevron. */
@Composable
private fun NavRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A row showing the current value on the right plus a chevron. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Rider weight as an inline numeric field: the "Your weight, kg" label with a "for calories"
 *  hint on the left, a compact entry on the right that persists each valid edit immediately (an
 *  empty field simply saves nothing). */
@Composable
private fun WeightRow(weightKg: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(weightKg.takeIf { it > 0 }?.toString().orEmpty()) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${stringResource(R.string.settings_weight)}, ${stringResource(R.string.unit_kg)}")
            Text(
                text = stringResource(R.string.settings_weight_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { entered ->
                text = entered.filter { it.isDigit() }.take(3)
                text.toIntOrNull()?.let(onChange)
            },
            singleLine = true,
            // A Done key gives the number keyboard a way to close and drop focus — without it the
            // field traps the cursor, since a numeric IME has no default dismiss action.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.width(96.dp),
        )
    }
}

/** Auto-pause settings behind a Settings row: the toggle plus speed / hold / auto-save numbers,
 *  each applied live. Speed and hold grey out while auto-pause is off; auto-save still applies to a
 *  manual pause, so it stays active. */
@Composable
private fun AutoPauseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by AppSettings.autoPauseEnabled.collectAsState()
    val speedKmh by AppSettings.autoPauseSpeedKmh.collectAsState()
    val holdSec by AppSettings.autoPauseHoldSec.collectAsState()
    val saveMin by AppSettings.autoSaveMin.collectAsState()
    val kmh = stringResource(R.string.unit_kmh)
    val sec = stringResource(R.string.unit_sec)
    val min = stringResource(R.string.unit_min)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autopause_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.autopause_enable), modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { AppSettings.setAutoPauseEnabled(context, it) },
                    )
                }
                LabeledNumberField(
                    label = "${stringResource(R.string.autopause_speed)}, $kmh",
                    value = speedKmh,
                    enabled = enabled,
                    onValueChange = { AppSettings.setAutoPauseSpeedKmh(context, it) },
                )
                LabeledNumberField(
                    label = "${stringResource(R.string.autopause_hold)}, $sec",
                    value = holdSec,
                    enabled = enabled,
                    onValueChange = { AppSettings.setAutoPauseHoldSec(context, it) },
                )
                LabeledNumberField(
                    label = "${stringResource(R.string.autopause_autosave)}, $min",
                    value = saveMin,
                    enabled = true, // auto-save applies to a manual pause too, so it is always live
                    onValueChange = { AppSettings.setAutoSaveMin(context, it) },
                )
                Text(
                    text = stringResource(R.string.autopause_resume_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.action_ok), onClick = onDismiss) },
    )
}

/** A "Label, unit" row with a compact numeric field on the right, applied live (an empty field
 *  saves nothing). Greys out its label when [enabled] is false. */
@Composable
private fun LabeledNumberField(label: String, value: Int, enabled: Boolean, onValueChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        OutlinedTextField(
            value = text,
            enabled = enabled,
            onValueChange = { entered ->
                text = entered.filter { it.isDigit() }.take(3)
                text.toIntOrNull()?.let(onValueChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.width(96.dp),
        )
    }
}

/** Single-choice radio dialog; selecting an option applies it immediately and closes. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, onClick = { onSelect(value) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}
