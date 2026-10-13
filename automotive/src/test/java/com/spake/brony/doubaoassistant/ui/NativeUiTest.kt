package com.spake.brony.doubaoassistant.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import com.spake.brony.doubaoassistant.data.repository.TestCredentialKeys
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ChatTopic
import com.spake.brony.doubaoassistant.data.model.ConversationSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import com.spake.brony.doubaoassistant.data.model.OverlayStyle
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.data.repository.AsrRepository
import com.spake.brony.doubaoassistant.speech.asr.AsrController
import com.spake.brony.doubaoassistant.databinding.ActivityMainBinding
import com.spake.brony.doubaoassistant.databinding.ActivityPermissionBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.spake.brony.doubaoassistant.viewmodel.KwsViewModel
import com.spake.brony.doubaoassistant.viewmodel.PermissionViewModel
import com.spake.brony.doubaoassistant.service.KwsService
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeUiTest {
    private fun lastReplyId(): String? = ConversationRepository.snapshot.value.content.messages
        .lastOrNull { it.isAssistant && it.previousResponseId != null }?.previousResponseId

    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        TestCredentialKeys.install()
        check(CredentialStore(application).save(ServiceCredentials(appId = "test-app-id", accessToken = "test-token", arkApiKey = "test-ark-key")))
        ConversationRepository.reset()
        AsrRepository.clear()
        application.getSharedPreferences("assistant", 0).edit().clear().commit()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        KwsRepository.publish(KwsSnapshot())
        KwsRepository.publishAudioLevel(0f)
        KwsRepository.setUiVisible(false)
    }

    @After fun tearDown() {
        ConversationRepository.reset()
        AsrRepository.clear()
        KwsRepository.publish(KwsSnapshot())
        KwsRepository.publishAudioLevel(0f)
        KwsRepository.setUiVisible(false)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun answerModePersistsAndSettingsGroupControlsWithHorizontalActionsInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val preferences = AssistantPreferences(application)
        assertEquals(AnswerMode.CHAT, preferences.answerMode)
        for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            preferences.appearance = appearance
            AppearanceTheme.apply(appearance)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                activity.openSettings()
                with(binding.settings) {
                    for ((mode, button, detail) in listOf(
                        Triple(AnswerMode.SINGLE, answerModeSingle, R.string.voice_answer_mode_single_detail),
                        Triple(AnswerMode.CHAT, answerModeChat, R.string.voice_answer_mode_chat_detail))) {
                        button.performClick()
                        binding.executePendingBindings()
                        assertTrue(button.isSelected)
                        assertEquals(mode, preferences.answerMode)
                        assertEquals(mode, ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.answerMode)
                        assertEquals(activity.getString(detail), answerModeDetail.text)
                        assertNull(shadowOf(activity).nextStartedService)
                    }
                    val orderedSections = listOf(appearanceSection, layoutSection, speedSection, replyStyleSection,
                        answerModeSection, captionsSection, backgroundChatSection,
                        nameSection, credentialsSection, historySection, permissionSection)
                    assertEquals(orderedSections.map { settingsColumn.indexOfChild(it) }.sorted(),
                        orderedSections.map { settingsColumn.indexOfChild(it) })
                    for ((width, height) in listOf(1280 to 720, 800 to 360)) {
                        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        binding.root.layout(0, 0, width, height)
                        shadowOf(Looper.getMainLooper()).idle()
                        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        binding.root.layout(0, 0, width, height)
                        for ((row, button, detail) in listOf(Triple(nameSection, changeName, nameDetail),
                            Triple(credentialsSection, editCredentials, credentialsDetail),
                            Triple(historySection, clearHistory, historyDetail))) {
                            val leftColumn = detail.parent as ViewGroup
                            assertTrue(button.left >= leftColumn.right)
                            assertTrue(kotlin.math.abs(button.top + button.bottom - row.height) <= 1)
                            assertTrue(button.width >= 48)
                            assertTrue(leftColumn.width > 0)
                        }
                    }
                    answerModeSingle.performClick()
                }
            } finally { controller.pause().stop().destroy() }
        }
        val recreated = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertEquals(AnswerMode.SINGLE, ViewModelProvider(recreated.get())[KwsViewModel::class.java].uiState.value.answerMode)
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun replyStyleOptionsRefreshImmediatelyPersistAndDoNotRestartVoiceInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val preferences = AssistantPreferences(application)
        assertEquals(ReplyStyle.SIMPLE, preferences.replyStyle)
        for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            preferences.appearance = appearance
            AppearanceTheme.apply(appearance)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                activity.openSettings()
                for ((style, button) in listOf(ReplyStyle.MINIMAL to binding.settings.replyStyleMinimal,
                    ReplyStyle.SIMPLE to binding.settings.replyStyleSimple, ReplyStyle.DETAILED to binding.settings.replyStyleDetailed)) {
                    button.performClick()
                    binding.executePendingBindings()
                    assertTrue(button.isSelected)
                    assertEquals(style, preferences.replyStyle)
                    assertEquals(style, ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.replyStyle)
                    assertNull(shadowOf(activity).nextStartedService)
                }
            } finally { controller.pause().stop().destroy() }
        }
        val recreated = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertEquals(ReplyStyle.DETAILED, ViewModelProvider(recreated.get())[KwsViewModel::class.java].uiState.value.replyStyle)
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun closedMicrophoneKeepsChatControlsIndependentAndEnterSendsWithoutOpeningCapture() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            assertEquals("暂停聊天", binding.toggleChat.contentDescription)
            assertTrue(binding.toggleChat.isEnabled)
            assertEquals(activity.getString(R.string.voice_stopped), activity.statusText(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value))
            binding.toggleChat.performClick()
            assertEquals(KwsService.ACTION_PAUSE, shadowOf(activity).nextStartedService.action)
            KwsRepository.publish(KwsSnapshot(chatPaused = true))
            shadowOf(Looper.getMainLooper()).idle()
            binding.executePendingBindings()
            assertEquals("恢复聊天", binding.toggleChat.contentDescription)
            assertEquals(View.GONE, binding.messageInput.visibility)
            assertEquals(View.VISIBLE, binding.listeningStatus.visibility)
            assertEquals(activity.getString(R.string.voice_paused), binding.listeningStatus.text.toString())
            binding.messageInput.setText("暂停时不应发送")
            binding.messageInput.onEditorAction(EditorInfo.IME_ACTION_SEND)
            assertNull(shadowOf(activity).nextStartedService)
            assertEquals("暂停时不应发送", binding.messageInput.text.toString())
            assertEquals(activity.getString(R.string.voice_paused), activity.statusText(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value))
            binding.toggleChat.performClick()
            assertEquals(KwsService.ACTION_RESUME, shadowOf(activity).nextStartedService.action)
            KwsRepository.publish(KwsSnapshot())
            shadowOf(Looper.getMainLooper()).idle()
            binding.executePendingBindings()
            assertEquals(View.VISIBLE, binding.messageInput.visibility)
            assertEquals(View.GONE, binding.listeningStatus.visibility)
            assertEquals(activity.getString(R.string.voice_message_hint), binding.messageInput.hint)
            binding.messageInput.setText("  ")
            binding.messageInput.onEditorAction(EditorInfo.IME_ACTION_SEND)
            assertNull(shadowOf(activity).nextStartedService)
            binding.messageInput.setText("我输入的问题")
            binding.messageInput.onEditorAction(EditorInfo.IME_ACTION_SEND)
            val sent = shadowOf(activity).nextStartedService
            assertEquals(KwsService.ACTION_SEND_TEXT, sent.action)
            assertEquals("我输入的问题", sent.getStringExtra(KwsService.EXTRA_TEXT))
            assertEquals("", binding.messageInput.text.toString())
            assertFalse(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.isListening)
            binding.messageInput.setText("关闭麦克风，豆包")
            binding.messageInput.requestFocus()
            binding.messageInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            binding.messageInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            assertEquals("关闭麦克风，豆包", shadowOf(activity).nextStartedService.getStringExtra(KwsService.EXTRA_TEXT))
            assertNull(shadowOf(activity).nextStartedService)
            KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING,
                mode = AssistantMode.AWAKE, asrRequested = true))
            shadowOf(Looper.getMainLooper()).idle()
            binding.executePendingBindings()
            assertEquals(View.GONE, binding.messageInput.visibility)
            assertEquals(View.VISIBLE, binding.listeningStatus.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun credentialsAreRequiredEvenWithMicrophoneAndAppIdTokenSavesBeforeEnteringHome() {
        application.getSharedPreferences("credentials", 0).edit().clear().commit()
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val direct = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertNull(direct.get().findViewById<View>(R.id.voice_root))
            assertEquals(PermissionActivity::class.java.name, shadowOf(direct.get()).nextStartedActivity.component?.className)
        } finally { direct.pause().stop().destroy() }
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityPermissionBinding>(activity.findViewById(R.id.permission_root))!!
            assertNull(shadowOf(activity).nextStartedActivity)
            binding.speechAppId.setText("123")
            binding.speechAccessToken.setText("")
            binding.arkApiKey.setText("")
            activity.requestMicrophone()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(CredentialStore(application).isConfigured)
            assertNotNull(ViewModelProvider(activity)[PermissionViewModel::class.java].uiState.value.error)
            binding.speechAccessToken.setText(" test-user-token ")
            binding.arkApiKey.setText(" test-user-ark ")
            activity.requestMicrophone()
            assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            val saved = CredentialStore(application).load()
            assertEquals("", saved.apiKey)
            assertEquals("123", saved.appId)
            assertEquals("test-user-token", saved.accessToken)
            assertEquals("test-user-ark", saved.arkApiKey)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun editingCredentialsShowsSavedValuesAndKeepsDraftAcrossRecreationWithoutAutoEnteringHome() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(PermissionActivity::class.java,
            Intent(application, PermissionActivity::class.java).putExtra(PermissionActivity.EXTRA_EDIT_CREDENTIALS, true)).setup()
        try {
            var activity = controller.get()
            var binding = DataBindingUtil.getBinding<ActivityPermissionBinding>(activity.findViewById(R.id.permission_root))!!
            assertEquals("test-app-id", binding.speechAppId.text.toString())
            assertEquals("test-token", binding.speechAccessToken.text.toString())
            assertEquals("test-ark-key", binding.arkApiKey.text.toString())
            assertFalse(binding.speechAccessToken.isSaveEnabled)
            binding.speechAccessToken.setText("updated-token")
            controller.recreate()
            activity = controller.get()
            binding = DataBindingUtil.getBinding(activity.findViewById(R.id.permission_root))!!
            assertEquals("updated-token", binding.speechAccessToken.text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            activity.requestMicrophone()
            assertEquals("updated-token", CredentialStore(application).load().accessToken)
            assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsOpenCredentialEditorAndStopVoiceService() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.openCredentials()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(PermissionActivity::class.java.name, intent.component?.className)
            assertTrue(intent.getBooleanExtra(PermissionActivity.EXTRA_EDIT_CREDENTIALS, false))
            assertEquals(KwsService::class.java.name, shadowOf(activity).nextStoppedService.component?.className)
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingPermissionCannotOpenHomeEvenThroughADirectActivityLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertNull(activity.findViewById<View>(R.id.voice_root))
            assertEquals(PermissionActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            assertFalse(KwsRepository.isUiVisible.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun grantingMicrophoneInSystemPermissionCallbackEntersHomeEvenIfNotificationsAreDenied() {
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertNull(shadowOf(activity).lastRequestedPermission)
            activity.requestMicrophone()
            val request = shadowOf(activity).lastRequestedPermission
            assertTrue(Manifest.permission.RECORD_AUDIO in request.requestedPermissions)
            assertTrue(Manifest.permission.POST_NOTIFICATIONS in request.requestedPermissions)
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                request.requestedPermissions.map { if (it == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }.toIntArray())
            assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun denyingMicrophoneInSystemPermissionCallbackNeverEntersHome() {
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.requestMicrophone()
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
            val launches = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
            assertFalse(launches.any { it.component?.className == MainActivity::class.java.name })
            assertFalse(activity.isFinishing)
            val state = ViewModelProvider(activity)[PermissionViewModel::class.java].uiState.value
            assertFalse(state.granted)
            assertTrue(state.requestedBefore)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [28]) fun olderAndroidRequestsOnlyMicrophone() {
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            controller.get().requestMicrophone()
            assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), shadowOf(controller.get()).lastRequestedPermission.requestedPermissions)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun grantedPermissionSkipsOnboardingAndRequestsHomeAutoStart() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val intent = shadowOf(controller.get()).nextStartedActivity
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false))
            assertTrue(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun permissionRevokedWhileAwayReturnsToTheGate() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.pause().stop()
            shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
            controller.start().resume()
            assertEquals(PermissionActivity::class.java.name, shadowOf(controller.get()).nextStartedActivity.component?.className)
            assertFalse(KwsRepository.isUiVisible.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun permanentDenialOpensRealApplicationSettingsAndGrantOnReturnEntersHome() {
        AssistantPreferences(application).microphoneRequested = true
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            controller.get().requestMicrophone()
            val settings = shadowOf(controller.get()).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
            assertEquals("package:${application.packageName}", settings.data.toString())
            controller.pause().stop()
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            controller.start().resume()
            assertEquals(MainActivity::class.java.name, shadowOf(controller.get()).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun themeAndCaptionPreferencesSurviveANewScreen() {
        val preferences = AssistantPreferences(application)
        preferences.appearance = Appearance.DARK
        preferences.captions = false
        preferences.speed = 2
        AppearanceTheme.apply(preferences.appearance)
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val state = ViewModelProvider(controller.get())[KwsViewModel::class.java].uiState.value
            assertEquals(Appearance.DARK, state.appearance)
            assertFalse(state.captions)
            assertEquals(2, state.speed)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun reusingHomeKeepsTheViewModelAndDoesNotRestartOrUnmuteAnExistingListener() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.MUTED))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val original = ViewModelProvider(activity)[KwsViewModel::class.java]
            activity.toggleCaptions()
            activity.openSettings()
            controller.pause().newIntent(Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)).resume()
            assertSame(original, ViewModelProvider(activity)[KwsViewModel::class.java])
            assertFalse(original.uiState.value.captions)
            assertFalse(activity.intent.hasExtra(MainActivity.EXTRA_AUTO_START))
            assertTrue(original.uiState.value.settingsOpen)
            assertEquals(AssistantMode.MUTED, KwsRepository.snapshot.value.mode)
            assertNull(shadowOf(activity).nextStartedService)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun destroyedServiceNoLongerObservesPageVisibilityOrSettings() {
        val controller = Robolectric.buildService(KwsService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        val marker = KwsSnapshot(status = ListeningStatus.ERROR, error = "销毁后的状态")
        KwsRepository.publish(marker)
        KwsRepository.setUiVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(marker, KwsRepository.snapshot.value)
    }

    @Test fun backgroundChatSwitchDefaultsOffPersistsAndRequestsPermissionWithoutOpeningMicrophone() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSettings.setCanDrawOverlays(false)
        val preferences = AssistantPreferences(application)
        assertFalse(preferences.backgroundChat)
        var controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            var binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            activity.openSettings()
            binding.executePendingBindings()
            assertFalse(binding.settings.backgroundChatSwitch.isChecked)
            assertEquals(View.GONE, binding.settings.overlayStyleSection.visibility)
            binding.settings.backgroundChatSwitch.performClick()
            binding.executePendingBindings()
            assertTrue(binding.settings.backgroundChatSwitch.isChecked)
            assertEquals(View.VISIBLE, binding.settings.overlayStyleSection.visibility)
            assertTrue(binding.settings.overlayStyleDetailed.isSelected)
            binding.settings.overlayStyleCompact.performClick()
            binding.executePendingBindings()
            assertTrue(binding.settings.overlayStyleCompact.isSelected)
            assertFalse(binding.settings.overlayStyleDetailed.isSelected)
            assertEquals(OverlayStyle.COMPACT, preferences.overlayStyle)
            assertTrue(AssistantPreferences(application).backgroundChat)
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(activity).nextStartedActivity.action)
            assertNull(shadowOf(activity).nextStartedService)
            controller.pause().stop().destroy()
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            activity = controller.get()
            binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            activity.openSettings()
            binding.executePendingBindings()
            assertTrue(binding.settings.backgroundChatSwitch.isChecked)
            assertTrue(binding.settings.overlayStyleCompact.isSelected)
            binding.settings.overlayStyleDetailed.performClick()
            binding.executePendingBindings()
            assertTrue(binding.settings.overlayStyleDetailed.isSelected)
            assertFalse(binding.settings.overlayStyleCompact.isSelected)
            assertEquals(OverlayStyle.DETAILED, preferences.overlayStyle)
            binding.settings.backgroundChatSwitch.performClick()
            binding.executePendingBindings()
            assertFalse(binding.settings.backgroundChatSwitch.isChecked)
            assertEquals(View.GONE, binding.settings.overlayStyleSection.visibility)
            assertFalse(AssistantPreferences(application).backgroundChat)
            assertNull(shadowOf(activity).nextStartedService)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
            preferences.backgroundChat = false
            ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test fun settingsDoNotPauseRestartOrClearChatAndTheCaptionSwitchUpdatesPreferences() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val listening = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE, asrRequested = true)
        val recognition = AsrSnapshot(status = AsrStatus.LISTENING, conversation = ConversationContent(liveText = "继续说话"))
        val content = ConversationContent(phase = ConversationPhase.SPEAKING,
            messages = listOf(ConversationMessage("当前回答", true, previousResponseId = "current_response")))
        KwsRepository.publish(listening)
        KwsRepository.publishAudioLevel(.6f)
        AsrRepository.publish(recognition)
        ConversationRepository.publish(ConversationSnapshot(content))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            binding.openSettings.performClick()
            val viewModel = ViewModelProvider(activity)[KwsViewModel::class.java]
            assertTrue(viewModel.uiState.value.settingsOpen)
            assertEquals(ConversationPhase.SPEAKING, viewModel.uiState.value.phase)
            assertEquals(.6f, viewModel.uiState.value.waveformLevel)
            assertEquals(listening, KwsRepository.snapshot.value)
            assertEquals(recognition, AsrRepository.snapshot.value)
            assertEquals(content, ConversationRepository.snapshot.value.content)
            assertEquals("current_response", lastReplyId())
            assertNull(shadowOf(activity).nextStartedService)
            binding.settings.captionsSwitch.performClick()
            binding.executePendingBindings()
            assertFalse(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.captions)
            assertFalse(AssistantPreferences(application).captions)
            binding.settings.closeSettings.performClick()
            assertFalse(viewModel.uiState.value.settingsOpen)
            assertEquals(ConversationPhase.SPEAKING, viewModel.uiState.value.phase)
            assertEquals(listening, KwsRepository.snapshot.value)
            assertEquals(recognition, AsrRepository.snapshot.value)
            assertEquals(content, ConversationRepository.snapshot.value.content)
            assertEquals("current_response", lastReplyId())
            assertNull(shadowOf(activity).nextStartedService)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recognitionFailureShowsRequestedCopyWithoutListeningFailurePrefix() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val listening = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.MUTED, asrRequested = false)
        KwsRepository.publish(listening)
        AsrRepository.publish(AsrSnapshot(status = AsrStatus.ERROR, error = application.getString(R.string.asr_failed)))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            assertEquals("没有听到你的声音,已暂停聊天", binding.listeningStatus.text.toString())
            assertEquals(listening, KwsRepository.snapshot.value)
            assertNull(shadowOf(activity).nextStartedService)
            binding.toggleChat.performClick()
            assertEquals(KwsService.ACTION_RESUME, shadowOf(activity).nextStartedService.action)
            assertEquals(listening, KwsRepository.snapshot.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun finishedReplyShowsReadyPromptAndDoesNotChangeListening() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val listening = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE, asrRequested = true)
        KwsRepository.publish(listening)
        AsrRepository.publish(AsrSnapshot(status = AsrStatus.LISTENING))
        val content = ConversationContent(phase = ConversationPhase.SPEAKING,
            messages = listOf(ConversationMessage("已经完成的回答", true)))
        ConversationRepository.publish(ConversationSnapshot(content))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            assertEquals(activity.getString(R.string.voice_speaking), binding.listeningStatus.text.toString())
            ConversationRepository.publish(ConversationSnapshot(content.copy(phase = ConversationPhase.READY)))
            shadowOf(Looper.getMainLooper()).idle()
            binding.executePendingBindings()
            assertEquals(activity.getString(R.string.voice_ready), binding.listeningStatus.text.toString())
            assertEquals(listening, KwsRepository.snapshot.value)
            assertNull(shadowOf(activity).nextStartedService)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun assistantNameDialogValidatesAndPersistsWithoutRestartingListeningInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (theme in listOf("notnight", "night")) {
            application.getSharedPreferences("assistant", 0).edit().clear().commit()
            RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-$theme-mdpi")
            val listening = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE, asrRequested = true)
            KwsRepository.publish(listening)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                binding.executePendingBindings()
                assertEquals("豆包", binding.settings.nameValue.text.toString())
                activity.openSettings()
                binding.settings.changeName.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                var dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val input = dialog.findViewById<android.widget.EditText>(R.id.name_input)!!
                assertEquals("豆包", input.text.toString())
                for (invalid in listOf("小", "小小小小白", "小白1")) {
                    input.setText(invalid)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(dialog.isShowing)
                    assertEquals(activity.getString(R.string.voice_name_invalid), input.error.toString())
                    assertEquals("豆包", AssistantPreferences(activity).assistantName)
                }
                input.setText("小白")
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("豆包", AssistantPreferences(activity).assistantName)

                binding.settings.changeName.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.findViewById<android.widget.EditText>(R.id.name_input)!!.setText("小白")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                runBlocking {
                    withTimeout(5_000) {
                        while (dialog.isShowing) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
                    }
                }
                binding.executePendingBindings()
                assertEquals("小白", AssistantPreferences(activity).assistantName)
                assertEquals("小白", binding.settings.nameValue.text.toString())
                assertEquals(activity.getString(R.string.voice_name_commands, "小白"), binding.settings.nameDetail.text.toString())
                assertEquals(listening, KwsRepository.snapshot.value)
                assertNull(shadowOf(activity).nextStartedService)
                controller.recreate()
                assertEquals("小白", ViewModelProvider(controller.get())[KwsViewModel::class.java].uiState.value.assistantName)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun closeRequiresConfirmationThenExitsWithoutDeletingChatInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (theme in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-$theme-mdpi")
            val topic = ChatTopic()
            val messages = listOf(ConversationMessage("当前的话题", false, "question", 1, topic.id),
                ConversationMessage("已有回答", true, "reply", 2, topic.id, previousResponseId = "old_response"))
            ConversationRepository.setTopic(topic)
            ConversationRepository.publish(ConversationSnapshot(ConversationContent(messages = messages)))
            KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING,
                mode = AssistantMode.AWAKE, asrRequested = true))
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                binding.executePendingBindings()
                binding.endSession.performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                binding.endSession.performClick()
                assertSame(dialog, ShadowDialog.getLatestDialog())
                assertNull(shadowOf(activity).nextStartedService)
                assertEquals(topic, ConversationRepository.activeTopic)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
                assertEquals("old_response", lastReplyId())
                assertFalse(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.ended)
                assertNull(shadowOf(activity).nextStartedService)

                binding.endSession.performClick()
                val confirmed = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(activity.getString(R.string.voice_confirm), confirmed.getButton(AlertDialog.BUTTON_POSITIVE).text)
                confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val intent = shadowOf(activity).nextStartedService
                assertEquals(KwsService.ACTION_EXIT, intent.action)
                assertTrue(activity.isFinishing)
                assertFalse(KwsRepository.isUiVisible.value)
                assertEquals(topic, ConversationRepository.activeTopic)
                assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun settingsClearHistoryRequiresConfirmationInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (theme in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-$theme-mdpi")
            val topic = ChatTopic()
            val messages = listOf(ConversationMessage("保留到确认清除", false, "question", 1, topic.id),
                ConversationMessage("已有回答", true, "reply", 2, topic.id, previousResponseId = "old_response"))
            ConversationRepository.setTopic(topic)
            ConversationRepository.publish(ConversationSnapshot(ConversationContent(messages = messages)))
            KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING,
                mode = AssistantMode.AWAKE, asrRequested = true))
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                activity.openSettings()
                binding.executePendingBindings()
                binding.settings.clearHistory.performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                binding.settings.clearHistory.performClick()
                assertSame(dialog, ShadowDialog.getLatestDialog())
                assertNull(shadowOf(activity).nextStartedService)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(messages, ConversationRepository.snapshot.value.content.messages)
                assertEquals("old_response", lastReplyId())
                binding.settings.clearHistory.performClick()
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(KwsService.ACTION_CLEAR_HISTORY, shadowOf(activity).nextStartedService.action)
                assertNull(shadowOf(activity).nextStartedService)
                assertFalse(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.settingsOpen)
                assertFalse(ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.ended)
                assertTrue(KwsRepository.snapshot.value.asrRequested)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun firstSetupPrefillsBuildCredentialsWithoutSavingOrEnteringHome() {
        application.getSharedPreferences("credentials", 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityPermissionBinding>(activity.findViewById(R.id.permission_root))!!
            val defaults = CredentialStore(application).load()
            assertTrue(binding.speechAppId.text.toString() == defaults.appId)
            assertTrue(binding.speechAccessToken.text.toString() == defaults.accessToken)
            assertTrue(binding.arkApiKey.text.toString() == defaults.arkApiKey)
            assertFalse(CredentialStore(application).isConfigured)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun renderCredentialFormInBothThemesIncludingSmallLandscape() {
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        for ((width, height) in listOf(1280 to 720, 800 to 360)) {
            for (theme in listOf("light", "dark")) {
                RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-land-${if (theme == "dark") "night" else "notnight"}-mdpi")
                val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
                try {
                    val binding = DataBindingUtil.getBinding<ActivityPermissionBinding>(controller.get().findViewById(R.id.permission_root))!!
                    binding.executePendingBindings()
                    binding.root.requestFocus()
                    binding.setupScroll.scrollTo(0, 0)
                    val name = "credentials-${com.spake.brony.doubaoassistant.BuildConfig.FLAVOR}-$theme-$width"
                    capture(binding.root, name, width, height)
                    binding.setupScroll.fullScroll(View.FOCUS_DOWN)
                    capture(binding.root, "$name-bottom", width, height)
                    val position = IntArray(2)
                    binding.permissionAction.getLocationOnScreen(position)
                    assertTrue(position[1] >= 0 && position[1] + binding.permissionAction.height <= height)
                    assertEquals("配置你的助手", binding.onboardTitle.text.toString())
                    assertEquals(3, binding.credentialsForm.childCount)
                    assertTrue(binding.speechAccessToken.top > binding.speechAppId.bottom)
                    assertTrue(binding.arkApiKey.top > binding.speechAccessToken.bottom)
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun renderAllElevenReferenceScreensInBothThemes() {
        // 设计稿示例仅作为截图测试夹具，生产 ViewModel 不会生成这些回复。
        val messages = listOf(ConversationMessage("今天有点累，想和你聊聊。", false), ConversationMessage("我在。今天发生了什么，\n让你觉得累？", true))
        for (theme in listOf("light", "dark")) {
            RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-${if (theme == "dark") "night" else "notnight"}-mdpi")
            for (denied in listOf(false, true)) {
                shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
                AssistantPreferences(application).microphoneRequested = denied
                val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
                try {
                    val binding = DataBindingUtil.getBinding<ActivityPermissionBinding>(controller.get().findViewById(R.id.permission_root))!!
                    if (denied) ViewModelProvider(controller.get())[PermissionViewModel::class.java].onPermissionChanged(false, true, true)
                    binding.executePendingBindings()
                    capture(binding.root, "$theme-${if (denied) "permission" else "onboarding"}", 1280, 720)
                } finally { controller.pause().stop().destroy() }
            }
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val screens = listOf("ready", "listening", "thinking", "speaking", "hidden", "paused", "ended", "settings", "network")
            for (screen in screens) {
                KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING, mode = if (screen == "paused") AssistantMode.MUTED else AssistantMode.AWAKE, asrRequested = screen != "paused"))
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    val activity = controller.get()
                    val viewModel = ViewModelProvider(activity)[KwsViewModel::class.java]
                    val phase = when (screen) {
                        "listening" -> ConversationPhase.LISTENING
                        "thinking" -> ConversationPhase.THINKING
                        "speaking", "hidden" -> ConversationPhase.SPEAKING
                        "network" -> ConversationPhase.NETWORK
                        else -> ConversationPhase.READY
                    }
                    viewModel.onConversationChanged(ConversationContent(phase, when (screen) { "ready" -> emptyList(); "listening" -> emptyList(); "thinking" -> messages.take(1); else -> messages }, if (screen == "listening") "今天有点累，想和你聊聊…" else ""))
                    if (screen == "hidden") viewModel.toggleCaptions()
                    if (screen == "ended") viewModel.onSessionEnded()
                    if (screen == "settings") viewModel.openSettings()
                    val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                    binding.executePendingBindings()
                    capture(binding.root, "$theme-$screen", 1280, 720)
                    assertEquals(1280, binding.root.width)
                    assertTrue(binding.toggleListening.width >= 48)
                    assertEquals(android.widget.LinearLayout.VERTICAL, binding.rightControls.orientation)
                    if (screen == "ready") {
                        val column = binding.conversation.getChildAt(0) as android.view.ViewGroup
                        assertEquals(2, column.childCount)
                        assertTrue(column.getChildAt(0).height > 0)
                    }
                    assertEquals(screen == "settings", binding.settings.settingsPanel.visibility == View.VISIBLE)
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun conversationMatchesTargetTypographyAndUsesAvailableWidth() {
        for (theme in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("w800dp-h360dp-land-$theme-mdpi")
            val view = com.spake.brony.doubaoassistant.ui.widget.ConversationView(application)
            view.setDimensions(DesignDimensions(800f, 360f))
            view.setPadding(0, 0, 120, 0)
            view.setConversation(ConversationContent(messages = listOf(
                ConversationMessage("你这边好像没说具体内容，是有什么想聊的事吗？", true),
                ConversationMessage("哎，天明，先休息啊。", false),
                ConversationMessage("听着像是你在和旁边人搭话，你是想跟我分享这段日常，还是有别的事？", true),
            )), true)
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 360)
            val column = view.getChildAt(0) as android.view.ViewGroup
            assertEquals(3, column.childCount)
            for (index in 0 until column.childCount) {
                val row = column.getChildAt(index)
                val text = if (row is android.widget.LinearLayout) row.getChildAt(0) as android.widget.TextView else row as android.widget.TextView
                val user = index == 1
                assertEquals((if (user) 20f else 26f) * 2 / 3, text.textSize, .001f)
                assertEquals(if (user) 20 else 25, text.lineHeight)
                assertEquals(400, text.typeface.weight)
                assertEquals(0f, text.letterSpacing, .001f)
                assertEquals(if (user) .7f else 1f, text.alpha, .001f)
                assertEquals(20, (row.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
                assertEquals(ContextCompat.getColor(application, if (user) R.color.voice_secondary else R.color.voice_text), text.currentTextColor)
                assertEquals(544, text.width)
            }
            val messages = ConversationContent(messages = listOf(ConversationMessage("上一条回复", true)))
            view.setConversation(messages.copy(liveText = "正在说话"), true)
            val liveText = column.getChildAt(column.childCount - 1) as android.widget.TextView
            assertEquals(20f * 2 / 3, liveText.textSize, .001f)
            assertEquals(.7f, liveText.alpha, .001f)
            view.setConversation(messages.copy(liveText = "继续说话"), true)
            assertSame(liveText, column.getChildAt(column.childCount - 1))
            assertEquals(.7f, liveText.alpha, .001f)
        }
    }

    @Test fun speakerStaysHiddenWhileAssistantTextUpdates() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val listening = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE, asrRequested = true)
        KwsRepository.publish(listening)
        val messages = listOf(ConversationMessage("问题", false, "user"), ConversationMessage("答", true, "reply"))
        ConversationRepository.publish(ConversationSnapshot(ConversationContent(messages = messages)))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            val column = binding.conversation.getChildAt(0) as ViewGroup
            assertTrue(column.getChildAt(0) is android.widget.TextView)
            val row = column.getChildAt(1) as android.widget.LinearLayout
            assertEquals(android.widget.LinearLayout.VERTICAL, row.orientation)
            val button = row.getChildAt(1) as android.widget.ImageButton
            assertEquals(View.GONE, button.visibility)
            binding.conversation.setConversation(ConversationContent(messages = messages.dropLast(1) + messages.last().copy(text = "答案更新")), true)
            assertSame(row, column.getChildAt(1))
            assertSame(button, row.getChildAt(1))
            assertEquals("答案更新", (row.getChildAt(0) as android.widget.TextView).text.toString())
            assertEquals(View.GONE, button.visibility)
            assertEquals(listening, KwsRepository.snapshot.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun closedMicrophoneStillShowsReplyAndAllowsPausingOutputWithoutRestartingCapture() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        KwsRepository.publish(KwsSnapshot(status = ListeningStatus.STOPPED))
        for (phase in listOf(ConversationPhase.THINKING, ConversationPhase.SPEAKING)) {
            ConversationRepository.publish(ConversationSnapshot(ConversationContent(phase = phase)))
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                binding.executePendingBindings()
                assertEquals(phase, ViewModelProvider(activity)[KwsViewModel::class.java].uiState.value.phase)
                assertEquals(activity.getString(R.string.voice_enable_mic), binding.toggleListening.contentDescription)
                assertEquals(activity.getString(R.string.voice_pause_chat), binding.toggleChat.contentDescription)
                assertTrue(binding.toggleChat.isEnabled)
                binding.toggleChat.performClick()
                assertEquals(KwsService.ACTION_PAUSE, shadowOf(activity).nextStartedService.action)
                assertEquals(ListeningStatus.STOPPED, KwsRepository.snapshot.value.status)
                ConversationRepository.publish(ConversationSnapshot(ConversationContent(phase = ConversationPhase.READY)))
                shadowOf(Looper.getMainLooper()).idle()
                binding.executePendingBindings()
                assertTrue(binding.toggleChat.isEnabled)
                assertEquals(activity.getString(R.string.voice_pause_chat), binding.toggleChat.contentDescription)
                assertEquals(activity.getString(R.string.voice_stopped), binding.listeningStatus.text.toString())
                assertNull(shadowOf(activity).nextStartedService)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun pausedChatCanSpeakAcknowledgementWithoutResumingAsrAndPauseButtonStopsIt() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.MUTED, asrRequested = false))
        ConversationRepository.publish(ConversationSnapshot(ConversationContent(phase = ConversationPhase.SPEAKING,
            messages = listOf(ConversationMessage("好的", true)))))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            assertEquals(activity.getString(R.string.voice_speaking), binding.listeningStatus.text.toString())
            assertEquals(activity.getString(R.string.voice_pause_chat), binding.toggleChat.contentDescription)
            binding.toggleChat.performClick()
            assertEquals(KwsService.ACTION_PAUSE, shadowOf(activity).nextStartedService.action)
            assertFalse(KwsRepository.snapshot.value.asrRequested)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun microphoneAndChatButtonsSendDifferentCommandsAndSettingsNeverRestartCapture() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        KwsRepository.publish(KwsSnapshot(status = ListeningStatus.LISTENING,
            mode = AssistantMode.AWAKE, asrRequested = true))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
            binding.executePendingBindings()
            binding.openSettings.performClick()
            assertNull(shadowOf(activity).nextStartedService)
            activity.closeSettings()
            binding.toggleChat.performClick()
            assertEquals(KwsService.ACTION_PAUSE, shadowOf(activity).nextStartedService.action)
            KwsRepository.publish(KwsRepository.snapshot.value.copy(mode = AssistantMode.MUTED, asrRequested = false))
            shadowOf(Looper.getMainLooper()).idle()
            binding.executePendingBindings()
            assertEquals(activity.getString(R.string.voice_disable_mic), binding.toggleListening.contentDescription)
            assertEquals(activity.getString(R.string.voice_resume_chat), binding.toggleChat.contentDescription)
            binding.toggleChat.performClick()
            assertEquals(KwsService.ACTION_RESUME, shadowOf(activity).nextStartedService.action)
            binding.toggleListening.performClick()
            assertEquals(KwsService.ACTION_STOP, shadowOf(activity).nextStartedService.action)
            KwsRepository.publish(KwsSnapshot())
            shadowOf(Looper.getMainLooper()).idle()
            binding.executePendingBindings()
            assertTrue(binding.toggleChat.isEnabled)
            activity.toggleChat()
            assertEquals(KwsService.ACTION_PAUSE, shadowOf(activity).nextStartedService.action)
            binding.toggleListening.performClick()
            assertEquals(KwsService.ACTION_START, shadowOf(activity).nextStartedService.action)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun layoutChoiceMirrorsControlsAndMenuAndSurvivesRecreationInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (theme in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-$theme-mdpi")
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                activity.openSettings()
                binding.executePendingBindings()
                binding.settings.layoutLeft.performClick()
                binding.executePendingBindings()
                assertTrue(binding.settings.layoutLeft.isSelected)
                assertFalse(binding.settings.layoutRight.isSelected)
                assertTrue(AssistantPreferences(application).controlsOnLeft)
                activity.closeSettings()
                binding.executePendingBindings()
                capture(binding.root, "controls-left-$theme", 1280, 720)
                assertTrue(binding.rightControls.right < 640)
                val menu = IntArray(2)
                binding.openSettings.getLocationOnScreen(menu)
                assertTrue(menu[0] > 640)
                assertTrue(binding.conversation.paddingLeft >= binding.rightControls.right)
                assertEquals(0, binding.conversation.paddingRight)
                assertNull(shadowOf(activity).nextStartedService)
                controller.recreate()
                val recreated = DataBindingUtil.getBinding<ActivityMainBinding>(controller.get().findViewById(R.id.voice_root))!!
                recreated.executePendingBindings()
                assertTrue(ViewModelProvider(controller.get())[KwsViewModel::class.java].uiState.value.controlsOnLeft)
                controller.get().openSettings()
                recreated.executePendingBindings()
                recreated.settings.layoutRight.performClick()
                recreated.executePendingBindings()
                assertFalse(AssistantPreferences(application).controlsOnLeft)
                controller.get().closeSettings()
                recreated.executePendingBindings()
                capture(recreated.root, "controls-right-$theme", 1280, 720)
                assertTrue(recreated.rightControls.left > 640)
                recreated.openSettings.getLocationOnScreen(menu)
                assertTrue(menu[0] < 640)
                assertEquals(0, recreated.conversation.paddingLeft)
                assertNull(shadowOf(controller.get()).nextStartedService)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun rightControlsStackCaptionsMicrophonePauseAndCloseInBothThemes() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for (theme in listOf("notnight", "night")) {
            for ((width, height) in listOf(1280 to 720, 1920 to 720, 800 to 360)) {
                RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-land-$theme-mdpi")
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    val binding = DataBindingUtil.getBinding<ActivityMainBinding>(controller.get().findViewById(R.id.voice_root))!!
                    binding.executePendingBindings()
                    shadowOf(Looper.getMainLooper()).idle()
                    repeat(2) {
                        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        binding.root.layout(0, 0, width, height)
                    }
                    assertEquals(4, binding.rightControls.childCount)
                    assertTrue(kotlin.math.abs(binding.rightControls.top * 2 + binding.rightControls.height - height) <= 1)
                    val buttons = listOf(binding.captionsButton, binding.toggleListening, binding.toggleChat, binding.endSession)
                    var previousBottom = 0
                    var centerX: Int? = null
                    for (button in buttons) {
                        val position = IntArray(2)
                        button.getLocationOnScreen(position)
                        assertTrue(position[1] >= previousBottom)
                        assertTrue(position[1] + button.height <= height)
                        assertTrue(position[0] >= 0 && position[0] + button.width <= width)
                        assertTrue(button.width >= 48 && button.height >= 48)
                        val center = position[0] + button.width / 2
                        centerX?.let { assertTrue(kotlin.math.abs(it - center) <= 1) }
                        centerX = center
                        previousBottom = position[1] + button.height
                    }
                    assertTrue(binding.conversation.width - binding.conversation.paddingRight <= binding.rightControls.left)
                    val bottomText = if (binding.messageInput.visibility == View.VISIBLE) binding.messageInput else binding.listeningStatus
                    for (view in listOf(binding.voiceMark, bottomText)) {
                        val position = IntArray(2)
                        view.getLocationOnScreen(position)
                        assertTrue(kotlin.math.abs(position[0] * 2 + view.width - width) <= 1)
                        assertTrue(position[1] + view.height <= height)
                    }
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun ultraWideAndPhoneLandscapeKeepControlsInsideTheViewport() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for ((width, height) in listOf(1920 to 720, 800 to 360)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-land-mdpi")
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val binding = DataBindingUtil.getBinding<ActivityMainBinding>(activity.findViewById(R.id.voice_root))!!
                binding.executePendingBindings()
                capture(binding.root, "landscape-$width", width, height)
                val position = IntArray(2)
                binding.endSession.getLocationOnScreen(position)
                assertTrue(position[1] >= 0)
                assertTrue(position[1] + binding.endSession.height <= height)
                assertTrue(binding.toggleListening.width >= 48)
            } finally { controller.pause().stop().destroy() }
            shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
            val permission = Robolectric.buildActivity(PermissionActivity::class.java).setup()
            try {
                val binding = DataBindingUtil.getBinding<ActivityPermissionBinding>(permission.get().findViewById(R.id.permission_root))!!
                binding.executePendingBindings()
                capture(binding.root, "onboarding-landscape-$width", width, height)
                binding.executePendingBindings()
                assertTrue(binding.permissionAction.minimumHeight >= 48)
                if (width == 1920) assertFalse(binding.onboardBody.text.contains('\n'))
            } finally { permission.pause().stop().destroy() }
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun capture(root: View, name: String, width: Int, height: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val output = File("build/reports/ui-snapshots/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
