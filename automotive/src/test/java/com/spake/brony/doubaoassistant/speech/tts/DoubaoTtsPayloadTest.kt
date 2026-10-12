package com.spake.brony.doubaoassistant.speech.tts

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoubaoTtsPayloadTest {
    @Test fun defaultVoiceUsesPcmAndSelectedSpeechRate() {
        val params = JSONObject(DoubaoTtsPlayer.sessionPayload(1)).getJSONObject("req_params")
        assertEquals("zh_female_vv_uranus_bigtts", params.getString("speaker"))
        assertEquals("pcm", params.getJSONObject("audio_params").getString("format"))
        assertEquals(24000, params.getJSONObject("audio_params").getInt("sample_rate"))
        assertEquals(0, params.getJSONObject("audio_params").getInt("speech_rate"))
        assertEquals(-20, JSONObject(DoubaoTtsPlayer.sessionPayload(0)).getJSONObject("req_params").getJSONObject("audio_params").getInt("speech_rate"))
        assertEquals(20, JSONObject(DoubaoTtsPlayer.sessionPayload(2)).getJSONObject("req_params").getJSONObject("audio_params").getInt("speech_rate"))
    }

    @Test fun textIsJsonEscapedInsteadOfInterpolatingRawQuotes() {
        val text = "他说：\"你好\"\n再见\\朋友"
        assertEquals(text, JSONObject(DoubaoTtsPlayer.textPayload(text)).getJSONObject("req_params").getString("text"))
    }
}
