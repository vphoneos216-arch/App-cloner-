package com.akash.appcloner.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStatsParsersTest {

    private val latencyDump = """
        16666667
        0	1000000000	0
        0	1016666667	0
        0	1033333334	0
        0	0	0
        0	9223372036854775807	0
    """.trimIndent()

    @Test
    fun `latency dump yields completed frames only`() {
        val window = SurfaceFlingerLatencyParser.parse(latencyDump, "SurfaceView[com.example]")
        assertNotNull(window)
        assertEquals(3, window!!.presentTimestampsNanos.size)
        assertEquals(16_666_667L, window.refreshPeriodNanos)
        assertEquals(60.0, FramePacing.analyse(window)!!.fps, 0.01)
    }

    @Test
    fun `latency dump with no completed frames yields nothing`() {
        val dump = "16666667\n0\t0\t0\n0\t0\t0\n"
        assertNull(SurfaceFlingerLatencyParser.parse(dump, null))
    }

    @Test
    fun `layer choice prefers the game surface and refuses to guess`() {
        val listing = """
            SurfaceView[com.example.game/com.example.game.MainActivity]#0
            com.example.game/com.example.game.MainActivity#0
            StatusBar#0
        """.trimIndent()
        val chosen = SurfaceFlingerLatencyParser.chooseLayer(listing, "com.example.game")
        assertEquals("SurfaceView[com.example.game/com.example.game.MainActivity]#0", chosen)
        assertNull(SurfaceFlingerLatencyParser.chooseLayer(listing, "com.other.game"))
    }

    @Test
    fun `gfxinfo skips rows the platform marked as not comparable`() {
        val dump = """
            Some header
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,
            0,1,2,3,4,5,6,7,8,9,10,11,12,1000000000,
            1,1,2,3,4,5,6,7,8,9,10,11,12,1008333333,
            0,1,2,3,4,5,6,7,8,9,10,11,12,1016666667,
            0,1,2,3,4,5,6,7,8,9,10,11,12,1033333334,
            ---PROFILEDATA---
        """.trimIndent()
        val window = GfxInfoFrameStatsParser.parse(dump)
        assertNotNull(window)
        assertEquals(3, window!!.presentTimestampsNanos.size)
        assertNull(window.refreshPeriodNanos)
    }

    @Test
    fun `gfxinfo without a profile block yields nothing`() {
        assertNull(GfxInfoFrameStatsParser.parse("** Graphics info **\nno data"))
    }
}
