package xx.biketracker.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

    val themeMode by AppSettings.themeMode.collectAsState()
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
