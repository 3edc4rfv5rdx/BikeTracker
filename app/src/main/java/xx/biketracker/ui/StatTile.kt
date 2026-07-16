package xx.biketracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One labelled figure — a short label, a big value, and an optional unit caption below.
 *  Shared by the ride details dialog and the extended-stats screen so both read alike. */
data class Stat(val label: String, val value: String, val unit: String? = null)

/** A row of equal-width [StatCell]s from [stats]; [Spacer]s pad a short final row so the last
 *  cells keep the others' width instead of stretching across the row. */
@Composable
fun StatRow(vararg stats: Stat?, modifier: Modifier = Modifier, cellsPerRow: Int = stats.size) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 0 until cellsPerRow) {
            val stat = stats.getOrNull(i)
            if (stat != null) StatCell(stat, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        }
    }
}

// The label caps at two lines so a long one wraps instead of pushing the value out of a narrow cell.
@Composable
fun StatCell(stat: Stat, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stat.value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (stat.unit != null) {
                Text(
                    text = stat.unit,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
