package com.spake.brony.doubaoassistant.speech.audio

import android.media.SoundPool
import com.spake.brony.doubaoassistant.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceCuePlayerTest {
    private fun pool(player: VoiceCuePlayer) = player.javaClass.getDeclaredField("pool")
        .apply { isAccessible = true }.get(player) as SoundPool?

    @Test fun bothLocalCuesPlayAt70Percent() {
        VoiceCuePlayer(RuntimeEnvironment.getApplication()).use { player ->
            val sound = shadowOf(checkNotNull(pool(player)))
            for (resource in listOf(R.raw.haode, R.raw.wozai)) {
                sound.notifyResourceLoaded(resource, true)
                player.play(resource)
                val plays = sound.getResourcePlaybacks(resource)
                assertEquals(1, plays.size)
                plays.forEach {
                    assertEquals(0.7f, it.leftVolume, 0f)
                    assertEquals(0.7f, it.rightVolume, 0f)
                    assertEquals(0, it.loop)
                    assertEquals(1f, it.rate, 0f)
                }
            }
        }
    }

    @Test fun loadingKeepsOnlyTheLatestCommandAndStopCancelsPendingAudio() {
        VoiceCuePlayer(RuntimeEnvironment.getApplication()).use { player ->
            val sound = shadowOf(checkNotNull(pool(player)))
            player.play()
            player.play(R.raw.wozai)
            assertFalse(sound.wasResourcePlayed(R.raw.haode))
            sound.notifyResourceLoaded(R.raw.haode, true)
            assertFalse(sound.wasResourcePlayed(R.raw.haode))
            sound.notifyResourceLoaded(R.raw.wozai, true)
            assertEquals(1, sound.getResourcePlaybacks(R.raw.wozai).size)
        }
        VoiceCuePlayer(RuntimeEnvironment.getApplication()).use { player ->
            val sound = shadowOf(checkNotNull(pool(player)))
            player.play()
            player.stop()
            sound.notifyResourceLoaded(R.raw.haode, true)
            assertFalse(sound.wasResourcePlayed(R.raw.haode))
        }
    }

    @Test fun failedLoadDoesNotPlayAndCloseRejectsLateCallbacksAndCommands() {
        val player = VoiceCuePlayer(RuntimeEnvironment.getApplication())
        val sound = shadowOf(checkNotNull(pool(player)))
        try {
            player.play()
            sound.notifyResourceLoaded(R.raw.haode, false)
            assertFalse(sound.wasResourcePlayed(R.raw.haode))
            sound.notifyResourceLoaded(R.raw.haode, true)
            player.play()
            assertTrue(sound.wasResourcePlayed(R.raw.haode))
            sound.clearPlayed()
            player.close()
            assertNull(pool(player))
            sound.notifyResourceLoaded(R.raw.haode, true)
            player.play()
            assertFalse(sound.wasResourcePlayed(R.raw.haode))
        } finally { player.close() }
    }
}
