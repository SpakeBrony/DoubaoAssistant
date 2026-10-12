package com.spake.brony.doubaoassistant.speech.kws

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KwsModelTest {
    @Test
    fun recognizerReplacesDynamicKeywordsAndReleasesWithoutWakingOnSilence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recognizer = KwsRecognizer(context.assets, context::getString)
        try {
            recognizer.start("豆包")
            assertFalse(recognizer.updateName("豆包"))
            for (name in listOf("小白", "重庆", "豆包")) {
                assertTrue(recognizer.updateName(name))
                repeat(30) {
                    recognizer.process(ShortArray(1600), 1600, isActive = { true }) { keyword, _ ->
                        throw AssertionError("Silence woke keyword: $keyword")
                    }
                }
            }
        } finally {
            recognizer.close()
            recognizer.close()
        }
    }

    @Test
    fun packagedModelLoadsAndSilenceDoesNotWakeIt() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val spotter = KwsModel.create(assets)
        try {
            for (name in listOf("豆包", "小白", "重庆", "豆包")) {
                val stream = spotter.createStream(checkNotNull(KwsKeywords.create(assets, name)))
                try {
                    assertTrue(stream.ptr != 0L)
                    repeat(30) {
                        stream.acceptWaveform(FloatArray(1600), KwsModel.SAMPLE_RATE)
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream)
                            assertTrue(spotter.getResult(stream).keyword.isEmpty())
                        }
                    }
                } finally { stream.release() }
            }
        } finally {
            spotter.release()
        }
    }
}
