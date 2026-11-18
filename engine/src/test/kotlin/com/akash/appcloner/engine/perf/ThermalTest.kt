package com.akash.appcloner.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTest {

    @Test
    fun `milli celsius is the common case`() {
        val (celsius, scale) = TemperatureConverter.convert(45_000L)
        assertEquals(45.0, celsius!!, 1e-9)
        assertEquals(TemperatureScale.MILLI_CELSIUS, scale)
    }

    @Test
    fun `deci celsius is not mistaken for a fraction of a degree`() {
        val (celsius, scale) = TemperatureConverter.convert(450L)
        assertEquals(45.0, celsius!!, 1e-9)
        assertEquals(TemperatureScale.DECI_CELSIUS, scale)
    }

    @Test
    fun `plain celsius passes through`() {
        val (celsius, scale) = TemperatureConverter.convert(42L)
        assertEquals(42.0, celsius!!, 1e-9)
        assertEquals(TemperatureScale.CELSIUS, scale)
    }

    @Test
    fun `implausible values are not converted`() {
        val (celsius, scale) = TemperatureConverter.convert(9_999_999L)
        assertNull(celsius)
        assertEquals(TemperatureScale.UNRECOGNISED, scale)
    }

    @Test
    fun `hottest soc zone ignores unrelated sensors`() {
        val snapshot = ThermalSnapshot(
            listOf(
                ThermalZoneReading("thermal_zone0", "battery", 30_000L, 30.0, TemperatureScale.MILLI_CELSIUS),
                ThermalZoneReading("thermal_zone1", "cpu-0-0", 52_000L, 52.0, TemperatureScale.MILLI_CELSIUS),
                ThermalZoneReading("thermal_zone2", "gpu-0", 48_000L, 48.0, TemperatureScale.MILLI_CELSIUS),
            ),
        )
        assertEquals("cpu-0-0", snapshot.hottestSocZone?.type)
    }

    @Test
    fun `throttling starts at moderate`() {
        assertTrue(!ThermalStatusNames.isThrottling(ThermalStatusNames.LIGHT))
        assertTrue(ThermalStatusNames.isThrottling(ThermalStatusNames.MODERATE))
        assertEquals("severe", ThermalStatusNames.describe(ThermalStatusNames.SEVERE))
    }
}
