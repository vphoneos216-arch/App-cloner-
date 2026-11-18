package com.akash.appcloner.engine.clone

/** One entry of `pm list users`. */
data class AndroidUser(
    val id: Int,
    val name: String,
    val flagsHex: String?,
    val running: Boolean,
)

/**
 * Parsing of `pm` and `am` output. Pure, so the shapes these commands return are pinned by
 * unit tests instead of being discovered on a device.
 *
 * Every parser returns null on an unrecognised shape rather than a default. A silently wrong
 * user id would install a clone into the wrong user.
 */
object PmOutputParser {

    private val createdUserRegex = Regex("""Success:\s*created user id\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val userInfoRegex = Regex("""UserInfo\{(\d+):([^:]*):([0-9a-fA-F]*)\}(.*)""")
    private val maxUsersRegex = Regex("""(\d+)\s*$""")

    fun parseCreatedUserId(stdout: String): Int? =
        createdUserRegex.find(stdout)?.groupValues?.get(1)?.toIntOrNull()

    fun parseUserList(stdout: String): List<AndroidUser> =
        stdout.lineSequence().mapNotNull { line ->
            val match = userInfoRegex.find(line) ?: return@mapNotNull null
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            AndroidUser(
                id = id,
                name = match.groupValues[2],
                flagsHex = match.groupValues[3].ifBlank { null },
                running = match.groupValues[4].contains("running", ignoreCase = true),
            )
        }.toList()

    /** `pm get-max-users` prints a trailing count; anything else yields null. */
    fun parseMaxUsers(stdout: String): Int? =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { maxUsersRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** `pm install-create` prints `Success: created install session [1234567]`. */
    fun parseSessionId(stdout: String): Int? =
        Regex("""\[(\d+)]""").find(stdout)?.groupValues?.get(1)?.toIntOrNull()

    fun isSuccess(stdout: String): Boolean = stdout.contains("Success", ignoreCase = true)
}
