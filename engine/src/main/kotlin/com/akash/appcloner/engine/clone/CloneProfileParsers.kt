package com.akash.appcloner.engine.clone

/**
 * Android user types this engine distinguishes. The strings are the platform's own.
 *
 * [CLONE_PROFILE] is the one that matters: a profile of the main user, purpose-built by AOSP
 * for running a second instance of a single-account app. It is not a managed profile and
 * involves no device policy controller.
 */
object UserTypes {
    const val CLONE_PROFILE = "android.os.usertype.profile.CLONE"
    const val MANAGED_PROFILE = "android.os.usertype.profile.MANAGED"
    const val FULL_SECONDARY = "android.os.usertype.full.SECONDARY"
    const val FULL_SYSTEM = "android.os.usertype.full.SYSTEM"
}

/** An Android user with its type resolved, where the platform disclosed one. */
data class TypedUser(
    val id: Int,
    val name: String,
    val type: String?,
    val running: Boolean,
) {
    val isCloneProfile: Boolean get() = type == UserTypes.CLONE_PROFILE

    /**
     * A user that looks like an OEM dual-app container but does not report the AOSP clone
     * type. Several OEMs ship proprietary profile users (OnePlus 999, Samsung 95) that behave
     * similarly. Treated as a reuse candidate, never silently as a clone profile.
     */
    val isOemDualAppCandidate: Boolean
        get() = type == null && id != 0 && (
            name.contains("parallel", ignoreCase = true) ||
                name.contains("dual", ignoreCase = true) ||
                name.contains("clone", ignoreCase = true) ||
                name.contains("twin", ignoreCase = true)
            )
}

/**
 * Parses `dumpsys user`, which is the only place the user type is exposed to a shell.
 *
 * The output nests a `Type: <string>` line under each `UserInfo{id:name:flags}` header, so the
 * type is attributed to the most recent header seen. A user whose type line is absent gets a
 * null type rather than a guessed one - mistaking an OEM profile for an AOSP clone profile
 * would send clone creation down the wrong path.
 */
object DumpsysUserParser {

    private val headerRegex = Regex("""UserInfo\{(\d+):([^:]*):([0-9a-fA-F]*)}""")
    private val typeRegex = Regex("""^\s*(?:Type:|mType=|type=)\s*([\w.]+)""")

    fun parse(dumpsysOutput: String): List<TypedUser> {
        val users = mutableListOf<TypedUser>()
        var pendingId: Int? = null
        var pendingName = ""
        var pendingRunning = false

        fun flush(type: String?) {
            val id = pendingId ?: return
            users += TypedUser(id, pendingName, type, pendingRunning)
            pendingId = null
        }

        dumpsysOutput.lineSequence().forEach { line ->
            val header = headerRegex.find(line)
            if (header != null) {
                flush(null)
                pendingId = header.groupValues[1].toIntOrNull()
                pendingName = header.groupValues[2]
                pendingRunning = line.contains("running", ignoreCase = true)
                return@forEach
            }
            if (pendingId != null) {
                val type = typeRegex.find(line)?.groupValues?.get(1)
                if (type != null) {
                    flush(type)
                } else if (line.contains("State:", ignoreCase = true)) {
                    pendingRunning = pendingRunning ||
                        line.contains("RUNNING", ignoreCase = true)
                }
            }
        }
        flush(null)
        return users
    }

    /**
     * Every `android.os.usertype.*` string the platform mentions in `dumpsys user`.
     *
     * `dumpsys user` prints the device's user-type table, so a type absent here is one this ROM
     * does not define. Presence does not prove creation will succeed - a limit or a user
     * restriction can still refuse it - but absence is conclusive, and that is what stops the
     * engine from committing to a mechanism the ROM has never heard of.
     */
    fun supportedUserTypes(dumpsysOutput: String): Set<String> =
        Regex("android\\.os\\.usertype\\.[\\w.]+")
            .findAll(dumpsysOutput)
            .map { it.value }
            .toSet()

