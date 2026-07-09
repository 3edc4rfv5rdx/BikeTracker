package xx.biketracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Filled dialog button; [destructive] tints it with the error color for delete/replace actions. */
@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) { Text(text) }
}

/**
 * Two dialog buttons pushed to opposite corners. Use in the AlertDialog confirmButton slot with
 * no dismissButton, so the buttons span the full width instead of clustering on the right.
 */
@Composable
fun DialogButtonRow(start: @Composable () -> Unit, end: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        start()
        end()
    }
}
