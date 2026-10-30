package com.spake.brony.doubaoassistant.ui

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.transition.Fade
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.util.Log
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.Appearance
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.data.model.ReplyStyle
import com.spake.brony.doubaoassistant.data.model.OverlayStyle
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import com.spake.brony.doubaoassistant.databinding.ActivityMainBinding
import com.spake.brony.doubaoassistant.service.KwsService
import com.spake.brony.doubaoassistant.ui.base.BaseActivity
import com.spake.brony.doubaoassistant.ui.dialog.ExitAppDialogFragment
import com.spake.brony.doubaoassistant.ui.dialog.ClearHistoryDialogFragment
import com.spake.brony.doubaoassistant.ui.dialog.AssistantNameDialogFragment
import com.spake.brony.doubaoassistant.ui.dialog.MessageInputDialogFragment
import com.spake.brony.doubaoassistant.ui.dialog.ChatLogsDialogFragment
import com.spake.brony.doubaoassistant.viewmodel.KwsUiState
import com.spake.brony.doubaoassistant.viewmodel.KwsViewModel

class MainActivity : BaseActivity() {
    private val viewModel: KwsViewModel by viewModels()
    private val preferences by lazy { AssistantPreferences(this) }
    private lateinit var binding: ActivityMainBinding
    private val phonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val message = when {
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED -> R.string.phone_call_permission_needed
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED -> R.string.phone_number_only
            else -> R.string.phone_permissions_granted
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasMicrophone() || !CredentialStore(this).isConfigured) { returnToPermission(); return }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.lifecycleOwner = this
        binding.viewModel = viewModel
        binding.actions = this
        binding.conversation.onReplay = ::replayReply
        viewModel.loadPreferences(preferences.appearance, preferences.captions, preferences.speed, preferences.assistantName)
        viewModel.setReplyStyle(preferences.replyStyle)
        viewModel.setAiControlEnabled(preferences.aiControlEnabled)
        viewModel.setDebugMode(preferences.debugMode)
        viewModel.setAnswerMode(preferences.answerMode)
        viewModel.setOverlayStyle(preferences.overlayStyle)
        viewModel.setBackgroundChat(preferences.backgroundChat)
        viewModel.setControlsOnLeft(preferences.controlsOnLeft)
        binding.root.doOnLayout { binding.applyDesignDimensions(it.width, it.height) }
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.uiState.value.settingsOpen) closeSettings() else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KwsRepository.setUiVisible(hasMicrophone() && CredentialStore(this).isConfigured && !isFinishing)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!hasMicrophone() || !CredentialStore(this).isConfigured) { returnToPermission(); return }
        viewModel.setBackgroundChat(preferences.backgroundChat)
        viewModel.setDebugMode(preferences.debugMode)
        sendControl(KwsService.ACTION_CHECK_HISTORY)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            intent.removeExtra(EXTRA_AUTO_START)
            if (!viewModel.uiState.value.isListening) startListening()
        }
    }

    override fun onStop() {
        KwsRepository.setUiVisible(false)
        if (::binding.isInitialized) TransitionManager.endTransitions(binding.root as ViewGroup)
        super.onStop()
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun returnToPermission() {
        if (isFinishing) return
        KwsRepository.setUiVisible(false)
        stopService(Intent(this, KwsService::class.java))
        startActivity(Intent(this, PermissionActivity::class.java))
        finish()
    }

    private fun startListening() {
        if (!hasMicrophone()) { returnToPermission(); return }
        viewModel.onSessionStarted()
        try {
            ContextCompat.startForegroundService(this, Intent(this, KwsService::class.java).setAction(KwsService.ACTION_START))
        } catch (e: RuntimeException) {
            Log.e("DoubaoUi", "Failed to start listening", e)
            viewModel.onListeningFailed(getString(R.string.kws_failed))
        }
    }

    private fun sendControl(action: String): Boolean {
        if (!hasMicrophone()) { returnToPermission(); return false }
        return try {
            startService(Intent(this, KwsService::class.java).setAction(action))
            true
        } catch (e: RuntimeException) {
            Log.e("DoubaoUi", "Failed to send listening control", e)
            viewModel.onListeningFailed(getString(R.string.kws_failed))
            false
        }
    }

    fun toggleListening() {
        if (viewModel.uiState.value.isListening) sendControl(KwsService.ACTION_STOP)
        else startListening()
    }

    fun toggleChat() {
        val state = viewModel.uiState.value
        if (!state.canControlChat) return
        if (state.isPaused) viewModel.onSessionStarted()
        sendControl(if (state.isPaused) KwsService.ACTION_RESUME else KwsService.ACTION_PAUSE)
    }

    private fun replayReply(messageId: String) {
        try {
            startService(Intent(this, KwsService::class.java).setAction(KwsService.ACTION_REPLAY)
                .putExtra(KwsService.EXTRA_MESSAGE_ID, messageId))
        } catch (e: RuntimeException) {
            Log.e("DoubaoUi", "Failed to replay reply", e)
            viewModel.onPlaybackFailed(getString(R.string.tts_failed))
        }
    }

    fun exitApp() {
        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(EXIT_APP_DIALOG) == null) {
            ExitAppDialogFragment().showNow(supportFragmentManager, EXIT_APP_DIALOG)
        }
    }

    fun confirmExitApp() {
        if (sendControl(KwsService.ACTION_EXIT)) {
            KwsRepository.setUiVisible(false)
            finishAndRemoveTask()
        }
    }

    fun clearHistory() {
        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(CLEAR_HISTORY_DIALOG) == null) {
            ClearHistoryDialogFragment().showNow(supportFragmentManager, CLEAR_HISTORY_DIALOG)
        }
    }

    fun confirmClearHistory() {
        if (sendControl(KwsService.ACTION_CLEAR_HISTORY)) {
            viewModel.onSessionStarted()
            closeSettings()
        }
    }

    fun openSettings() {
        animateSettings()
        viewModel.openSettings()
        binding.executePendingBindings()
        binding.settings.closeSettings.requestFocus()
    }

    fun openCredentials() {
        KwsRepository.setUiVisible(false)
        stopService(Intent(this, KwsService::class.java))
        startActivity(Intent(this, PermissionActivity::class.java)
            .putExtra(PermissionActivity.EXTRA_EDIT_CREDENTIALS, true))
        finish()
    }

    fun managePhonePermissions() {
        val missing = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val permanentlyDenied = missing.any { permission ->
            val requested = if (permission == Manifest.permission.CALL_PHONE) preferences.callPhoneRequested else preferences.contactsRequested
            requested && !shouldShowRequestPermissionRationale(permission)
        }
        if (missing.isEmpty() || permanentlyDenied) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.voice_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        } else {
            if (Manifest.permission.CALL_PHONE in missing) preferences.callPhoneRequested = true
            if (Manifest.permission.READ_CONTACTS in missing) preferences.contactsRequested = true
            phonePermissions.launch(missing.toTypedArray())
        }
    }

    fun closeSettings() {
        animateSettings()
        viewModel.closeSettings()
        binding.executePendingBindings()
        binding.openSettings.requestFocus()
    }

    private fun animateSettings() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val transition = TransitionSet()
            .addTransition(Slide(if (viewModel.uiState.value.controlsOnLeft) Gravity.RIGHT else Gravity.LEFT).addTarget(binding.settings.settingsPanel))
            .addTransition(Fade().addTarget(binding.settingsScrim))
            .setDuration(240)
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup, transition)
    }

    fun toggleCaptions() {
        viewModel.toggleCaptions()
        preferences.captions = viewModel.uiState.value.captions
    }

    fun toggleBackgroundChat() {
        val enabled = !viewModel.uiState.value.backgroundChat
        preferences.backgroundChat = enabled
        viewModel.setBackgroundChat(enabled)
        if (viewModel.uiState.value.isListening) sendControl(KwsService.ACTION_REFRESH_BACKGROUND)
        if (enabled && !Settings.canDrawOverlays(this)) requestOverlayPermission()
    }

    fun setSpeed(speed: Int) {
        viewModel.setSpeed(speed)
        preferences.speed = viewModel.uiState.value.speed
    }

    fun setOverlayStyle(style: OverlayStyle) {
        preferences.overlayStyle = style
        viewModel.setOverlayStyle(style)
        if (viewModel.uiState.value.isListening) sendControl(KwsService.ACTION_REFRESH_BACKGROUND)
    }

    fun setReplyStyle(style: ReplyStyle) {
        preferences.replyStyle = style
        viewModel.setReplyStyle(style)
    }

    fun toggleAiControl() {
        val enabled = !viewModel.uiState.value.aiControlEnabled
        preferences.aiControlEnabled = enabled
        viewModel.setAiControlEnabled(enabled)
    }

    fun toggleDebugMode() {
        preferences.debugMode = !viewModel.uiState.value.debugMode
        viewModel.setDebugMode(preferences.debugMode)
    }

    fun showChatLogs() {
        if (preferences.debugMode && !supportFragmentManager.isStateSaved &&
            supportFragmentManager.findFragmentByTag("chat_logs") == null) {
            ChatLogsDialogFragment().showNow(supportFragmentManager, "chat_logs")
        }
    }

    fun setAnswerMode(mode: AnswerMode) {
        preferences.answerMode = mode
        viewModel.setAnswerMode(mode)
    }

    fun showMessageInput() {
        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(MESSAGE_INPUT_DIALOG) == null) {
            MessageInputDialogFragment().showNow(supportFragmentManager, MESSAGE_INPUT_DIALOG)
        }
    }

    fun sendTypedMessage(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty()) return false
        return try {
            startService(Intent(this, KwsService::class.java).setAction(KwsService.ACTION_SEND_TEXT)
                .putExtra(KwsService.EXTRA_TEXT, text))
            viewModel.onSessionStarted()
            true
        } catch (e: RuntimeException) {
            Log.e("DoubaoUi", "Failed to send typed message", e)
            false
        }
    }

    fun setControlsOnLeft(left: Boolean) {
        preferences.controlsOnLeft = left
        viewModel.setControlsOnLeft(left)
    }

    fun changeAssistantName() {
        if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(ASSISTANT_NAME_DIALOG) == null) {
            AssistantNameDialogFragment().showNow(supportFragmentManager, ASSISTANT_NAME_DIALOG)
        }
    }

    fun confirmAssistantName(name: String) {
        preferences.assistantName = name
        viewModel.setAssistantName(name)
    }

    fun requestOverlayPermission() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        } catch (e: RuntimeException) {
            Log.e("DoubaoUi", "Failed to open background wake permission settings", e)
            Toast.makeText(this, R.string.voice_background_permission_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun setAppearance(appearance: Appearance) {
        preferences.appearance = appearance
        viewModel.loadPreferences(appearance, preferences.captions, preferences.speed)
        AppearanceTheme.apply(appearance)
    }

    fun statusText(state: KwsUiState): String {
        state.errorMessage?.let { return getString(R.string.listening_error, it) }
        state.asr.error?.let { return it }
        state.chatError?.let { return it }
        if (state.snapshot.status == ListeningStatus.ERROR) return getString(R.string.listening_error, state.snapshot.error.orEmpty())
        if (!state.ended && !state.isPaused) {
            state.ttsError?.let { return it }
            state.historyError?.let { return it }
        }
        return getString(when (state.phase) {
            ConversationPhase.READY -> when {
                !state.isListening -> R.string.voice_stopped
                state.snapshot.status == ListeningStatus.STARTING -> R.string.listening_starting
                state.asr.status == AsrStatus.STARTING -> R.string.voice_asr_connecting
                state.asr.status == AsrStatus.LISTENING -> R.string.voice_ready
                !state.snapshot.asrRequested -> R.string.voice_kws_waiting
                else -> R.string.voice_asr_connecting
            }
            ConversationPhase.LISTENING -> R.string.voice_listening
            ConversationPhase.THINKING -> R.string.voice_thinking
            ConversationPhase.EXECUTING -> R.string.voice_executing
            ConversationPhase.SPEAKING -> R.string.voice_speaking
            ConversationPhase.PAUSED -> if (!state.isListening) R.string.voice_stopped else R.string.voice_paused
            ConversationPhase.ENDED -> R.string.voice_ended
            ConversationPhase.NETWORK -> R.string.voice_network
            ConversationPhase.ERROR -> R.string.kws_failed
        })
    }

    fun showChatError() {
        val error = viewModel.uiState.value.chatError ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_error_detail_title)
            .setMessage(error)
            .setPositiveButton(R.string.voice_confirm, null)
            .show()
    }


    companion object {
        const val EXTRA_AUTO_START = "com.spake.brony.doubaoassistant.AUTO_START"
        private const val EXIT_APP_DIALOG = "exit_app"
        private const val CLEAR_HISTORY_DIALOG = "clear_history"
        private const val ASSISTANT_NAME_DIALOG = "assistant_name"
        private const val MESSAGE_INPUT_DIALOG = "message_input"
    }
}