    /**
     * Merges `pm list users` with `dumpsys user`. The first is reliable for the user list, the
     * second is the only source of the type, and on some builds one of the two is unavailable.
     */
    fun merge(listUsers: List<AndroidUser>, typed: List<TypedUser>): List<TypedUser> {
        val typeById = typed.associateBy { it.id }
        val merged = listUsers.map { user ->
            val match = typeById[user.id]
            TypedUser(
                id = user.id,
                name = user.name.ifBlank { match?.name.orEmpty() },
                type = match?.type,
                running = user.running || match?.running == true,
            )
        }
        val known = merged.map { it.id }.toSet()
        return merged + typed.filterNot { it.id in known }
    }
}

/** Why creating a clone profile failed. Each case needs a different thing from the operator. */
enum class CloneProfileFailure {
    /** The per-parent limit is reached. Stock AOSP allows one clone profile. */
    LIMIT_REACHED,

    /** The ROM does not enable this user type. */
    TYPE_UNSUPPORTED,

    /** This build's `pm` does not accept the subcommand or flags. */
    COMMAND_UNSUPPORTED,

    /** A user restriction forbids adding a clone profile. */
    RESTRICTED,

    UNKNOWN,
}

/**
 * Classifies `pm create-user` output.
 *
 * The messages come from UserManagerService through a ServiceSpecificException, so they are
 * stable enough to key on, and anything unrecognised stays [CloneProfileFailure.UNKNOWN] with
 * the raw text preserved for the operator rather than being forced into a category.
 */
object CloneProfileErrorParser {

    fun classify(output: String): CloneProfileFailure {
        val text = output.lowercase()
        return when {
            "cannot add more profiles" in text || "max" in text && "profile" in text ->
                CloneProfileFailure.LIMIT_REACHED

            "unknown command" in text || "unknown option" in text ||
                "bad argument" in text -> CloneProfileFailure.COMMAND_UNSUPPORTED

            "user type" in text && ("not enabled" in text || "unknown" in text || "disabled" in text) ->
                CloneProfileFailure.TYPE_UNSUPPORTED

            "restriction" in text || "disallow" in text -> CloneProfileFailure.RESTRICTED

            else -> CloneProfileFailure.UNKNOWN
        }
    }

    fun remedyFor(failure: CloneProfileFailure): String = when (failure) {
        CloneProfileFailure.LIMIT_REACHED ->
            "Stock Android allows one clone profile per user. The existing one can hold one " +
                "copy of each app, so clone a different app into it. A second instance of the " +
                "same app needs max-allowed-per-parent raised in config_user_types, which takes " +
                "a resource overlay or a system_server hook."

        CloneProfileFailure.TYPE_UNSUPPORTED ->
            "This ROM does not enable the AOSP clone profile user type. Check whether the " +
                "device has an OEM dual-app profile that can be reused instead."

        CloneProfileFailure.COMMAND_UNSUPPORTED ->
            "This build's pm does not accept these flags. Some OEM builds move user creation " +
                "between `pm` and `cmd user`; the fallback is attempted automatically."

        CloneProfileFailure.RESTRICTED ->
            "A user restriction (no_add_clone_profile) blocks this. It is set by device policy " +
                "or by the OEM."

        CloneProfileFailure.UNKNOWN ->
            "Unrecognised failure. The exact command output is in the operation log."
    }
}

/** Parses `cmd package resolve-activity --brief`, which prints the component on its own line. */
object ResolveActivityParser {

    fun parse(output: String, packageName: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$packageName/") }
            .lastOrNull()
}

/** Parses `pm list packages -U`, which prints `package:<name> uid:<uid>`. */
object PackageUidParser {

    fun parse(output: String, packageName: String): Int? =
        output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:$packageName ") || it == "package:$packageName" }
            ?.substringAfter("uid:", "")
            ?.trim()
            ?.toIntOrNull()

    /** The app id is the part of a uid that is stable across users. */
    fun appIdOf(uid: Int): Int = uid % 100_000

    /** The uid the same app receives inside another user. */
    fun uidFor(userId: Int, appId: Int): Int = userId * 100_000 + appId
}
