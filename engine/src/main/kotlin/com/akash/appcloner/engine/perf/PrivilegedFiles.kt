package com.akash.appcloner.engine.perf

import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import java.io.File

/**
 * Reads a file directly when the app is allowed to, and through root when it is not.
 *
 * The distinction between "read it" and "could not read it" is preserved all the way up: a
 * caller gets null and decides what to report, instead of an empty string that looks like a
 * valid empty file.
 */
class PrivilegedFiles(private val rootProvider: RootProvider = RootProvider()) {

    /** Source of the last successful read, for the [Metric.Value.source] label. */
    enum class Access { DIRECT, ROOT }

    data class ReadResult(val content: String, val access: Access)

    fun read(path: String): ReadResult? {
        val file = File(path)
        if (file.canRead()) {
            runCatching { file.readText() }
                .getOrNull()
                ?.let { return ReadResult(it, Access.DIRECT) }
        }
        if (!rootProvider.isRooted) return null
        val result = Shell.exec("cat ${Shell.quote(path)}", asRoot = true, timeoutSeconds = 10)
        return if (result.isSuccess && result.stdout.isNotBlank()) {
            ReadResult(result.stdout, Access.ROOT)
        } else {
            null
        }
    }

    fun listDirectory(path: String): List<String>? {
        val file = File(path)
        if (file.canRead()) {
            file.list()?.let { return it.toList() }
        }
        if (!rootProvider.isRooted) return null
        val result = Shell.exec("ls -1 ${Shell.quote(path)}", asRoot = true, timeoutSeconds = 10)
        return if (result.isSuccess) result.lines else null
    }
}
