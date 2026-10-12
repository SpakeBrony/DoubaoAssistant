package com.spake.brony.doubaoassistant.speech.audio

import org.junit.Assert.*
import org.junit.Test

class VoiceLevelMeterTest {
    @Test fun quietIsStationaryAndLouderSamplesIncreaseLevel() {
        assertEquals(0f, VoiceLevelMeter.measure(ShortArray(160), 160))
        assertEquals(0f, VoiceLevelMeter.measure(ShortArray(160) { 30 }, 160))
        val low = VoiceLevelMeter.measure(ShortArray(160) { 500 }, 160)
        val high = VoiceLevelMeter.measure(ShortArray(160) { 5000 }, 160)
        assertTrue(low > 0f && high > low && high <= 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSampleCountIsRejected() { VoiceLevelMeter.measure(shortArrayOf(1), 2) }
}
