package com.spake.brony.doubaoassistant.viewmodel

import androidx.lifecycle.ViewModelStore
import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KwsViewModelTest {
    @Test fun closingMicrophoneDoesNotPauseChatOrDisableChatControl() {
        val state = KwsUiState(snapshot = KwsSnapshot(status = ListeningStatus.STOPPED))
        assertEquals(ConversationPhase.READY, state.phase)
        assertFalse(state.isPaused)
        assertTrue(state.canTypeMessage)
        assertTrue(state.canControlChat)
        val paused = state.copy(snapshot = state.snapshot.copy(chatPaused = true))
        assertTrue(paused.isPaused)
        assertTrue(paused.canControlChat)
        assertFalse(paused.isListening)
        assertFalse(paused.canTypeMessage)
        assertFalse(paused.copy(conversation = ConversationContent(phase = ConversationPhase.SPEAKING)).canTypeMessage)
        assertFalse(state.copy(snapshot = KwsSnapshot(status = ListeningStatus.LISTENING, asrRequested = true)).canTypeMessage)
        assertFalse(state.copy(ended = true).canTypeMessage)
        assertFalse(state.copy(ended = true).canControlChat)
    }

    @Test fun textInputKeepsLocalizedFailureVisibleWhenMicrophoneIsClosed() {
        val state = KwsUiState(snapshot = KwsSnapshot(status = ListeningStatus.ERROR, error = "监听失败"))
        assertEquals("监听失败", state.inputError)
        assertEquals("聊天失败", state.copy(chatError = "聊天失败").inputError)
        assertEquals("记录失败", KwsUiState(historyError = "记录失败").inputError)
        assertNull(KwsUiState().inputError)
    }

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun recognitionUpdatesCaptionsWithoutResettingSettingsAndUnsubscribesOnClear() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE))
        val recognition = MutableStateFlow(AsrSnapshot())
        val conversations = MutableStateFlow(ConversationSnapshot())
        val levels = MutableStateFlow(0f)
        val viewModel = KwsViewModel(snapshots, recognition, conversations, levels)
        store.put("kws", viewModel)
        runCurrent()
        viewModel.loadPreferences(Appearance.DARK, false, 2)
        val content = ConversationContent(phase = ConversationPhase.LISTENING, liveText = "导航到上海")
        recognition.value = AsrSnapshot(AsrStatus.LISTENING, content)
        conversations.value = ConversationSnapshot(content)
        runCurrent()
        assertEquals(content, viewModel.uiState.value.conversation)
        assertFalse(viewModel.uiState.value.captions)
        assertEquals(Appearance.DARK, viewModel.uiState.value.appearance)
        recognition.value = recognition.value.copy(status = AsrStatus.ERROR, error = "连接失败")
        runCurrent()
        assertEquals(ConversationPhase.ERROR, viewModel.uiState.value.phase)
        store.clear()
        runCurrent()
        assertEquals(0, recognition.subscriptionCount.value)
        assertEquals(0, snapshots.subscriptionCount.value)
        assertEquals(0, conversations.subscriptionCount.value)
        assertEquals(0, levels.subscriptionCount.value)
    }

    @Test
    fun volumeAloneDoesNotAnimateAndAsrHealthDoesNotReplaceReply() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot(status = ListeningStatus.LISTENING,
            mode = AssistantMode.AWAKE, asrRequested = true))
        val recognition = MutableStateFlow(AsrSnapshot())
        val answer = ConversationContent(phase = ConversationPhase.SPEAKING)
        val conversations = MutableStateFlow(ConversationSnapshot(answer))
        val viewModel = KwsViewModel(snapshots, recognition, conversations, MutableStateFlow(.8f))
        store.put("kws", viewModel)
        runCurrent()
        assertEquals(0f, viewModel.uiState.value.waveformLevel)
        recognition.value = AsrSnapshot(AsrStatus.LISTENING, ConversationContent(liveText = "你好"))
        runCurrent()
        assertEquals(.8f, viewModel.uiState.value.waveformLevel)
        assertEquals(answer, viewModel.uiState.value.conversation)
        viewModel.openSettings()
        assertEquals(ConversationPhase.SPEAKING, viewModel.uiState.value.phase)
        assertEquals(.8f, viewModel.uiState.value.waveformLevel)
        recognition.value = recognition.value.copy(conversation = ConversationContent())
        runCurrent()
        assertEquals(0f, viewModel.uiState.value.waveformLevel)
    }

    @Test
    fun publishedServiceStateReachesTheDefaultViewModel() = runTest {
        val previous = KwsRepository.snapshot.value
        try {
            KwsRepository.publish(KwsSnapshot())
            val viewModel = KwsViewModel()
            store.put("kws", viewModel)
            runCurrent()
            val latest = KwsSnapshot(status = ListeningStatus.LISTENING, keyword = "闭嘴豆包")
            KwsRepository.publish(latest)
            runCurrent()
            assertEquals(latest, viewModel.uiState.value.snapshot)
        } finally {
            store.clear()
            KwsRepository.publish(previous)
        }
    }

    @Test
    fun snapshotsUpdateStateAndClearLocalErrors() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot())
        val viewModel = KwsViewModel(snapshots)
        store.put("kws", viewModel)
        runCurrent()
        assertFalse(viewModel.uiState.value.isListening)
        val snapshot = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.MUTED)
        snapshots.value = snapshot
        runCurrent()
        assertTrue(viewModel.uiState.value.isListening)
        assertEquals(snapshot, viewModel.uiState.value.snapshot)

        viewModel.onListeningFailed("停止失败")
        assertEquals("停止失败", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.isListening)

        snapshots.value = KwsSnapshot()
        runCurrent()
        assertFalse(viewModel.uiState.value.isListening)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun clearingTheViewModelUnsubscribesAndNewScreensReadTheLatestState() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot())
        val original = KwsViewModel(snapshots)
        store.put("kws", original)
        runCurrent()
        assertEquals(1, snapshots.subscriptionCount.value)

        store.clear()
        runCurrent()
        assertEquals(0, snapshots.subscriptionCount.value)

        val latest = KwsSnapshot(status = ListeningStatus.LISTENING, keyword = "你好豆包", detectionCount = 1)
        snapshots.value = latest
        runCurrent()
        assertFalse(original.uiState.value.isListening)

        val reopened = KwsViewModel(snapshots)
        store.put("kws", reopened)
        assertEquals(latest, reopened.uiState.value.snapshot)
        runCurrent()
        assertEquals(1, snapshots.subscriptionCount.value)
    }

    @Test
    fun serviceErrorsRemainVisibleToNewScreens() = runTest {
        val failed = KwsSnapshot(status = ListeningStatus.ERROR, error = "麦克风不可用")
        val viewModel = KwsViewModel(MutableStateFlow(failed))
        store.put("kws", viewModel)
        runCurrent()
        assertFalse(viewModel.uiState.value.isListening)
        assertEquals(failed, viewModel.uiState.value.snapshot)
    }

    @Test
    fun serviceUpdatesDoNotResetSettingsCaptionsOrSpeed() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot())
        val viewModel = KwsViewModel(snapshots)
        store.put("kws", viewModel)
        runCurrent()
        viewModel.loadPreferences(Appearance.DARK, false, 2)
        viewModel.openSettings()
        snapshots.value = KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.MUTED)
        runCurrent()
        assertTrue(viewModel.uiState.value.settingsOpen)
        assertFalse(viewModel.uiState.value.captions)
        assertEquals(2, viewModel.uiState.value.speed)
        assertEquals(Appearance.DARK, viewModel.uiState.value.appearance)
    }

    @Test
    fun closingSettingsDoesNotUndoAVoiceMute() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE))
        val viewModel = KwsViewModel(snapshots)
        store.put("kws", viewModel)
        runCurrent()
        viewModel.openSettings()
        viewModel.closeSettings()
        assertEquals(AssistantMode.AWAKE, viewModel.uiState.value.snapshot.mode)
        viewModel.openSettings()
        snapshots.value = snapshots.value.copy(mode = AssistantMode.MUTED, detectionCount = 1)
        runCurrent()
        viewModel.closeSettings()
        assertEquals(AssistantMode.MUTED, viewModel.uiState.value.snapshot.mode)
        viewModel.openSettings()
        viewModel.closeSettings()
        assertEquals(ConversationPhase.PAUSED, viewModel.uiState.value.phase)
    }

    @Test
    fun endedSessionSurvivesServiceUpdatesAndSettingsClose() = runTest {
        val snapshots = MutableStateFlow(KwsSnapshot(status = ListeningStatus.LISTENING, mode = AssistantMode.AWAKE))
        val viewModel = KwsViewModel(snapshots)
        store.put("kws", viewModel)
        runCurrent()
        viewModel.openSettings()
        viewModel.onSessionEnded()
        snapshots.value = KwsSnapshot()
        runCurrent()
        assertEquals(ConversationPhase.ENDED, viewModel.uiState.value.phase)
        viewModel.closeSettings()
        assertEquals(ConversationPhase.ENDED, viewModel.uiState.value.phase)
        viewModel.onSessionStarted()
        assertFalse(viewModel.uiState.value.ended)
    }
}
