package com.akash.appcloner.engine.clone

import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-clone data isolation by mount namespace, for multiple instances of one package inside a
 * single Android user.
 *
 * ## Why this is gated and not implemented in-app
 *
 * An app's mount namespace is established by zygote when the process is specialised. Nothing
 * this app can run afterwards - `unshare`, `nsenter`, a bind mount from a root shell - lands in
 * the game's namespace, because the game is forked and specialised by zygote, not by us. Doing
 * this correctly needs code running inside the spawn path: a Zygisk or KernelSU companion
 * module that bind-mounts a per-clone data directory over the package's own during
 * specialisation.
 *
 * That module is a separate native deliverable and is **not** part of this project. Until it is
 * installed, [availability] reports [BackendAvailability.NeedsSetup] and every operation
 * refuses. The config writer below is complete and is the interface the module reads, so the
 * app side needs no further work once the module exists.
 *
 * ## Contract the companion module has to honour
 *
 *  - marker:  `/data/adb/modules/appcloner_isolation/module.prop`
 *  - config:  `/data/adb/appcloner/clones.json`, schema below, mode 0600 root:root
 *  - on specialise: for each entry whose `package` matches the process's package and whose
 *    `enabled` is true, bind-mount `dataDir` over `/data/data/<package>` and `externalDir`
 *    over `/storage/emulated/<user>/Android/data/<package>` inside the new namespace only.
 *  - the original instance is the entry with `cloneIndex == 0` and must never be remapped.
 *
 * Schema: `{"schemaVersion":1,"clones":[{"id","package","user","cloneIndex","dataDir",
 * "externalDir","enabled","rootAllowed"}]}`
 */
