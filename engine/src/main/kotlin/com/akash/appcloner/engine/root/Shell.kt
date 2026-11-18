package com.akash.appcloner.engine.root

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of one command execution. [timedOut] is distinct from a non-zero exit. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val isSuccess: Boolean get() = !timedOut && exitCode == 0
    val lines: List<String> get() = stdout.lineSequence().filter { it.isNotBlank() }.toList()

    companion object {
        fun failure(reason: String) = ShellResult(-1, "", reason, timedOut = false)
    }
}

/**
 * Minimal command runner. Never interactive, always bounded by a timeout, and every argument
 * that comes from outside this process goes through [quote] before it reaches a shell.
 */
object Shell {

    private const val TAG = "AppCloner/Shell"
    private const val DEFAULT_TIMEOUT_SECONDS = 15L
    private const val READER_JOIN_MILLIS = 1_000L

    /**
     * Single-quotes a value for safe inclusion in a shell command line. Package names, user
     * ids and paths all pass through here; a path with a space or a quote in it must not be
     * able to extend the command.
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun exec(
        command: String,
        asRoot: Boolean = false,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): ShellResult {
        val argv = if (asRoot) {
            arrayOf("su", "-c", command)
        } else {
            arrayOf("sh", "-c", command)
        }
        return run(argv, timeoutSeconds)
    }

    private fun run(argv: Array<String>, timeoutSeconds: Long): ShellResult {
        val process = try {
            ProcessBuilder(*argv).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            // An absent su binary surfaces as IOException here. That is a normal
            // "no root on this device" outcome, not an error worth throwing.
            Log.d(TAG, "exec failed: ${argv.joinToString(" ")}", t)
            return ShellResult.failure(t.message ?: t::class.java.simpleName)
        }

        // Both pipes are drained on their own threads. A command that fills the stderr buffer
        // while nothing reads it would otherwise block forever, and the timeout below would be
        // the only thing that ended it.
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = Thread {
            runCatching { process.inputStream.bufferedReader().use { out.append(it.readText()) } }
        }
        val errReader = Thread {
            runCatching { process.errorStream.bufferedReader().use { err.append(it.readText()) } }
        }

        return try {
            outReader.start()
            errReader.start()

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                outReader.join(READER_JOIN_MILLIS)
                errReader.join(READER_JOIN_MILLIS)
                return ShellResult(-1, out.toString(), err.toString(), timedOut = true)
            }
            outReader.join(READER_JOIN_MILLIS)
            errReader.join(READER_JOIN_MILLIS)
            ShellResult(process.exitValue(), out.toString(), err.toString(), timedOut = false)
        } catch (t: Throwable) {
            Log.d(TAG, "exec failed: ${argv.joinToString(" ")}", t)
            ShellResult.failure(t.message ?: t::class.java.simpleName)
        } finally {
            process.destroy()
        }
    }

    /** True when the named binary is resolvable on PATH or at an absolute path. */
    fun binaryExists(name: String): Boolean {
        if (name.startsWith("/")) return File(name).canExecute()
        return exec("command -v ${quote(name)}", asRoot = false, timeoutSeconds = 5).isSuccess
    }
}
