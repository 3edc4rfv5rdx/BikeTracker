package xx.biketracker.map

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.biketracker.R
import xx.biketracker.ui.DialogButton
import xx.biketracker.ui.DialogButtonRow

/**
 * Offline map manager UI, opened from Settings. The operation itself lives in
 * [OfflineMapManager], so closing the dialog does not cancel a running download and reopening
 * it reconnects to live progress; a result that arrived while the dialog was closed is shown
 * on the next open.
 */
@Composable
fun OfflineMapDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by OfflineMapManager.state.collectAsState()
    val counts by OfflineMapManager.regions.collectAsState()
    val downloadedMessage = stringResource(R.string.map_offline_done)
    val downloadFailedMessage = stringResource(R.string.map_offline_failed)
    val deletedMessage = stringResource(R.string.map_offline_deleted)
    val noAreaMessage = stringResource(R.string.map_offline_no_area)

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    LaunchedEffect(Unit) { OfflineMapManager.refresh(context) }

    // Terminal results surface exactly once, then re-arm the Download button.
    LaunchedEffect(state) {
        when (state) {
            OfflineMapManager.State.Succeeded -> {
                toast(downloadedMessage)
                OfflineMapManager.acknowledgeResult()
            }
            OfflineMapManager.State.Failed -> {
                toast(downloadFailedMessage)
                OfflineMapManager.acknowledgeResult()
            }
            else -> Unit
        }
    }

    val downloading = state as? OfflineMapManager.State.Downloading

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_offline_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${stringResource(R.string.map_offline_hint)}.")
                counts?.let { c ->
                    Text("${stringResource(R.string.map_offline_regions)}: ${c.complete}")
                    if (c.partial > 0) {
                        Text("${stringResource(R.string.map_offline_partial)}: ${c.partial}")
                    }
                }
                downloading?.let { d ->
                    LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            DialogButtonRow(
                start = {
                    DialogButton(
                        text = stringResource(R.string.map_offline_delete),
                        destructive = true,
                        onClick = {
                            OfflineMapManager.deleteAll(context) { toast(deletedMessage) }
                        },
                    )
                },
                end = {
                    if (downloading != null) {
                        // Cancel keeps the partial region; pressing Download again resumes it.
                        DialogButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { OfflineMapManager.cancel(context) },
                        )
                    } else {
                        DialogButton(
                            text = stringResource(R.string.map_offline_download),
                            onClick = {
                                if (!OfflineMapManager.start(context)) toast(noAreaMessage)
                            },
                        )
                    }
                },
            )
        },
    )
}
