package com.akash.appcloner.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.akash.appcloner.engine.root.CommandLog
import com.akash.appcloner.engine.root.CommandRecord
import com.akash.appcloner.ui.SectionTitle
import com.akash.appcloner.ui.theme.StatusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * Every command this app has run, newest first, with both streams and the exit code.
 *
 * This is the screen that makes the rest of the app checkable: a clone that failed shows the
 * exact command and the exact refusal, rather than a generic error.
 */
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val records by CommandLog.records.collectAsState()

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            SectionTitle("Command log")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${records.size} recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
                TextButton(onClick = { CommandLog.clear() }) { Text("Clear") }
            }
            if (records.isEmpty()) {
                Text(
                    "Nothing yet. Every privileged command runs through one place and lands here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }
        }

        items(records.reversed()) { record -> CommandCard(record) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CommandCard(record: CommandRecord) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(
                    "${clock.format(Date(record.timestampMillis))}  ${if (record.asRoot) "su" else "sh"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.unknown,
                    modifier = Modifier.fillMaxWidth(0.55f),
                )
                Text(
                    record.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.succeeded) StatusColors.ok else StatusColors.bad,
                )
            }
            Text(
                record.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            record.stdout.trim().takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.unknown,
                )
            }
            record.stderr.trim().takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "stderr: $it",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.bad,
                )
            }
        }
    }
}
