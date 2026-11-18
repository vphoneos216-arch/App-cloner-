package com.akash.appcloner.engine.stability

/** The parts of an ELF header that decide whether a library can load at all. */
data class ElfHeader(
    val is64Bit: Boolean,
    val littleEndian: Boolean,
    val machine: Int,
) {
    val machineName: String
        get() = when (machine) {
            EM_ARM -> "arm"
            EM_AARCH64 -> "aarch64"
            EM_386 -> "x86"
            EM_X86_64 -> "x86_64"
            else -> "machine 0x${machine.toString(16)}"
        }

    companion object {
        const val EM_ARM = 0x28
        const val EM_AARCH64 = 0xB7
        const val EM_386 = 0x03
        const val EM_X86_64 = 0x3E

        /** ABI directory names, mapped to the ELF machine and word size they must contain. */
        fun expectedFor(abiDir: String): Pair<Int, Boolean>? = when (abiDir) {
            "arm64-v8a" -> EM_AARCH64 to true
            "armeabi-v7a", "armeabi" -> EM_ARM to false
            "x86_64" -> EM_X86_64 to true
            "x86" -> EM_386 to false
            else -> null
        }
    }
}

/**
 * Reads an ELF header out of the first bytes of a shared object.
 *
 * This is the cheapest real check that a native library matches the ABI directory it sits in.
 * A 32-bit object shipped under `lib/arm64-v8a/` is a packaging fault that shows up at runtime
 * as a bare "dlopen failed", with nothing in the message to say why.
 */
object ElfHeaderReader {

    private const val HEADER_BYTES = 20

    fun parse(bytes: ByteArray): ElfHeader? {
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0] != 0x7F.toByte() ||
            bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() ||
            bytes[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val is64Bit = when (bytes[4].toInt()) {
            1 -> false
            2 -> true
            else -> return null
        }
        val littleEndian = when (bytes[5].toInt()) {
            1 -> true
            2 -> false
            else -> return null
        }
        val machine = if (littleEndian) {
            (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
        }
        return ElfHeader(is64Bit, littleEndian, machine)
    }

    const val MIN_BYTES = HEADER_BYTES
}
