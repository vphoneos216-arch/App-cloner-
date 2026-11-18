package com.akash.appcloner.engine.compat

/** Game engine families this detector can recognise from file names alone. */
enum class GameEngineKind {
    UNITY_IL2CPP,
    UNITY_MONO,
    UNITY_UNKNOWN_SCRIPTING,
    UNREAL,
    GODOT,
    COCOS2DX,
    NATIVE_UNKNOWN,
    NO_NATIVE_CODE,
}

/**
 * What the detector concluded and, importantly, the exact APK entries it concluded it from.
 * [evidence] holds real entry paths observed in the APK; nothing here is guessed from the
 * package name.
 */
data class EngineDetection(
    val kind: GameEngineKind,
    val evidence: List<String>,
    val notes: List<String> = emptyList(),
)

/**
 * Classifies an APK by the names of entries it contains. Pure - feed it the entry list from
 * [ApkInspector] or from a unit test.
 *
 * This identifies the engine family only. It says nothing about engine version, struct layout
 * or any address: those are per-build facts that have to come from a dump of the actual build.
 */
object GameEngineDetector {

    private const val IL2CPP_LIB = "libil2cpp.so"
    private const val UNITY_LIB = "libunity.so"
    private const val GLOBAL_METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
    private const val MANAGED_DIR = "assets/bin/Data/Managed/"

    fun detect(entries: Collection<String>): EngineDetection {
        val libNames = entries.filter { it.startsWith("lib/") }
        if (libNames.isEmpty()) {
            return EngineDetection(GameEngineKind.NO_NATIVE_CODE, emptyList())
        }

        fun libMatches(predicate: (String) -> Boolean): List<String> =
            libNames.filter { predicate(it.substringAfterLast('/')) }

        val il2cpp = libMatches { it == IL2CPP_LIB }
        val unity = libMatches { it == UNITY_LIB }
        val mono = libMatches { it.startsWith("libmono") }
        val unreal = libMatches { it.startsWith("libUE") || it.startsWith("libUnreal") }
        val godot = libMatches { it.startsWith("libgodot") }
        val cocos = libMatches { it.startsWith("libcocos") }

        val metadata = entries.filter { it == GLOBAL_METADATA }
        val managedAssemblies = entries.filter {
            it.startsWith(MANAGED_DIR) && it.endsWith(".dll")
        }.take(3)

        return when {
            il2cpp.isNotEmpty() || metadata.isNotEmpty() -> EngineDetection(
                kind = GameEngineKind.UNITY_IL2CPP,
                evidence = (il2cpp + unity + metadata).distinct(),
                notes = listOf(
                    "IL2CPP: names and offsets require dumping GameAssembly/libil2cpp against " +
                        "this build's global-metadata.dat. Check the metadata version first; " +
                        "it may be encrypted or packed.",
                ),
            )

            unity.isNotEmpty() && (mono.isNotEmpty() || managedAssemblies.isNotEmpty()) ->
                EngineDetection(
                    kind = GameEngineKind.UNITY_MONO,
                    evidence = (unity + mono + managedAssemblies).distinct(),
                    notes = listOf("Mono build: managed assemblies are readable without a dumper."),
                )

            unity.isNotEmpty() -> EngineDetection(
                kind = GameEngineKind.UNITY_UNKNOWN_SCRIPTING,
                evidence = unity,
                notes = listOf(
                    "libunity.so present but neither libil2cpp.so nor managed assemblies were " +
                        "found in the base APK. Scripting backend is undetermined - the payload " +
                        "may live in a split APK or an OBB.",
                ),
            )

            unreal.isNotEmpty() -> EngineDetection(
                kind = GameEngineKind.UNREAL,
                evidence = unreal,
                notes = listOf(
                    "Unreal: establish the engine version before anything else. FName layout " +
                        "and vector width differ between UE4 and UE5 builds.",
                ),
            )

            godot.isNotEmpty() -> EngineDetection(GameEngineKind.GODOT, godot)
            cocos.isNotEmpty() -> EngineDetection(GameEngineKind.COCOS2DX, cocos)

            else -> EngineDetection(
                kind = GameEngineKind.NATIVE_UNKNOWN,
                evidence = libNames.map { it.substringAfterLast('/') }.distinct().take(12),
                notes = listOf("No known engine marker. Treat as a custom native build."),
            )
        }
    }
}
