package com.akash.appcloner.engine.stability

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.core.content.ContextCompat
import com.akash.appcloner.engine.perf.Metric
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell

/** One recorded process death, from the platform's own history. */
data class ProcessExitRecord(
    val processName: String,
    val pid: Int,
    val reason: Int,
    val reasonName: String,
    val subReason: Int,
    val description: String?,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val status: Int,
    val timestampMillis: Long,
) {
    val isNativeCrash: Boolean get() = reason == ApplicationExitInfo.REASON_CRASH_NATIVE
    val isJavaCrash: Boolean get() = reason == ApplicationExitInfo.REASON_CRASH
    val isLowMemoryKill: Boolean get() = reason == ApplicationExitInfo.REASON_LOW_MEMORY
    val isAnr: Boolean get() = reason == ApplicationExitInfo.REASON_ANR
    val isResourceKill: Boolean
        get() = reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
}

/**
 * Collects why a game process died.
 *
 * The platform keeps this history itself, which makes it the one crash source that is both
 * reliable and free of guesswork: it distinguishes a native crash from an ANR from a
 * low-memory kill, and it includes the process size at the moment of death - exactly what is
 * needed to tell "the clone crashed" from "the device ran out of memory with two instances up".
 *
 * Reading another package's history needs the DUMP permission, so without root it reports
 * [Metric.needsRoot] instead of an empty list.
 */
class CrashCollector(
    private val context: Context,
    private val rootProvider: RootProvider = RootProvider(),
) {

    fun exitReasons(packageName: String, max: Int = 16): Metric<List<ProcessExitRecord>> {
        val am = ContextCompat.getSystemService(context, ActivityManager::class.java)
            ?: return Metric.Unavailable("No activity manager")
        return try {
            val records = am.getHistoricalProcessExitReasons(packageName, 0, max)
                .map { info ->
                    ProcessExitRecord(
                        processName = info.processName,
                        pid = info.pid,
                        reason = info.reason,
                        reasonName = describeReason(info.reason),
                        subReason = info.subReason,
                        description = info.description,
                        importance = info.importance,
                        pssKb = info.pss,
                        rssKb = info.rss,
                        status = info.status,
                        timestampMillis = info.timestamp,
                    )
                }
            Metric.Value(records, "ActivityManager exit reasons")
        } catch (e: SecurityException) {
            Metric.Unavailable(
                reason = "Reading exit history for $packageName needs the DUMP permission: " +
                    (e.message ?: "denied"),
                remedy = "Grant root, or run: adb shell dumpsys activity exit-info $packageName",
            )
        } catch (t: Throwable) {
            Metric.Unavailable("Exit history unavailable: ${t.message ?: t::class.java.simpleName}")
        }
    }

    fun describeReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "crash (managed)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory kill"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_SIGNALED -> "signalled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        else -> "reason $reason"
    }

    /**
     * Native crash log for a dead process. The platform attaches a trace to native crashes and
     * ANRs, which is the stack worth keeping; nothing is attached for an ordinary exit.
     */
    fun nativeTrace(packageName: String, timestampMillis: Long, maxBytes: Int = 64 * 1024): String? {
        val am = ContextCompat.getSystemService(context, ActivityManager::class.java) ?: return null
        return runCatching {
            am.getHistoricalProcessExitReasons(packageName, 0, 32)
                .firstOrNull { it.timestamp == timestampMillis }
                ?.traceInputStream
                ?.use { stream -> String(stream.readBytes().take(maxBytes).toByteArray()) }
        }.getOrNull()
    }

    /** Recent log lines for a pid. READ_LOGS is privileged, so this is root-gated. */
    fun logcatForPid(pid: Int, lines: Int = 400): Metric<String> {
        if (!rootProvider.isRooted) {
            return Metric.needsRoot("logcat for another process")
        }
        val result = Shell.exec(
            "logcat -d -t $lines --pid=$pid",
            asRoot = true,
            timeoutSeconds = 25,
        )
        return if (result.isSuccess && result.stdout.isNotBlank()) {
            Metric.Value(result.stdout, "logcat --pid=$pid")
        } else {
            Metric.Unavailable(
                reason = "logcat returned nothing for pid $pid: ${result.stderr.trim().take(160)}",
                remedy = "On releases without --pid support use: logcat -d -t $lines | grep $pid",
            )
        }
    }

    /** Most recent tombstone file names. Root only; the directory is 0700 root:system. */
    fun tombstones(limit: Int = 10): Metric<List<String>> {
        if (!rootProvider.isRooted) {
            return Metric.needsRoot("/data/tombstones")
        }
        val result = Shell.exec(
            "ls -t /data/tombstones 2>/dev/null | head -n $limit",
            asRoot = true,
            timeoutSeconds = 15,
        )
        return if (result.isSuccess) {
            Metric.Value(result.lines, "/data/tombstones")
        } else {
            Metric.Unavailable("Could not list tombstones: ${result.stderr.trim().take(160)}")
        }
    }

    fun readTombstone(name: String, maxBytes: Int = 128 * 1024): Metric<String> {
        if (!rootProvider.isRooted) return Metric.needsRoot("/data/tombstones")
        val safeName = name.substringAfterLast('/')
        val result = Shell.exec(
            "head -c $maxBytes ${Shell.quote("/data/tombstones/$safeName")}",
            asRoot = true,
            timeoutSeconds = 20,
        )
        return if (result.isSuccess && result.stdout.isNotBlank()) {
            Metric.Value(result.stdout, "/data/tombstones/$safeName")
        } else {
            Metric.Unavailable("Could not read tombstone $safeName")
        }
    }
}
