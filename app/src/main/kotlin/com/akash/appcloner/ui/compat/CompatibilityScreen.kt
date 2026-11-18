package com.akash.appcloner.ui.compat

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.akash.appcloner.CompatController
import com.akash.appcloner.engine.compat.AbiResolution
import com.akash.appcloner.engine.compat.Finding
import com.akash.appcloner.engine.compat.Severity
import com.akash.appcloner.engine.compat.StaticVerdict
import com.akash.appcloner.ui.KeyValueRow
import com.akash.appcloner.ui.SectionTitle
import com.akash.appcloner.ui.theme.StatusColors

/**
 * Static compatibility picture for one package.
 *
 * The verdict is worded as what it is. "No blockers found" means nothing in the metadata
 * contradicts this device, which is not the same as the game working - that is settled by
 * running it.
 */
@Composable
fun CompatibilityScreen(controller: CompatController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            if (state.inspecting) {
                Text("Inspecting...", style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let {
                Text(it, color = StatusColors.warn, style = MaterialTheme.typography.bodySmall)
            }
            if (state.inspection == null && !state.inspecting) {
                Text(
                    "Pick a package with Inspect on the Clones tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }
        }

        state.inspection?.let { inspection ->
            item {
                SectionTitle(inspection.label)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        KeyValueRow("package", inspection.packageName)
                        KeyValueRow(
                            "version",
                            "${inspection.versionName} (${inspection.versionCode})",
                        )
                        KeyValueRow("min / target sdk", "${inspection.minSdk} / ${inspection.targetSdk}")
                        KeyValueRow(
                            "abis in apk",
                            inspection.apkAbis.joinToString().ifEmpty { "none" },
                        )
                        KeyValueRow("engine", inspection.engine.kind.name)
                        KeyValueRow("splits", inspection.splitApks.size.toString())
                        KeyValueRow(
                            "native libs",
                            "${inspection.nativeLibraries.size} " +
                                "(${inspection.totalNativeBytes / (1024 * 1024)} MB)",
                        )
                        KeyValueRow("extractNativeLibs", inspection.extractNativeLibs?.toString() ?: "n/a")
                        inspection.obb?.let { obb ->
                            KeyValueRow(
                                "obb",
                                "${obb.files.size} files, ${obb.totalBytes / (1024 * 1024)} MB",
                                if (obb.isConsistentWithInstall) null else StatusColors.warn,
                            )
                        } ?: KeyValueRow("obb", "not inspected", StatusColors.warn)
                        if (inspection.engine.evidence.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "evidence: ${inspection.engine.evidence.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.unknown,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        state.report?.let { report ->
            item {
                SectionTitle("Verdict")
                Text(
                    when (report.verdict) {
                        StaticVerdict.BLOCKED -> "Blocked on this device"
                        StaticVerdict.NO_BLOCKERS_FOUND -> "No blockers found (not a guarantee)"
                        StaticVerdict.UNVERIFIABLE -> "Could not verify"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (report.verdict) {
                        StaticVerdict.BLOCKED -> StatusColors.bad
                        StaticVerdict.NO_BLOCKERS_FOUND -> StatusColors.ok
                        StaticVerdict.UNVERIFIABLE -> StatusColors.warn
                    },
                )
                val abi = report.abi
                Text(
                    when (abi) {
                        is AbiResolution.Resolved ->
                            "Platform would select ${abi.abi} " +
                                "(${if (abi.is64Bit) "64" else "32"}-bit)"

                        is AbiResolution.NoMatchingAbi -> "No ABI in common with this device"
                        AbiResolution.NoNativeCode -> "No native code"
                        is AbiResolution.ForcedAbiUnavailable ->
                            "Pinned ABI ${abi.requested} unavailable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }

            item { SectionTitle("Findings") }
            items(report.findings) { finding -> FindingCard(finding) }
        }

        state.nativeReport?.let { native ->
            item {
                SectionTitle("Native libraries")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        KeyValueRow("abi checked", native.selectedAbi ?: "all")
                        KeyValueRow("objects checked", native.checks.size.toString())
                        KeyValueRow(
                            "mismatches",
                            native.problems.size.toString(),
                            if (native.problems.isEmpty()) StatusColors.ok else StatusColors.bad,
                        )
                        native.problems.forEach {
                            Text(
                                "${it.library.path}: ${it.problem}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.bad,
                            )
                        }
                        if (native.unreadable.isNotEmpty()) {
                            Text(
                                "unreadable: ${native.unreadable.size} objects",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.warn,
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
private fun FindingCard(finding: Finding) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                finding.title,
                style = MaterialTheme.typography.titleSmall,
                color = when (finding.severity) {
                    Severity.BLOCKER -> StatusColors.bad
                    Severity.WARNING -> StatusColors.warn
                    Severity.INFO -> StatusColors.unknown
                },
            )
            Text(finding.detail, style = MaterialTheme.typography.bodySmall)
            finding.nextStep?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "next: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }
            Text(
                finding.code,
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.unknown,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
