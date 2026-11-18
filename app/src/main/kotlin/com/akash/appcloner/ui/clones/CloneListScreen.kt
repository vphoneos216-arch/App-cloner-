@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.akash.appcloner.ui.clones

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.akash.appcloner.ClonesController
import com.akash.appcloner.engine.clone.BackendAvailability
import com.akash.appcloner.engine.clone.BackendOption
import com.akash.appcloner.engine.clone.CloneGoal
import com.akash.appcloner.engine.clone.CloneRecord
import com.akash.appcloner.engine.clone.CloneState
import com.akash.appcloner.engine.compat.Abi
import com.akash.appcloner.engine.compat.ResolvedGame
import com.akash.appcloner.ui.KeyValueRow
import com.akash.appcloner.ui.SectionTitle
import com.akash.appcloner.ui.theme.StatusColors

/**
 * Clone management.
 *
 * The backend list is shown with each mechanism's real properties and, where it is unusable, the
 * reason. A mechanism that cannot run two instances at once says so before a clone is made,
 * rather than after.
 */
@Composable
fun CloneListScreen(
    controller: ClonesController,
    modifier: Modifier = Modifier,
    onInspect: (String) -> Unit,
) {
    val state by controller.state.collectAsState()
    var goal by remember { mutableStateOf(CloneGoal.CONCURRENT_WITH_ORIGINAL) }
    var pinned32Bit by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            SectionTitle("Goal")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CloneGoal.entries.forEach { candidate ->
                    FilterChip(
                        selected = goal == candidate,
                        onClick = {
                            goal = candidate
                            controller.refresh(candidate)
                        },
                        label = { Text(candidate.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = pinned32Bit,
                    onClick = { pinned32Bit = !pinned32Bit },
                    label = { Text("pin 32-bit") },
                )
                Spacer(Modifier.fillMaxWidth(0.03f))
                Text(
                    "For the 32-bit leg of the matrix. Needs the secondary-user backend.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.unknown,
                )
            }
        }

        item { SectionTitle("Backends on this device") }
        items(state.backendOptions) { option -> BackendCard(option) }

        item {
            SectionTitle("Clones")
            if (state.clones.isEmpty()) {
                Text(
                    "None yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }
        }
        items(state.clones) { clone ->
            CloneCard(
                clone = clone,
                onLaunch = { controller.launch(clone) },
                onStop = { controller.stop(clone) },
                onBackup = { controller.backup(clone) },
                onRemove = { controller.remove(clone) },
                onRootRequest = { controller.requestRootAccess(clone, true) },
            )
        }

        item {
            SectionTitle("Installed apps")
            Row {
                TextButton(onClick = { controller.refresh(goal) }) {
                    Text(if (state.busy) "Scanning..." else "Rescan")
                }
            }
            state.message?.let {
                Text(it, color = StatusColors.warn, style = MaterialTheme.typography.bodySmall)
            }
        }

        items(state.installedGames) { game ->
            InstalledAppCard(
                game = game,
                onClone = {
                    controller.create(
                        packageName = game.packageName,
                        label = game.label,
                        goal = if (pinned32Bit) CloneGoal.PINNED_ABI else goal,
                        forcedAbi = if (pinned32Bit) Abi.ARMEABI_V7A.directoryName else null,
                    )
                },
                onInspect = { onInspect(game.packageName) },
            )
        }

        if (state.log.isNotEmpty()) {
            item {
                SectionTitle("Last operation log")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        state.log.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BackendCard(option: BackendOption) {
    val capabilities = option.backend.capabilities
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    capabilities.kind.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth(0.62f),
                )
                Text(
                    when (option.availability) {
                        BackendAvailability.Available -> if (option.fitsGoal) "usable" else "wrong fit"
                        is BackendAvailability.NeedsSetup -> "setup needed"
                        is BackendAvailability.Unavailable -> "unavailable"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (option.isUsable) StatusColors.ok else StatusColors.warn,
                )
            }
            KeyValueRow("concurrent", capabilities.concurrentWithOriginal.toString())
            KeyValueRow("keeps identity", capabilities.preservesPackageIdentity.toString())
            KeyValueRow("survives update", capabilities.survivesGameUpdate.toString())
            KeyValueRow("stock runtime", capabilities.runsOnUnmodifiedRuntime.toString())
            KeyValueRow("needs root", capabilities.requiresRoot.toString())
            KeyValueRow(
                "max per package",
                capabilities.maxClonesPerPackage?.toString() ?: "device limit",
            )
            when (val availability = option.availability) {
                is BackendAvailability.Unavailable -> {
                    Text(
                        availability.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.unknown,
                    )
                    availability.remedy?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.unknown,
                        )
                    }
                }

                is BackendAvailability.NeedsSetup -> Text(
                    "${availability.reason} (${availability.action.name.lowercase().replace('_', ' ')})",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )

                BackendAvailability.Available -> option.mismatchReason?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.unknown,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloneCard(
    clone: CloneRecord,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onBackup: () -> Unit,
    onRemove: () -> Unit,
    onRootRequest: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(clone.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(clone.packageName)
                    clone.androidUserId?.let { append("  user $it") }
                    clone.forcedAbi?.let { append("  abi $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.unknown,
            )
            KeyValueRow(
                "state",
                clone.state.name.lowercase(),
                if (clone.state == CloneState.BROKEN) StatusColors.bad else null,
            )
            if (clone.state == CloneState.BROKEN) {
                Text(
                    "Recorded here but not found on the device. It was probably removed from " +
                        "system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.warn,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onLaunch) { Text("Launch") }
                OutlinedButton(onClick = onStop) { Text("Stop") }
                OutlinedButton(onClick = onBackup) { Text("Backup") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onRootRequest) { Text("Root") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun InstalledAppCard(
    game: ResolvedGame,
    onClone: () -> Unit,
    onInspect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.fillMaxWidth(0.6f)) {
                    Text(game.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        game.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.unknown,
                    )
                    game.profile?.let { profile ->
                        Text(
                            "known title: ${profile.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.ok,
                        )
                        profile.notes?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.unknown,
                            )
                        }
                    }
                }
                Column {
                    OutlinedButton(onClick = onClone) { Text("Clone") }
                    TextButton(onClick = onInspect) { Text("Inspect") }
                }
            }
        }
    }
}
