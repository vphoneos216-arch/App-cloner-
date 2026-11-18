package com.akash.appcloner.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineDetectorTest {

    @Test
    fun `il2cpp is identified from the library`() {
        val result = GameEngineDetector.detect(
            listOf("lib/arm64-v8a/libil2cpp.so", "lib/arm64-v8a/libunity.so"),
        )
        assertEquals(GameEngineKind.UNITY_IL2CPP, result.kind)
        assertTrue(result.evidence.any { it.endsWith("libil2cpp.so") })
    }

    @Test
    fun `il2cpp is identified from global metadata alone`() {
        val result = GameEngineDetector.detect(
            listOf(
                "lib/arm64-v8a/libunity.so",
                "assets/bin/Data/Managed/Metadata/global-metadata.dat",
            ),
        )
        assertEquals(GameEngineKind.UNITY_IL2CPP, result.kind)
    }

    @Test
    fun `mono is identified from managed assemblies`() {
        val result = GameEngineDetector.detect(
            listOf(
                "lib/arm64-v8a/libunity.so",
                "lib/arm64-v8a/libmonobdwgc-2.0.so",
                "assets/bin/Data/Managed/Assembly-CSharp.dll",
            ),
        )
        assertEquals(GameEngineKind.UNITY_MONO, result.kind)
    }

    @Test
    fun `unity with no scripting marker stays undetermined`() {
        val result = GameEngineDetector.detect(listOf("lib/arm64-v8a/libunity.so"))
        assertEquals(GameEngineKind.UNITY_UNKNOWN_SCRIPTING, result.kind)
        assertTrue(result.notes.isNotEmpty())
    }

    @Test
    fun `unreal is identified and carries a version warning`() {
        val result = GameEngineDetector.detect(listOf("lib/arm64-v8a/libUE4.so"))
        assertEquals(GameEngineKind.UNREAL, result.kind)
        assertTrue(result.notes.any { it.contains("version", ignoreCase = true) })
    }

    @Test
    fun `no libraries means no native code`() {
        val result = GameEngineDetector.detect(listOf("classes.dex", "AndroidManifest.xml"))
        assertEquals(GameEngineKind.NO_NATIVE_CODE, result.kind)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `unknown native build lists what it saw`() {
        val result = GameEngineDetector.detect(listOf("lib/arm64-v8a/libcustomengine.so"))
        assertEquals(GameEngineKind.NATIVE_UNKNOWN, result.kind)
        assertEquals(listOf("libcustomengine.so"), result.evidence)
    }
}
