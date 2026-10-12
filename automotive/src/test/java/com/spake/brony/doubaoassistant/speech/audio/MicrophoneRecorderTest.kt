package com.spake.brony.doubaoassistant.speech.audio

import android.app.Application
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class MicrophoneRecorderTest {
    @Test fun pcmUsesTheSameCallerBufferAndPreservesShortReadCount() {
        val application: Application = RuntimeEnvironment.getApplication()
        val microphone = MicrophoneRecorder(application::getString, 16000)
        val pcm = ShortArray(1600)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(buffer: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                assertSame(pcm, buffer)
                assertEquals(0, offset)
                assertEquals(1600, size)
                assertTrue(blocking)
                buffer[0] = 1234
                buffer[1] = -1234
                return 2
            }
        })
        try {
            microphone.start()
            assertEquals(2, microphone.read(pcm))
            assertEquals(1234.toShort(), pcm[0])
            assertEquals((-1234).toShort(), pcm[1])
        } finally {
            microphone.close()
            ShadowAudioRecord.clearSource()
        }
    }

    @Test fun startStopAndCloseKeepCaptureAndReleaseInOneOwner() {
        val application: Application = RuntimeEnvironment.getApplication()
        val microphone = MicrophoneRecorder(application::getString, 16000)
        try {
            microphone.start()
            assertTrue(microphone.isRecording)
            val audio = microphone.javaClass.getDeclaredField("recorder").apply { isAccessible = true }
                .get(microphone) as AudioRecord
            assertEquals(16000, audio.sampleRate)
            assertEquals(AudioFormat.CHANNEL_IN_MONO, audio.channelConfiguration)
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, audio.audioFormat)
            assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, audio.audioSource)
            assertTrue(microphone.stop())
            assertFalse(microphone.isRecording)
            assertEquals(AudioRecord.STATE_INITIALIZED, audio.state)
            assertTrue(microphone.stop())
            microphone.close()
            assertFalse(microphone.isRecording)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, audio.state)
            microphone.close()
            assertTrue(microphone.stop())
        } finally { microphone.close() }
    }

    @Test fun stopBeforeStartPreventsALateTaskFromReopeningTheMicrophone() {
        val application: Application = RuntimeEnvironment.getApplication()
        val microphone = MicrophoneRecorder(application::getString, 16000)
        try {
            assertTrue(microphone.stop())
            microphone.start()
            assertFalse(microphone.isRecording)
            assertNull(microphone.javaClass.getDeclaredField("recorder").apply { isAccessible = true }.get(microphone))
        } finally { microphone.close() }
    }
}
