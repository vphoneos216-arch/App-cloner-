package com.akash.appcloner.engine.stability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityTest {

    private fun elf(
        class64: Boolean,
        machine: Int,
        littleEndian: Boolean = true,
    ): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0x7F.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = if (class64) 2.toByte() else 1.toByte()
        bytes[5] = if (littleEndian) 1.toByte() else 2.toByte()
        if (littleEndian) {
            bytes[18] = (machine and 0xFF).toByte()
            bytes[19] = ((machine shr 8) and 0xFF).toByte()
        } else {
            bytes[18] = ((machine shr 8) and 0xFF).toByte()
            bytes[19] = (machine and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `aarch64 object is recognised`() {
        val header = ElfHeaderReader.parse(elf(class64 = true, machine = ElfHeader.EM_AARCH64))!!
        assertTrue(header.is64Bit)
        assertEquals("aarch64", header.machineName)
    }

    @Test
    fun `arm 32-bit object is recognised`() {
        val header = ElfHeaderReader.parse(elf(class64 = false, machine = ElfHeader.EM_ARM))!!
        assertTrue(!header.is64Bit)
        assertEquals("arm", header.machineName)
    }

    @Test
    fun `big endian headers are read the other way round`() {
        val header = ElfHeaderReader.parse(
            elf(class64 = true, machine = ElfHeader.EM_AARCH64, littleEndian = false),
        )!!
        assertEquals(ElfHeader.EM_AARCH64, header.machine)
    }

    @Test
    fun `non-elf content is rejected`() {
        assertNull(ElfHeaderReader.parse("not an elf file at all".toByteArray()))
        assertNull(ElfHeaderReader.parse(ByteArray(4)))
    }

    @Test
    fun `abi directories map to the architecture they must contain`() {
        assertEquals(ElfHeader.EM_AARCH64 to true, ElfHeader.expectedFor("arm64-v8a"))
        assertEquals(ElfHeader.EM_ARM to false, ElfHeader.expectedFor("armeabi-v7a"))
        assertNull(ElfHeader.expectedFor("riscv64"))
    }

    @Test
    fun `memory pressure uses the platform threshold`() {
        val ok = MemoryPressureWatcher.assess(
            availableBytes = 2_000_000_000L,
            thresholdBytes = 200_000_000L,
            totalBytes = 8_000_000_000L,
            platformLowMemoryFlag = false,
        )
        assertEquals(MemoryPressureLevel.OK, ok.level)

        val approaching = MemoryPressureWatcher.assess(
            availableBytes = 300_000_000L,
            thresholdBytes = 200_000_000L,
            totalBytes = 8_000_000_000L,
            platformLowMemoryFlag = false,
        )
        assertEquals(MemoryPressureLevel.APPROACHING, approaching.level)

        val atThreshold = MemoryPressureWatcher.assess(
            availableBytes = 150_000_000L,
            thresholdBytes = 200_000_000L,
            totalBytes = 8_000_000_000L,
            platformLowMemoryFlag = false,
        )
        assertEquals(MemoryPressureLevel.AT_THRESHOLD, atThreshold.level)
    }

    @Test
    fun `the platform low memory flag wins outright`() {
        val critical = MemoryPressureWatcher.assess(
            availableBytes = 4_000_000_000L,
            thresholdBytes = 200_000_000L,
            totalBytes = 8_000_000_000L,
            platformLowMemoryFlag = true,
        )
        assertEquals(MemoryPressureLevel.CRITICAL, critical.level)
    }
}