class RootDataIsolationBackend(
    private val rootProvider: RootProvider = RootProvider(),
) : CloneBackend {

    companion object {
        const val SCHEMA_VERSION = 1
        const val MODULE_MARKER = "/data/adb/modules/appcloner_isolation/module.prop"
        const val CONFIG_DIR = "/data/adb/appcloner"
        const val CONFIG_PATH = "$CONFIG_DIR/clones.json"
        const val CLONE_ROOT = "$CONFIG_DIR/data"
    }

    override val kind = CloneBackendKind.ROOT_DATA_ISOLATION

    override val capabilities = BackendCapabilities(
        kind = kind,
        concurrentWithOriginal = true,
        preservesPackageIdentity = true,
        survivesGameUpdate = true,
        runsOnUnmodifiedRuntime = true,
        requiresRoot = true,
        maxClonesPerPackage = null,
        perCloneRootControl = true,
    )

    override suspend fun availability(): BackendAvailability = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) {
            return@withContext BackendAvailability.NeedsSetup(
                reason = "Mount-namespace isolation needs root.",
                action = BackendAvailability.SetupAction.GRANT_ROOT,
            )
        }
        val present = Shell.exec("test -f ${Shell.quote(MODULE_MARKER)}", asRoot = true).isSuccess
        if (!present) {
            BackendAvailability.NeedsSetup(
                reason = "The companion isolation module is not installed. Per-clone mounts have " +
                    "to be applied during app specialisation, which an app cannot do for itself.",
                action = BackendAvailability.SetupAction.INSTALL_COMPANION_ROOT_MODULE,
            )
        } else {
            BackendAvailability.Available
        }
    }

    override suspend fun listClones(): List<CloneRecord> = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) return@withContext emptyList()
        val raw = Shell.exec("cat ${Shell.quote(CONFIG_PATH)}", asRoot = true)
        if (!raw.isSuccess) return@withContext emptyList()
        parseConfig(raw.stdout)
    }

    override suspend fun createClone(request: CloneRequest): CloneResult =
        withContext(Dispatchers.IO) {
            when (val state = availability()) {
                is BackendAvailability.NeedsSetup -> return@withContext CloneResult.Unsupported(
                    message = state.reason,
                    manualInstruction = setupRecipe(),
                )

                is BackendAvailability.Unavailable -> return@withContext CloneResult.Failure(
                    message = state.reason,
                    nextStep = state.remedy,
                )

                BackendAvailability.Available -> Unit
            }

            val existing = listClones().filter { it.packageName == request.packageName }
            val index = (existing.mapNotNull { it.id.substringAfterLast(':').toIntOrNull() }
                .maxOrNull() ?: 0) + 1
            val userId = 0
            val dataDir = "$CLONE_ROOT/$userId/${request.packageName}/$index/data"
            val externalDir = "$CLONE_ROOT/$userId/${request.packageName}/$index/external"

            val log = mutableListOf<String>()
            val mk = Shell.exec(
                "mkdir -p ${Shell.quote(dataDir)} ${Shell.quote(externalDir)} && " +
                    "chmod 700 ${Shell.quote(dataDir)} ${Shell.quote(externalDir)}",
                asRoot = true,
            )
            log += "$ mkdir -p $dataDir"
            if (!mk.isSuccess) {
                return@withContext CloneResult.Failure(
                    message = "Could not create the clone's data directory: ${mk.stderr.trim()}",
                    log = log,
                )
            }

            val record = CloneRecord(
                id = "mount:${request.packageName}:$index",
                packageName = request.packageName,
                label = request.label,
                backend = kind,
                androidUserId = userId,
                state = CloneState.CREATED,
                rootAllowed = request.allowRoot,
                forcedAbi = request.forcedAbi,
                createdAtMillis = System.currentTimeMillis(),
            )

            val written = writeConfig(existing + record, index, dataDir, externalDir)
            if (!written) {
                return@withContext CloneResult.Failure(
                    message = "Could not write $CONFIG_PATH.",
                    log = log,
                )
            }
            CloneResult.Success(record, log)
        }

    override suspend fun launch(clone: CloneRecord): CloneResult = CloneResult.Unsupported(
        message = "Launching an isolated instance is the companion module's job: the mount has " +
            "to exist before the process starts.",
        manualInstruction = setupRecipe(),
    )

    override suspend fun remove(clone: CloneRecord): CloneResult = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) return@withContext CloneResult.Failure("Root is not available")
        val remaining = listClones().filterNot { it.id == clone.id }
        val index = clone.id.substringAfterLast(':').toIntOrNull()
            ?: return@withContext CloneResult.Failure("Malformed clone id ${clone.id}")
        val dir = "$CLONE_ROOT/${clone.androidUserId ?: 0}/${clone.packageName}/$index"
        // Data is moved aside rather than deleted, so a mistake here is recoverable.
        Shell.exec(
            "mv ${Shell.quote(dir)} ${Shell.quote("$dir.removed")} 2>/dev/null",
            asRoot = true,
        )
        if (!writeConfigRecords(remaining)) {
            return@withContext CloneResult.Failure("Could not rewrite $CONFIG_PATH")
        }
        CloneResult.Success(clone.copy(state = CloneState.REMOVED))
    }

    override suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult =
        CloneResult.Unsupported(
            message = "The config carries a rootAllowed flag for the module to honour, but the " +
                "root policy itself belongs to the installed root manager.",
            manualInstruction = "Set the policy for ${clone.packageName} in your root manager's " +
                "superuser list.",
        )

    private fun setupRecipe(): String = """
        Build and install the companion module, then retry:
          1. Target the module at Zygisk (Magisk/APatch) or the KernelSU module API.
          2. On specialise, read $CONFIG_PATH and match the process package.
          3. Bind-mount the entry's dataDir over /data/data/<package> and externalDir over
             /storage/emulated/<user>/Android/data/<package>, in the new namespace only.
          4. Leave cloneIndex 0 untouched - that is the original installation.
          5. Ship $MODULE_MARKER so this backend can see the module is present.
        Until then, use the managed-profile backend for a concurrent clone, or the
        secondary-user backend for full isolation.
    """.trimIndent()

    private fun parseConfig(json: String): List<CloneRecord> = runCatching {
        val root = JSONObject(json)
        val clones = root.optJSONArray("clones") ?: JSONArray()
        (0 until clones.length()).mapNotNull { i ->
            val o = clones.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("package").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val index = o.optInt("cloneIndex", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            CloneRecord(
                id = o.optString("id").ifEmpty { "mount:$pkg:$index" },
                packageName = pkg,
                label = o.optString("label").ifEmpty { pkg },
                backend = kind,
                androidUserId = o.optInt("user", 0),
                state = if (o.optBoolean("enabled", true)) CloneState.READY else CloneState.CREATED,
                rootAllowed = o.optBoolean("rootAllowed", false),
                forcedAbi = o.optString("forcedAbi").ifEmpty { null },
                createdAtMillis = o.optLong("createdAt", 0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeConfig(
        records: List<CloneRecord>,
        newIndex: Int,
        dataDir: String,
        externalDir: String,
    ): Boolean = writeConfigRecords(records) { record, obj ->
        val index = record.id.substringAfterLast(':').toIntOrNull()
        if (index == newIndex) {
            obj.put("dataDir", dataDir)
            obj.put("externalDir", externalDir)
        }
    }

    private fun writeConfigRecords(
        records: List<CloneRecord>,
        decorate: (CloneRecord, JSONObject) -> Unit = { _, _ -> },
    ): Boolean {
        val array = JSONArray()
        records.forEach { record ->
            val index = record.id.substringAfterLast(':').toIntOrNull() ?: return@forEach
            val userId = record.androidUserId ?: 0
            val obj = JSONObject().apply {
                put("id", record.id)
                put("package", record.packageName)
                put("label", record.label)
                put("user", userId)
                put("cloneIndex", index)
                put("dataDir", "$CLONE_ROOT/$userId/${record.packageName}/$index/data")
                put("externalDir", "$CLONE_ROOT/$userId/${record.packageName}/$index/external")
                put("enabled", record.state != CloneState.REMOVED)
                put("rootAllowed", record.rootAllowed)
                record.forcedAbi?.let { put("forcedAbi", it) }
                put("createdAt", record.createdAtMillis)
            }
            decorate(record, obj)
            array.put(obj)
        }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("clones", array)
            .toString(2)

        // Written through a temp file and moved into place, so the module never reads a
        // half-written config.
        val tmp = "$CONFIG_PATH.tmp"
        val write = Shell.exec(
            "mkdir -p ${Shell.quote(CONFIG_DIR)} && " +
                "cat > ${Shell.quote(tmp)} <<'APPCLONER_EOF'\n$payload\nAPPCLONER_EOF\n" +
                "chmod 600 ${Shell.quote(tmp)} && mv -f ${Shell.quote(tmp)} ${Shell.quote(CONFIG_PATH)}",
            asRoot = true,
            timeoutSeconds = 20,
        )
        return write.isSuccess
    }
}
