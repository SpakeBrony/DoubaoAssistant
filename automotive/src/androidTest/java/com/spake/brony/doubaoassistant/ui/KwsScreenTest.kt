package com.spake.brony.doubaoassistant.ui

import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import com.spake.brony.doubaoassistant.viewmodel.KwsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KwsScreenTest {
    private var previousCredentialPayload: String? = null
    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        previousCredentialPayload = instrumentation.targetContext.getSharedPreferences("credentials", 0).getString("payload", null)
        check(CredentialStore(instrumentation.targetContext).save(
            ServiceCredentials(apiKey = "test-speech-key", arkApiKey = "test-ark-key")))
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        KwsRepository.publish(KwsSnapshot())
        KwsRepository.setUiVisible(false)
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("credentials", 0)
            .edit().putString("payload", previousCredentialPayload).commit()
        KwsRepository.publish(KwsSnapshot())
        KwsRepository.setUiVisible(false)
    }

    @Test
    fun backgroundStateUpdatesReachTheScreenWithoutServiceBinding() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(KwsRepository.isUiVisible.value)
            val latest = KwsSnapshot(
                status = ListeningStatus.LISTENING,
                mode = AssistantMode.AWAKE,
                keyword = "你好豆包",
                detectionCount = 1,
            )
            // 模拟服务发布状态，不实际打开麦克风。
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                KwsRepository.publish(latest)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(KwsRepository.isUiVisible.value)
            onView(withId(R.id.toggle_listening)).check(matches(withContentDescription(R.string.voice_pause_chat)))
            onView(withId(R.id.listening_status)).check(matches(withText(R.string.voice_kws_waiting)))
            scenario.recreate()
            scenario.onActivity {
                assertTrue(KwsRepository.isUiVisible.value)
                assertEquals(latest, ViewModelProvider(it)[KwsViewModel::class.java].uiState.value.snapshot)
            }
            onView(withId(R.id.toggle_listening)).check(matches(withContentDescription(R.string.voice_pause_chat)))
        }
    }

    @Test
    fun recreatingTheScreenKeepsTheViewModelAndDoesNotStartRecording() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: KwsViewModel
            scenario.onActivity { original = ViewModelProvider(it)[KwsViewModel::class.java] }
            scenario.recreate()
            scenario.onActivity {
                val restored = ViewModelProvider(it)[KwsViewModel::class.java]
                assertSame(original, restored)
                assertFalse(restored.uiState.value.isListening)
            }
            onView(withId(R.id.toggle_listening)).check(matches(withContentDescription(R.string.voice_resume_chat)))
            onView(withId(R.id.listening_status)).check(matches(withText(R.string.voice_stopped)))
        }
    }
}
