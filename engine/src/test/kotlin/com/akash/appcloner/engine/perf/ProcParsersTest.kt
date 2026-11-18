package com.akash.appcloner.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProcParsersTest {

    private val memInfo = """
        MemTotal:        7903248 kB
        MemFree:          312456 kB
        MemAvailable:    2561032 kB
        Buffers:           12345 kB
        Cached:          1987654 kB
        SwapTotal:       4194304 kB
        SwapFree:        3145728 kB
    """.trimIndent()

    @Test
    fun `meminfo fields are read in kilobytes`() {
        val snapshot = MemInfoParser.parse(memInfo)
        assertEquals(7903248L, snapshot.totalKb)
        assertEquals(2561032L, snapshot.availableKb)
        assertEquals(3145728L, snapshot.swapFreeKb)
    }

    @Test
    fun `missing meminfo fields stay null`() {
        val snapshot = MemInfoParser.parse("MemTotal: 1024 kB")
        assertEquals(1024L, snapshot.totalKb)
        assertNull(snapshot.availableKb)
        assertNull(snapshot.usedKb)
    }

    @Test
    fun `used memory comes from available not from free`() {
        val snapshot = MemInfoParser.parse(memInfo)
        assertEquals(7903248L - 2561032L, snapshot.usedKb)
    }

    @Test
    fun `cpu load is the busy share between two samples`() {
        val before = CpuStatParser.parse("cpu  100 0 50 850 0 0 0 0").first()
        val after = CpuStatParser.parse("cpu  200 0 100 1700 0 0 0 0").first()
        val load = CpuStatParser.load(before, after)
        assertNotNull(load)
        assertEquals(0.15, load!!.busyFraction, 1e-9)
    }

    @Test
    fun `counters that do not advance give no reading rather than zero load`() {
        val sample = CpuStatParser.parse("cpu  100 0 50 850 0 0 0 0").first()
        assertNull(CpuStatParser.load(sample, sample))
    }

    @Test
    fun `iowait counts as idle`() {
        val before = CpuStatParser.parse("cpu  0 0 0 900 100 0 0 0").first()
        val after = CpuStatParser.parse("cpu  0 0 0 1800 200 0 0 0").first()
        assertEquals(0.0, CpuStatParser.load(before, after)!!.busyFraction, 1e-9)
    }

    @Test
    fun `per-cpu lines are differenced by label`() {
        val before = CpuStatParser.parse("cpu  0 0 0 100 0 0 0 0\ncpu0 0 0 0 100 0 0 0 0")
        val after = CpuStatParser.parse("cpu  50 0 0 150 0 0 0 0\ncpu0 25 0 0 175 0 0 0 0")
        val loads = CpuStatParser.loads(before, after)
        assertEquals(2, loads.size)
        assertEquals(0.5, loads.first { it.label == "cpu" }.busyFraction, 1e-9)
        assertEquals(0.25, loads.first { it.label == "cpu0" }.busyFraction, 1e-9)
    }

    // comm contains both a space and parentheses, which is why the parser works from the last ')'
    private val procStat =
        "1234 (my app (x)) S 1 1234 0 0 -1 4194304 100 0 0 0 42 13 0 0 20 0 7 0 500"

    @Test
    fun `process stat survives a comm with spaces and parentheses`() {
        val stat = ProcessStatParser.parseStat(procStat)
        assertNotNull(stat)
        assertEquals(1234, stat!!.pid)
        assertEquals("my app (x)", stat.comm)
        assertEquals(42L, stat.utime)
        assertEquals(13L, stat.stime)
        assertEquals(7L, stat.numThreads)
    }

    @Test
    fun `process cpu fraction is relative to one core`() {
        val before = ProcessStatParser.parseStat(procStat)!!
        val after = before.copy(utime = 120L, stime = 35L)
        // 155 - 55 = 100 jiffies over one second at 100 Hz: one core fully busy.
        val fraction = ProcessStatParser.cpuFraction(before, after, intervalJiffies = 100.0)
        assertEquals(1.0, fraction!!, 1e-9)
    }

    @Test
    fun `a restarted process gives no reading`() {
        val before = ProcessStatParser.parseStat(procStat)!!
        val after = before.copy(utime = 1L, stime = 0L)
        assertNull(ProcessStatParser.cpuFraction(before, after, 100.0))
    }

    @Test
    fun `process status yields resident set size`() {
        val status = ProcessStatParser.parseStatus(
            1234,
            """
            Name:	game
            VmRSS:	 1048576 kB
            RssAnon:	  524288 kB
            RssFile:	  262144 kB
            """.trimIndent(),
        )
        assertEquals(1048576L, status.vmRssKb)
        assertEquals(524288L, status.rssAnonKb)
    }
}
