package com.akash.appcloner.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiResolverTest {

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val arm32Device = listOf("armeabi-v7a", "armeabi")

    @Test
    fun `picks 64-bit first on a 64-bit device`() {
        val result = AbiResolver.resolve(listOf("armeabi-v7a", "arm64-v8a"), arm64Device)
        assertTrue(result is AbiResolution.Resolved)
        result as AbiResolution.Resolved
        assertEquals("arm64-v8a", result.abi)
        assertTrue(result.is64Bit)
    }

    @Test
    fun `falls to 32-bit when that is all the apk ships`() {
        val result = AbiResolver.resolve(listOf("armeabi-v7a"), arm64Device)
        result as AbiResolution.Resolved
        assertEquals("armeabi-v7a", result.abi)
        assertTrue(!result.is64Bit)
    }

    @Test
    fun `reports no match instead of guessing`() {
        val result = AbiResolver.resolve(listOf("arm64-v8a"), arm32Device)
        assertTrue(result is AbiResolution.NoMatchingAbi)
    }

    @Test
    fun `no native code is distinct from no match`() {
        assertEquals(AbiResolution.NoNativeCode, AbiResolver.resolve(emptyList(), arm64Device))
    }

    @Test
    fun `pinning 32-bit works when both sides provide it`() {
        val result = AbiResolver.resolveForced(
            listOf("arm64-v8a", "armeabi-v7a"),
            arm64Device,
            "armeabi-v7a",
        )
        result as AbiResolution.Resolved
        assertEquals("armeabi-v7a", result.abi)
        assertTrue(result.forced)
    }

    @Test
    fun `pinning fails loudly when the apk has no 32-bit libraries`() {
        val result = AbiResolver.resolveForced(listOf("arm64-v8a"), arm64Device, "armeabi-v7a")
        result as AbiResolution.ForcedAbiUnavailable
        assertEquals(AbiResolution.ForcedAbiUnavailable.Missing.APK, result.missingFrom)
    }

    @Test
    fun `pinning fails when the device is 64-bit only`() {
        val result = AbiResolver.resolveForced(
            listOf("arm64-v8a", "armeabi-v7a"),
            listOf("arm64-v8a"),
            "armeabi-v7a",
        )
        result as AbiResolution.ForcedAbiUnavailable
        assertEquals(AbiResolution.ForcedAbiUnavailable.Missing.DEVICE, result.missingFrom)
    }
}
