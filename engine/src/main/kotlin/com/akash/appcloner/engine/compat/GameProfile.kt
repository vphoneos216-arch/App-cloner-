package com.akash.appcloner.engine.compat

/**
 * Where an identifier in this registry came from. Nothing in the registry is treated as
 * established until [GameProfileRegistry.resolveInstalled] has matched it against the
 * package manager on the device in hand.
 */
enum class Provenance {
    /** Publicly documented store identifier. Still verified against PackageManager before use. */
    PUBLIC_STORE_ID,

    /** Supplied by the operator for this device. */
    USER_SUPPLIED,
}

/**
 * A candidate target. This carries identity and launch hints only - no offsets, no struct
 * layouts, no version-specific constants, because none of those can be known from a package
 * name and inventing them is how hours get lost.
 */
data class GameProfile(
    val id: String,
    val displayName: String,
    val candidatePackages: List<String>,
    val provenance: Provenance = Provenance.PUBLIC_STORE_ID,
    val notes: String? = null,
)

/** A profile that has been confirmed present on this device. */
data class ResolvedGame(
    val profile: GameProfile?,
    val packageName: String,
    val label: String,
)

object GameProfileRegistry {

    /**
     * Battle-royale titles named in the product spec, plus the regional variants that share a
     * build lineage. Package names are public store identifiers; several titles ship under
     * different identifiers per region, so a miss here is normal and never fatal - the clone
     * flow works off whatever the package manager actually reports.
     */
    val profiles: List<GameProfile> = listOf(
        GameProfile(
            id = "free_fire",
            displayName = "Free Fire",
            candidatePackages = listOf("com.dts.freefireth"),
        ),
        GameProfile(
            id = "free_fire_max",
            displayName = "Free Fire MAX",
            candidatePackages = listOf("com.dts.freefiremax"),
        ),
        GameProfile(
            id = "pubg_mobile",
            displayName = "PUBG Mobile",
            candidatePackages = listOf(
                "com.tencent.ig",
                "com.pubg.krmobile",
                "com.vng.pubgmobile",
                "com.rekoo.pubgm",
            ),
            notes = "Region builds differ in package name and in OBB naming.",
        ),
        GameProfile(
            id = "bgmi",
            displayName = "Battlegrounds Mobile India",
            candidatePackages = listOf("com.pubg.imobile"),
        ),
        GameProfile(
            id = "cod_mobile",
            displayName = "Call of Duty: Mobile",
            candidatePackages = listOf(
                "com.activision.callofduty.shooter",
                "com.garena.game.codm",
                "com.tencent.tmgp.cod",
            ),
        ),
        GameProfile(
            id = "fortnite",
            displayName = "Fortnite",
            candidatePackages = listOf("com.epicgames.fortnite"),
            notes = "Sideloaded on Android; not distributed through Google Play. " +
                "Clone support depends on the installer flow, not on the store.",
        ),
    )

    private val byPackage: Map<String, GameProfile> =
        profiles.flatMap { p -> p.candidatePackages.map { it to p } }.toMap()

    fun profileForPackage(packageName: String): GameProfile? = byPackage[packageName]

    /**
     * Turns whatever is actually installed into the working set. [installed] is the real
     * package list from the device; profiles that match nothing installed are simply absent.
     */
    fun resolveInstalled(installed: Map<String, String>): List<ResolvedGame> =
        installed.map { (pkg, label) -> ResolvedGame(profileForPackage(pkg), pkg, label) }

    /** Known titles with nothing installed to back them, for an honest "not found" list. */
    fun missingFrom(installedPackages: Set<String>): List<GameProfile> =
        profiles.filter { p -> p.candidatePackages.none { it in installedPackages } }
}
