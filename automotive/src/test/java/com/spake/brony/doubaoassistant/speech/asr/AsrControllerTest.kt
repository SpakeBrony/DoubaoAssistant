package com.spake.brony.doubaoassistant.speech.asr

import com.spake.brony.doubaoassistant.data.model.AsrCommand

import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.KwsCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrControllerTest {
    @Test fun authorizedBackgroundChatKeepsAsrAndPreservesManualPause() {
        val controller = AsrController()
        controller.start(true)
        controller.onUiVisibilityChanged(false, backgroundChat = true)
        assertTrue(controller.asrRequested)
        controller.onCommand(AsrCommand.STOP_REPLY)
        assertFalse(controller.asrRequested)
        controller.onUiVisibilityChanged(true)
        controller.onUiVisibilityChanged(false, backgroundChat = true)
        assertEquals(AssistantMode.MUTED, controller.mode)
        assertFalse(controller.asrRequested)
        controller.onCommand(KwsCommand.RESUME)
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(false, backgroundChat = false)
        assertFalse(controller.asrRequested)
        controller.stop()
        controller.onUiVisibilityChanged(false, backgroundChat = true)
        assertFalse(controller.asrRequested)
    }

    @Test fun floatingChatReturnHomeKeepsConversationButNeverUnmutes() {
        val controller = AsrController()
        controller.start(true)
        assertTrue(controller.onCommand(AsrCommand.RETURN_HOME, keepChatOnReturnHome = true))
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(false, backgroundChat = true)
        assertTrue(controller.asrRequested)
        controller.onCommand(AsrCommand.STOP_REPLY)
        controller.onCommand(AsrCommand.RETURN_HOME, keepChatOnReturnHome = true)
        assertEquals(AssistantMode.MUTED, controller.mode)
        assertFalse(controller.asrRequested)
    }

    @Test
    fun visiblePageRequestsAsrAndAllowsExplicitResumeToRestartIt() {
        val controller = AsrController()
        controller.onUiVisibilityChanged(true)
        assertFalse(controller.asrRequested)
        controller.start(uiVisible = true)
        assertTrue(controller.asrRequested)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        assertEquals(AssistantMode.AWAKE, controller.mode)
    }

    @Test
    fun backgroundResumeStartsAsrWithoutOpeningThePage() {
        val controller = AsrController()
        controller.start(uiVisible = false)
        assertFalse(controller.asrRequested)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(false)
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(true)
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(true)
        assertTrue(controller.asrRequested)
    }

    @Test
    fun leavingThePageWaitsForAnExplicitResumeCommand() {
        val controller = AsrController()
        controller.start(uiVisible = true)
        controller.onUiVisibilityChanged(false)
        assertEquals(AssistantMode.WAITING, controller.mode)
        assertFalse(controller.asrRequested)
        controller.onCommand(KwsCommand.RESUME)
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(true)
        assertTrue(controller.asrRequested)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
    }

    @Test
    fun muteSurvivesPageChangesButResumeCanUnmuteInForegroundAndBackground() {
        for (visible in listOf(true, false)) {
            val controller = AsrController()
            controller.start(uiVisible = visible)
            assertTrue(controller.onCommand(AsrCommand.STOP_REPLY))
            controller.onUiVisibilityChanged(!visible)
            controller.onUiVisibilityChanged(visible)
            assertEquals(AssistantMode.MUTED, controller.mode)
            assertFalse(controller.asrRequested)
            assertTrue(controller.onCommand(KwsCommand.RESUME))
            assertEquals(AssistantMode.AWAKE, controller.mode)
            assertTrue(controller.asrRequested)
            if (visible) assertTrue(controller.onCommand(KwsCommand.RESUME))
        }
    }

    @Test fun kwsStopPausesCloudRecognitionButKeepsResumeAvailable() {
        for (visible in listOf(true, false)) {
            val controller = AsrController()
            controller.start(visible)
            controller.onCommand(KwsCommand.RESUME)
            assertTrue(controller.asrRequested)
            assertTrue(controller.onCommand(KwsCommand.STOP_REPLY))
            assertEquals(AssistantMode.MUTED, controller.mode)
            assertFalse(controller.asrRequested)
            controller.onUiVisibilityChanged(!visible)
            assertEquals(AssistantMode.MUTED, controller.mode)
            assertTrue(controller.onCommand(KwsCommand.RESUME))
            assertTrue(controller.asrRequested)
            controller.stop()
            assertFalse(controller.onCommand(KwsCommand.STOP_REPLY))
        }
    }

    @Test
    fun backgroundResumeUnmutesAndStartsAsrWhileThePageRemainsHidden() {
        val controller = AsrController()
        controller.start(uiVisible = false)
        controller.onCommand(AsrCommand.STOP_REPLY)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(true)
        assertTrue(controller.asrRequested)
        assertTrue(controller.onCommand(AsrCommand.STOP_REPLY))
        assertFalse(controller.asrRequested)
    }

    @Test
    fun recognitionFailureRemainsPausedAcrossVisibilityChangesAndCommandCanResume() {
        val controller = AsrController()
        controller.start(uiVisible = true)
        controller.onCommand(AsrCommand.STOP_REPLY)
        controller.onUiVisibilityChanged(false)
        controller.onUiVisibilityChanged(true)
        assertFalse(controller.asrRequested)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        assertTrue(controller.asrRequested)
        assertEquals(AssistantMode.AWAKE, controller.mode)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        controller.stop()
        assertFalse(controller.onCommand(KwsCommand.RESUME))
    }

    @Test
    fun returningHomePausesChatButResumeCanRestoreItWithoutOpeningThePage() {
        val controller = AsrController()
        controller.start(uiVisible = true)
        assertTrue(controller.onCommand(AsrCommand.RETURN_HOME))
        assertFalse(controller.asrRequested)
        assertEquals(AssistantMode.WAITING, controller.mode)
        controller.onUiVisibilityChanged(false)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        assertTrue(controller.asrRequested)
        controller.onCommand(AsrCommand.STOP_REPLY)
        controller.onCommand(AsrCommand.RETURN_HOME)
        assertEquals(AssistantMode.MUTED, controller.mode)
        assertTrue(controller.onCommand(KwsCommand.RESUME))
        assertEquals(AssistantMode.AWAKE, controller.mode)
        assertTrue(controller.asrRequested)
        controller.onUiVisibilityChanged(true)
        assertTrue(controller.asrRequested)
    }

    @Test
    fun stoppedMicrophoneCannotRequestAsrThroughCommandsOrVisibilityChanges() {
        val controller = AsrController()
        controller.start(uiVisible = true)
        controller.stop()
        controller.onUiVisibilityChanged(false)
        controller.onUiVisibilityChanged(true)
        KwsCommand.entries.forEach { assertFalse(controller.onCommand(it)) }
        assertFalse(controller.asrRequested)
        assertEquals(AssistantMode.WAITING, controller.mode)
    }
}
