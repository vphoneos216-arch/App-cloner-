package com.akash.appcloner.engine.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One executed command, kept whole: what ran, whether it ran as root, what it printed on both
 * streams, and how it ended.
 *
 * A permission denial and a command that does not exist both surface here with their real text,
 * which is the difference between a log and a progress indicator.
 */
data class CommandRecord(
    val sequence: Long,
    val timestampMillis: Long,
    val command: String,
    val asRoot: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMillis: Long,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0

    val summary: String
        get() = when {
            timedOut -> "timed out after ${durationMillis}ms"
            exitCode == 0 -> "ok (${durationMillis}ms)"
            else -> "exit $exitCode (${durationMillis}ms)"
        }
}

/**
 * Bounded record of every command this app has run.
 *
 * Output is truncated per stream so a `dumpsys` cannot push the rest of the history out of
 * memory; truncation is marked in the text rather than silent.
 */
object CommandLog {

    private const val CAPACITY = 300
    private const val MAX_STREAM_CHARS = 4_000

    private val lock = Any()
    private var nextSequence = 1L
    private val entries = ArrayDeque<CommandRecord>(CAPACITY)

    private val _records = MutableStateFlow<List<CommandRecord>>(emptyList())
    val records: StateFlow<List<CommandRecord>> = _records.asStateFlow()

    fun record(
        command: String,
        asRoot: Boolean,
        result: ShellResult,
        durationMillis: Long,
    ): CommandRecord {
        val record = synchronized(lock) {
            val entry = CommandRecord(
                sequence = nextSequence++,
                timestampMillis = System.currentTimeMillis(),
                command = command,
                asRoot = asRoot,
                exitCode = result.exitCode,
                stdout = truncate(result.stdout),
                stderr = truncate(result.stderr),
                timedOut = result.timedOut,
                durationMillis = durationMillis,
            )
            entries.addLast(entry)
            while (entries.size > CAPACITY) entries.removeFirst()
            _records.value = entries.toList()
            entry
        }
        return record
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _records.value = emptyList()
        }
    }

    /** The whole log as text, for the diagnostics export. */
    fun render(): String = _records.value.joinToString("\n\n") { record ->
        buildString {
            append(if (record.asRoot) "# " else "$ ")
            appendLine(record.command)
            appendLine("  ${record.summary}")
            record.stdout.trim().takeIf { it.isNotEmpty() }?.let {
                appendLine(it.prependIndent("  "))
            }
            record.stderr.trim().takeIf { it.isNotEmpty() }?.let {
                appendLine("  stderr:")
                appendLine(it.prependIndent("  "))
            }
        }
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_STREAM_CHARS) {
            text
        } else {
            text.take(MAX_STREAM_CHARS) + "\n... [truncated ${text.length - MAX_STREAM_CHARS} chars]"
        }
}
