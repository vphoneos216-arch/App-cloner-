package com.akash.appcloner.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {

    @Test
    fun `obb names yield role version and package`() {
        val file = ObbNameParser.parse("main.1234.com.dts.freefiremax.obb", 100L)
        assertEquals(ObbRole.MAIN, file.role)
        assertEquals(1234L, file.versionCode)
        assertEquals("com.dts.freefiremax", file.packageName)
    }

    @Test
    fun `patch files are recognised`() {
        assertEquals(ObbRole.PATCH, ObbNameParser.parse("patch.9.com.example.obb").role)
    }

    @Test
    fun `unrecognised names do not get an invented version`() {
        val file = ObbNameParser.parse("assets.obb")
        assertEquals(ObbRole.UNKNOWN, file.role)
        assertNull(file.versionCode)
    }

    @Test
    fun `stale expansion files are detected after a game update`() {
        val layout = ObbLayout(
            directory = "/storage/emulated/0/Android/obb/com.example",
            exists = true,
            files = listOf(ObbNameParser.parse("main.1234.com.example.obb", 10L)),
            totalBytes = 10L,
            installedVersionCode = 2000L,
        )
        assertFalse(layout.isConsistentWithInstall)
        assertEquals(1, layout.staleFiles.size)
    }

    @Test
    fun `matching version codes are consistent`() {
        val layout = ObbLayout(
            directory = "d",
            exists = true,
            files = listOf(ObbNameParser.parse("main.2000.com.example.obb", 10L)),
            totalBytes = 10L,
            installedVersionCode = 2000L,
        )
        assertTrue(layout.isConsistentWithInstall)
    }

    @Test
    fun `per-user paths are separate trees`() {
        assertEquals(
            "/storage/emulated/11/Android/obb/com.example",
            StoragePaths.obbDir(11, "com.example"),
        )
        assertEquals("/data/user/11/com.example", StoragePaths.privateDataDir(11, "com.example"))
    }
}
