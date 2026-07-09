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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.biketracker.R
import xx.biketracker.ui.DialogButton
import xx.biketracker.ui.DialogButtonRow

/**
 * Offline map manager, opened from Settings. Downloads the area last viewed on the Map tab
 * (recorded in [MapViewport]) into MapLibre's offline store and can delete everything
 * downloaded. Closing the dialog does not cancel a running download.
 */
@Composable
fun OfflineMapDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var regionCount by remember { mutableStateOf(-1) }
    var progress by remember { mutableStateOf<Int?>(null) }

    fun toast(resId: Int) = Toast.makeText(context, context.getString(resId), Toast.LENGTH_LONG).show()

    LaunchedEffect(Unit) { countOfflineRegions(context) { regionCount = it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_offline_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${stringResource(R.string.map_offline_hint)}.")
                if (regionCount >= 0) {
                    Text("${stringResource(R.string.map_offline_regions)}: $regionCount")
                }
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
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
                            deleteAllOfflineRegions(context) {
                                regionCount = 0
                                toast(R.string.map_offline_deleted)
                            }
                        },
                    )
                },
                end = {
                    DialogButton(
                        text = stringResource(R.string.map_offline_download),
                        onClick = {
                            if (progress == null) {
                                val started = downloadViewedRegion(
                                    context,
                                    onProgress = { progress = it },
                                    onFinished = { ok ->
                                        progress = null
                                        countOfflineRegions(context) { regionCount = it }
                                        toast(if (ok) R.string.map_offline_done else R.string.map_offline_failed)
                                    },
                                )
                                if (started) progress = 0 else toast(R.string.map_offline_no_area)
                            }
                        },
                    )
                },
            )
        },
    )
}
