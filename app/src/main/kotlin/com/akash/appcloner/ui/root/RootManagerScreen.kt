package com.akash.appcloner.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.akash.appcloner.RootController
import com.akash.appcloner.engine.clone.TypedUser
import com.akash.appcloner.engine.clone.UserTypes
import com.akash.appcloner.ui.KeyValueRow
import com.akash.appcloner.ui.SectionTitle
import com.akash.appcloner.ui.theme.StatusColors

/**
 * Root status with its evidence.
 *
 * The verdict line is not a decoration: it is derived from running `id` as root and reading uid
 * 0 back. If the request was denied, the denial text is what is shown.
 */
@Composable
fun RootManagerScreen(controller: RootController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            SectionTitle("Superuser")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    KeyValueRow(
                        "verdict",
                        when {
                            state.checking -> "checking"
                            state.isRooted -> "root granted"
                            else -> "no root"
                        },
                        if (state.isRooted) StatusColors.ok else StatusColors.bad,
                    )
                    KeyValueRow("manager", state.status?.flavor?.name?.lowercase() ?: "unknown")
                    state.status?.rawVersion?.let { KeyValueRow("su -v", it) }
                    state.idOutput?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "id (exit ${state.idExitCode}):",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.unknown,
                        )
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    state.status?.evidence?.forEach { evidence ->
                        Text(
                            evidence,
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.unknown,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = controller::refresh, enabled = !state.checking) {
                        Text(if (state.checking) "Checking..." else "Re-check root")
                    }
                    state.message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.warn,
                        )
                    }
                }
            }
        }

        item {
            SectionTitle("Native bridge")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    KeyValueRow(
                        "libappclonerbridge",
                        if (state.nativeAvailable) "loaded" else "not loaded",
                        if (state.nativeAvailable) StatusColors.ok else StatusColors.warn,
                    )
                    KeyValueRow("process abi", state.nativeAbi ?: "n/a")
                    KeyValueRow("process uid", state.nativeUid?.toString() ?: "n/a")
                    Text(
                        "The native side reads SELinux labels and ownership directly, which is " +
                            "how a clone's data directory is verified rather than assumed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.unknown,
                    )
                }
            }
        }

        item {
            SectionTitle("Android users on this device")
            if (state.users.isEmpty()) {
                Text(
                    if (state.isRooted) {
                        "No users enumerated yet."
                    } else {
                        "Needs root: the user list and its types come from pm and dumpsys."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }
        }

        items(state.users) { user -> UserCard(user) }

        item {
            SectionTitle("Diagnostics")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "Runs the whole capability probe: which user types this ROM defines, " +
                            "every user and its type, each backend's verdict with its reason, " +
                            "and the full command log. Creates nothing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.unknown,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { controller.runDiagnostics() },
                        enabled = !state.diagnosticsRunning,
                    ) {
                        Text(if (state.diagnosticsRunning) "Probing..." else "Run diagnostics")
                    }
                    state.reportPath?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Written to $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.unknown,
                        )
                    }
                }
            }
        }

        state.report?.let { report ->
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        report,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        item {
            SectionTitle("Clone capacity")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    KeyValueRow("clone profiles", state.cloneProfiles.size.toString())
                    KeyValueRow("oem dual-app profiles", state.oemDualAppProfiles.size.toString())
                    Text(
                        "Stock Android sets max-allowed-per-parent to 1 for the clone profile " +
                            "type, so one profile. One profile holds one copy of each app, so " +
                            "several different apps can be cloned into it. A second instance of " +
                            "the same app needs a second profile, which stock Android refuses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.unknown,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UserCard(user: TypedUser) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "user ${user.id}: ${user.name.ifBlank { "(unnamed)" }}",
                style = MaterialTheme.typography.bodyLarge,
            )
            KeyValueRow(
                "type",
                user.type ?: "not reported",
                when {
                    user.isCloneProfile -> StatusColors.ok
                    user.type == UserTypes.MANAGED_PROFILE -> StatusColors.warn
                    else -> null
                },
            )
            KeyValueRow("running", user.running.toString())
            if (user.isCloneProfile) {
                Text(
                    "AOSP clone profile: usable for cloning.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.ok,
                )
            }
            if (user.isOemDualAppCandidate) {
                Text(
                    "Looks like an OEM dual-app profile. Its type is not reported, so it is " +
                        "offered as a reuse candidate rather than treated as a clone profile.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.unknown,
                )
            }
            if (user.type == UserTypes.MANAGED_PROFILE) {
                Text(
                    "Managed profile. Not used by this app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.unknown,
                )
            }
        }
    }
}
