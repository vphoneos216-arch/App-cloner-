package com.akash.appcloner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.akash.appcloner.engine.clone.BackendAvailability
import com.akash.appcloner.engine.clone.CloneBackendKind
import com.akash.appcloner.engine.clone.CloneState
import com.akash.appcloner.engine.perf.MonitorTarget
import com.akash.appcloner.engine.policy.IntegrityPolicy
import com.akash.appcloner.ui.theme.AppClonerTheme
import com.akash.appcloner.ui.clones.CloneListScreen
import com.akash.appcloner.ui.compat.CompatibilityScreen
import com.akash.appcloner.ui.dashboard.GamingDashboardScreen

private enum class Tab(val label: String) {
    CLONES("Clones"),
    DASHBOARD("Dashboard"),
    COMPAT("Compat"),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AppClonerApplication
        setContent {
            AppClonerTheme {
                AppClonerRoot(app)
            }
        }
    }
}

@Composable
private fun AppClonerRoot(app: AppClonerApplication) {
    var tab by remember { mutableStateOf(Tab.CLONES) }
    val clonesState by app.clones.state.collectAsState()

    LaunchedEffect(Unit) { app.clones.refresh() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The monitoring notification is cosmetic; sampling continues either way. */ }

    val provisioning = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { app.clones.refresh() }

    val managedProfileSetup = clonesState.backendOptions
        .firstOrNull { it.backend.kind == CloneBackendKind.MANAGED_PROFILE }
        ?.availability as? BackendAvailability.NeedsSetup

    // Dashboard targets: every clone, plus the originals, so the two can be compared side by
    // side. A clone recorded but missing from the device is left out - there is nothing to watch.
    val targets = remember(clonesState.clones, clonesState.installedGames) {
        val cloneTargets = clonesState.clones
            .filter { it.state != CloneState.BROKEN && it.state != CloneState.REMOVED }
            .map { MonitorTarget(it.packageName, "${it.label} (clone)", it.androidUserId) }
        val originalTargets = clonesState.installedGames
            .filter { it.profile != null }
            .map { MonitorTarget(it.packageName, it.label, null) }
        (cloneTargets + originalTargets).distinctBy { it.packageName to it.androidUserId }
    }

    Scaffold(
        topBar = {
            // Plain header rather than TopAppBar: that component is still behind an
            // experimental opt-in in Material3 and is not worth the churn here.
            Text(
                "App Cloner - gaming",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { candidate ->
                    NavigationBarItem(
                        selected = tab == candidate,
                        onClick = { tab = candidate },
                        icon = { Text(candidate.label.take(1)) },
                        label = { Text(candidate.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (managedProfileSetup != null && tab == Tab.CLONES) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "Work profile not set up",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            managedProfileSetup.reason,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            IntegrityPolicy.UI_EXPLANATION,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(
                            onClick = {
                                provisioning.launch(
                                    app.graph.managedProfileBackend.provisioningIntent(),
                                )
                            },
                        ) {
                            Text("Create work profile")
                        }
                    }
                }
            }

            when (tab) {
                Tab.CLONES -> CloneListScreen(
                    controller = app.clones,
                    onInspect = { packageName ->
                        app.compat.inspect(packageName)
                        tab = Tab.COMPAT
                    },
                )

                Tab.DASHBOARD -> GamingDashboardScreen(
                    controller = app.dashboard,
                    availableTargets = targets,
                    onSamplingChanged = { sampling, label ->
                        if (sampling) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    app,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            MonitorService.start(app, label)
                        } else {
                            MonitorService.stop(app)
                        }
                    },
                )

                Tab.COMPAT -> CompatibilityScreen(controller = app.compat)
            }
        }
    }
}
