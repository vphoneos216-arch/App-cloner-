package com.akash.appcloner.engine.clone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local record of clones this app created, used to label and group what the backends report.
 *
 * The store is never the source of truth about whether a clone exists - a user removed from
 * system settings would leave a stale entry. [CloneEngine.clones] reconciles it against what
 * the backends actually see.
 */
class CloneStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_cloner_clones", Context.MODE_PRIVATE)

    fun all(): List<CloneRecord> {
        val raw = prefs.getString(KEY_CLONES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    fun put(record: CloneRecord) {
        val merged = all().filterNot { it.id == record.id } + record
        persist(merged)
    }

    fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    private fun persist(records: List<CloneRecord>) {
        val array = JSONArray()
        records.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_CLONES, array.toString()).apply()
    }

    private fun toJson(record: CloneRecord) = JSONObject().apply {
        put("id", record.id)
        put("package", record.packageName)
        put("label", record.label)
        put("backend", record.backend.name)
        record.androidUserId?.let { put("user", it) }
        put("state", record.state.name)
        put("rootAllowed", record.rootAllowed)
        record.forcedAbi?.let { put("forcedAbi", it) }
        put("createdAt", record.createdAtMillis)
    }

    private fun fromJson(obj: JSONObject): CloneRecord? {
        val id = obj.optString("id").ifEmpty { return null }
        val pkg = obj.optString("package").ifEmpty { return null }
        val backend = runCatching { CloneBackendKind.valueOf(obj.optString("backend")) }
            .getOrNull() ?: return null
        val state = runCatching { CloneState.valueOf(obj.optString("state")) }
            .getOrDefault(CloneState.CREATED)
        return CloneRecord(
            id = id,
            packageName = pkg,
            label = obj.optString("label").ifEmpty { pkg },
            backend = backend,
            androidUserId = if (obj.has("user")) obj.optInt("user") else null,
            state = state,
            rootAllowed = obj.optBoolean("rootAllowed", false),
            forcedAbi = obj.optString("forcedAbi").ifEmpty { null },
            createdAtMillis = obj.optLong("createdAt", 0L),
        )
    }

    private companion object {
        const val KEY_CLONES = "clones_v1"
    }
}
