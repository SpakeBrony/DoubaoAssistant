package com.spake.brony.doubaoassistant.service

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.car.GeelyHvacController
import com.spake.brony.doubaoassistant.conversation.AiOperationExecutor
import com.spake.brony.doubaoassistant.conversation.ConversationCoordinator
import com.spake.brony.doubaoassistant.information.InformationController
import com.spake.brony.doubaoassistant.system.SystemOperationController
import com.spake.brony.doubaoassistant.data.model.AsrCommand
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.AsrStatus
import com.spake.brony.doubaoassistant.data.model.AssistantMode
import com.spake.brony.doubaoassistant.data.model.KwsCommand
import com.spake.brony.doubaoassistant.data.model.KwsSnapshot
import com.spake.brony.doubaoassistant.data.model.ListeningStatus
import com.spake.brony.doubaoassistant.data.network.DoubaoResponsesClient
import com.spake.brony.doubaoassistant.data.repository.AsrRepository
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ChatHistoryRepository
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.data.repository.KwsRepository
import com.spake.brony.doubaoassistant.service.notification.VoiceNotification
import com.spake.brony.doubaoassistant.conversation.ConversationCommands
import com.spake.brony.doubaoassistant.conversation.AiIntentPrompt
import com.spake.brony.doubaoassistant.speech.asr.AsrController
import com.spake.brony.doubaoassistant.speech.asr.DoubaoAsrRecognizer
import com.spake.brony.doubaoassistant.speech.audio.MicrophoneRecorder
import com.spake.brony.doubaoassistant.speech.audio.VoiceAudioPlayer
import com.spake.brony.doubaoassistant.speech.audio.VoiceLevelMeter
import com.spake.brony.doubaoassistant.speech.kws.KwsModel
import com.spake.brony.doubaoassistant.speech.kws.KwsRecognizer
import com.spake.brony.doubaoassistant.speech.tts.DoubaoTtsPlayer
import com.spake.brony.doubaoassistant.ui.overlay.ChatOverlay
import com.spake.brony.doubaoassistant.ui.permission.RuntimePermissionActivity
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KwsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val asr = AsrController()
    private val voiceCues = lazy { VoiceAudioPlayer(applicationContext) }
    private val overlay = lazy {
        ChatOverlay(applicationContext,
            onToggleChat = { paused -> controlChat(resume = paused) },
            onToggleMicrophone = { enabled ->
                if (enabled) stopListening(preserveReply = true) else startListening()
            })
    }
    @Volatile private var backgroundChatAvailable = false
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        main.post { if (!destroyed) synchronizeState(snapshot) }
    }
    private val responses by lazy { DoubaoResponsesClient(applicationContext) }
    private val information = lazy { InformationController(applicationContext) }
    private var destroyed = false
    private var exitRequested = false
    private var microphoneClosed = false
    private var singleAnswerWaiting = false
    private var sessionStarted = false
    private var historyReady = false
    private val historyLoaded = CompletableDeferred<Unit>()
    private var submittingText = false
    private val history by lazy {
        ChatHistoryRepository(applicationContext) {
            main.post { if (!destroyed) conversation.onHistoryError() }
        }
    }
    private val tts by lazy {
        DoubaoTtsPlayer(application) { version, update ->
            if (!destroyed) conversation.onTtsUpdate(version, update)
        }
    }
    private val preferences by lazy { AssistantPreferences(applicationContext) }
    private val notifications by lazy { VoiceNotification(applicationContext) }
    private val operationExecutor by lazy {
        AiOperationExecutor(
            car = GeelyHvacController(applicationContext, aiControlEnabled = { preferences.aiControlEnabled }),
            system = SystemOperationController(applicationContext),
            api = information.value,
        )
    }
    private val conversation: ConversationCoordinator by lazy {
        ConversationCoordinator(
            scope = scope,
            getString = application::getString,
            speech = tts,
            saveMessage = history::save,
            speed = { preferences.speed },
            createTopic = history::createTopic,
            onIdle = {
                if (!destroyed && asrRequest.enabled) {
                    pauseRecognition(AsrRepository.snapshot.value.copy(
                        status = AsrStatus.ERROR, error = getString(R.string.asr_failed)))
                }
            },
            assistantName = { preferences.assistantName },
            onReplyFinished = ::finishClosedMicrophone,
            onAsrCommand = { command ->
                if (!destroyed && (submittingText || activeRun != 0L && asrRequest.enabled)) executeVoiceCommand(command)
            },
            lastResponseId = history::lastResponseId,
            replyInstruction = { preferences.replyStyle.instruction },
            aiControlEnabled = { preferences.aiControlEnabled },
            answerMode = { preferences.answerMode },
            onSingleQuestionRecognized = ::stopSingleAnswerRecognition,
            executeOperation = operationExecutor::execute,
            judgeOperation = { text, candidates ->
                responses.classify(text, AiIntentPrompt.classificationInstructionFor(text), candidates)
            },
            responses = responses::stream,
        )
    }
    private var snapshot = KwsSnapshot()
    private var nextRun = 0L

    @Volatile private var activeRun = 0L
    @Volatile private var microphone: MicrophoneRecorder? = null
    private data class AsrRequest(
        val enabled: Boolean = false,
        val generation: Long = 0,
        val topicGeneration: Long = 0,
    )
    @Volatile private var asrRequest = AsrRequest()

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 主线程创建本地 MP3 提示音播放器；TTS 由 AudioTrack 流式播放。
        voiceCues.value
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
        notifications.createChannel()
        val topicAtStart = ConversationRepository.activeTopic
        topicAtStart?.let(history::createTopic)
        scope.launch {
            try {
                val topic = topicAtStart ?: history.currentTopic()
                val messages = history.latest(topic.id)
                // 读取磁盘期间用户可能已确认新话题，旧记录不能重新出现在页面。
                if (ConversationRepository.activeTopic == topicAtStart) conversation.restoreHistory(topic, messages)
                clearExpiredHistory()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { conversation.onHistoryError() }
            finally {
                if (!destroyed) {
                    historyReady = true
                    synchronizeState(snapshot)
                    historyLoaded.complete(Unit)
                }
            }
        }
        scope.launch {
            KwsRepository.isUiVisible.collect { synchronizeState(snapshot) }
        }
        scope.launch { ConversationRepository.snapshot.collect { updateOverlay() } }
        scope.launch { AsrRepository.snapshot.collect { updateOverlay() } }
        scope.launch { KwsRepository.audioLevel.collect { updateOverlay() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action in listOf(ACTION_START, ACTION_SEND_TEXT, ACTION_REPLAY) && !CredentialStore(this).isConfigured) {
            stopListening(getString(R.string.asr_credentials_missing))
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startListening()
            ACTION_SEND_TEXT -> intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
                scope.launch {
                    historyLoaded.await()
                    if (destroyed) return@launch
                    submittingText = true
                    try {
                        microphoneClosed = activeRun == 0L
                        // 文字请求独立于收音和聊天暂停状态；保留 KWS，暂停 ASR，防止旧语音结果打断。
                        if (!microphoneClosed) asr.onCommand(AsrCommand.STOP_REPLY)
                        singleAnswerWaiting = activeRun != 0L
                        sessionStarted = true
                        AsrRepository.clear()
                        synchronizeState(snapshot.copy(
                            status = if (microphoneClosed) ListeningStatus.STOPPED else snapshot.status,
                            chatPaused = !microphoneClosed && snapshot.chatPaused,
                            error = null,
                            command = null,
                        ))
                        conversation.onTextSubmitted(text)
                        if (activeRun == 0L) foregroundClosedMicrophone()
                    } finally {
                        submittingText = false
                        finishClosedMicrophone()
                    }
                }
            }
            ACTION_REFRESH_BACKGROUND -> {
                synchronizeState(snapshot)
                if (activeRun == 0L && !microphoneClosed && !conversation.hasActiveReply) stopSelf()
            }
            ACTION_REPLAY -> intent?.getStringExtra(EXTRA_MESSAGE_ID)
                ?.takeIf { it.isNotBlank() }?.let(conversation::replay)
            ACTION_PAUSE, ACTION_RESUME -> controlChat(resume = action == ACTION_RESUME)
            ACTION_CHECK_HISTORY -> {
                val checkNeeded = historyReady
                scope.launch {
                    historyLoaded.await()
                    if (destroyed) return@launch
                    // 冷启动已在恢复历史时检查，后台返回则重新查询。
                    if (checkNeeded) clearExpiredHistory()
                    if (activeRun == 0L && !microphoneClosed && !conversation.hasActiveReply) stopSelf(startId)
                }
            }
            ACTION_END_TOPIC, ACTION_CLEAR_HISTORY -> {
                resetTopic(clearHistory = action == ACTION_CLEAR_HISTORY)
                if (activeRun == 0L && !microphoneClosed) stopSelf()
            }
            ACTION_STOP -> stopListening(preserveReply = true)
            else -> {
                exitRequested = action == ACTION_EXIT
                stopListening()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun clearExpiredHistory() {
        val topic = ConversationRepository.activeTopic
        val topicGeneration = asrRequest.topicGeneration
        val cutoff = System.currentTimeMillis() - 60L * 60 * 60 * 1000
        try {
            val lastTime = history.lastMessageTime() ?: return
            if (lastTime <= 0 || lastTime >= cutoff || destroyed ||
                asrRequest.topicGeneration != topicGeneration || ConversationRepository.activeTopic != topic) return
            // 查询期间的新消息已显示但可能仍在排队落盘，不能按旧查询结果清除它。
            if (ConversationRepository.snapshot.value.content.messages.any { it.timeMillis >= cutoff }) return
            resetTopic(clearHistory = true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { conversation.onHistoryError() }
    }

    private fun resetTopic(clearHistory: Boolean) {
        asrRequest = asrRequest.copy(generation = asrRequest.generation + 1,
            topicGeneration = asrRequest.topicGeneration + 1)
        AsrRepository.clear()
        conversation.newTopic()
        if (clearHistory) history.clearAll(checkNotNull(ConversationRepository.activeTopic))
        synchronizeState(snapshot.copy(command = null))
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopListening(getString(R.string.microphone_denied))
            return
        }
        if (activeRun != 0L) return
        try {
            microphoneClosed = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(VoiceNotification.ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(VoiceNotification.ID, notification())
            }
            val run = ++nextRun
            activeRun = run
            AsrRepository.clear()
            if (!sessionStarted) {
                sessionStarted = true
                conversation.startSession()
            }
            asr.start(KwsRepository.isUiVisible.value)
            if (preferences.answerMode == AnswerMode.SINGLE) {
                // 单次模式启动只监听唤醒词；保留关麦前或文字输入正在进行的 AI/TTS。
                singleAnswerWaiting = true
                asr.onCommand(AsrCommand.STOP_REPLY)
            } else if (snapshot.chatPaused) {
                asr.onCommand(AsrCommand.STOP_REPLY)
            }
            synchronizeState(snapshot.copy(
                status = ListeningStatus.STARTING,
                keyword = "",
                command = null,
                echoCancellation = false,
                error = null,
            ))
            worker.execute { listen(run) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to start listening", e)
            stopListening(getString(R.string.kws_failed))
        }
    }


    private fun listen(run: Long) {
        val capture = MicrophoneRecorder(application::getString, KwsModel.SAMPLE_RATE)
        val keywords = KwsRecognizer(assets, application::getString)
        val recognizer = DoubaoAsrRecognizer(application, AsrRepository.snapshot.value.conversation) { generation, value ->
            main.post {
                val request = asrRequest
                if (activeRun == run && request.generation == generation && request.enabled) {
                    onRecognition(value)
                }
            }
        }
        try {
            if (activeRun != run) return
            keywords.start(preferences.assistantName)
            if (activeRun != run) return
            microphone = capture
            // 发布采音对象后再检查代次，覆盖关麦发生在对象交接之前的情况。
            if (activeRun != run) return
            capture.start()
            val echoCancellation = capture.echoCancellationEnabled
            main.post {
                if (activeRun == run) synchronizeState(snapshot.copy(
                    status = ListeningStatus.LISTENING, echoCancellation = echoCancellation))
            }
            val pcm = ShortArray(1600)
            while (activeRun == run) {
                val count = capture.read(pcm)
                if (activeRun != run) break
                check(count > 0) { "${getString(R.string.microphone_read_failed)} ($count)" }
                val currentName = preferences.assistantName
                if (keywords.updateName(currentName)) {
                    main.post {
                        if (activeRun == run && preferences.assistantName == currentName) notifications.update(notification())
                    }
                }
                val level = VoiceLevelMeter.measure(pcm, count)
                main.post { if (activeRun == run) KwsRepository.publishAudioLevel(level) }
                keywords.process(pcm, count, isActive = { activeRun == run }) { keyword, name ->
                    main.post { onKeyword(run, keyword, name) }
                }
                val request = asrRequest
                // 前台或小窗自动允许聊天；后台收到恢复口令后也可继续送音频。
                recognizer.process(pcm, count, request.enabled,
                    request.generation, request.topicGeneration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice input failed", e)
            main.post { if (activeRun == run) stopListening(getString(R.string.kws_failed)) }
        } catch (e: LinkageError) {
            Log.e(TAG, "Voice input native library failed", e)
            main.post { if (activeRun == run) stopListening(getString(R.string.kws_native_failed)) }
        } finally {
            try { recognizer.close() }
            finally {
                try { capture.close() }
                finally {
                    if (microphone === capture) microphone = null
                    keywords.close()
                }
            }
        }
    }

    private fun stopListening(error: String? = null, preserveReply: Boolean = false) {
        if (!preserveReply) singleAnswerWaiting = false
        microphoneClosed = preserveReply
        activeRun = 0L
        if (!preserveReply && voiceCues.isInitialized()) voiceCues.value.stop()
        asr.stop()
        AsrRepository.stop()
        if (!preserveReply) conversation.stopSession()
        KwsRepository.publishAudioLevel(0f)
        val microphoneStopped = microphone?.stop() ?: true
        val stopError = error ?: if (microphoneStopped) null else getString(R.string.voice_command_mic_failed)
        synchronizeState(snapshot.copy(
            status = if (stopError == null) ListeningStatus.STOPPED else ListeningStatus.ERROR,
            command = null,
            echoCancellation = false,
            error = stopError,
            chatPaused = preserveReply && snapshot.chatPaused,
        ))
        if (preserveReply) {
            foregroundClosedMicrophone()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopSingleAnswerRecognition() {
        if (destroyed || activeRun == 0L || !asrRequest.enabled) return
        // 问题确认后只停 ASR，当前 AI/TTS 继续，保留 KWS 等待下一次口令。
        singleAnswerWaiting = true
        asr.onCommand(AsrCommand.STOP_REPLY)
        synchronizeState(snapshot.copy(command = null))
    }

    private fun finishClosedMicrophone() {
        if (!destroyed && !submittingText && microphoneClosed && activeRun == 0L) {
            foregroundClosedMicrophone()
        }
    }

    private fun foregroundClosedMicrophone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关麦后保留用户开启的聊天窗口；待机不再占用 microphone 或 mediaPlayback 类型。
            val type = when {
                conversation.hasActiveReply -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            startForeground(VoiceNotification.ID, notification(), type)
        } else {
            startForeground(VoiceNotification.ID, notification())
        }
    }

    private fun synchronizeState(value: KwsSnapshot) {
        val visible = KwsRepository.isUiVisible.value
        val running = activeRun != 0L && (value.status == ListeningStatus.LISTENING || value.status == ListeningStatus.STARTING)
        updateBackgroundChat(visible, running)
        asr.onUiVisibilityChanged(visible, backgroundChatAvailable)
        snapshot = resolveSnapshot(value, running)
        val requested = snapshot.asrRequested
        updateAsrRequest(requested)
        updateConversationAvailability(value, requested)
        KwsRepository.publish(snapshot)
        updateOverlay()
    }

    private fun updateBackgroundChat(visible: Boolean, running: Boolean) {
        backgroundChatAvailable = if (!destroyed && !visible && (running || microphoneClosed) && preferences.backgroundChat && Settings.canDrawOverlays(this)) {
            overlay.value.show(preferences.appearance, preferences.overlayStyle)
        } else {
            if (overlay.isInitialized()) overlay.value.close()
            false
        }
    }

    private fun resolveSnapshot(value: KwsSnapshot, running: Boolean): KwsSnapshot =
        value.copy(
            mode = asr.mode,
            chatPaused = if (running) asr.mode == AssistantMode.MUTED else value.chatPaused,
            asrRequested = historyReady &&
                value.status == ListeningStatus.LISTENING && asr.asrRequested,
        )

    private fun updateAsrRequest(requested: Boolean) {
        if (requested != asrRequest.enabled) {
            asrRequest = asrRequest.copy(enabled = requested, generation = asrRequest.generation + 1)
            if (!requested) AsrRepository.stop()
        }
    }

    private fun updateConversationAvailability(value: KwsSnapshot, requested: Boolean) {
        val preserveReply = microphoneClosed || singleAnswerWaiting || RuntimePermissionActivity.hasPendingRequest ||
            (information.isInitialized() && information.value.hasActiveQuery) ||
            (value.status == ListeningStatus.STARTING && conversation.hasActiveReply)
        conversation.setEnabled((singleAnswerWaiting || !snapshot.chatPaused) && (preserveReply || requested), listening = requested)
    }

    private fun controlChat(resume: Boolean) {
        singleAnswerWaiting = false
        if (activeRun != 0L) {
            asr.onUiVisibilityChanged(KwsRepository.isUiVisible.value, backgroundChatAvailable)
            if (resume) asr.onCommand(KwsCommand.RESUME) else asr.onCommand(AsrCommand.STOP_REPLY)
            if (resume) restartAsrRequest()
            synchronizeState(snapshot.copy(command = null))
        } else {
            microphoneClosed = true
            sessionStarted = true
            synchronizeState(snapshot.copy(command = null, chatPaused = !resume))
            foregroundClosedMicrophone()
        }
    }

    private fun updateOverlay() {
        if (overlay.isInitialized()) overlay.value.update(snapshot, AsrRepository.snapshot.value, ConversationRepository.snapshot.value,
            KwsRepository.audioLevel.value)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlay.isInitialized()) overlay.value.close()
        synchronizeState(snapshot)
    }

    private fun restartAsrRequest() {
        asrRequest = asrRequest.copy(generation = asrRequest.generation + 1)
        conversation.restartListening()
    }

    private fun onRecognition(value: AsrSnapshot) {
        when (value.status) {
            AsrStatus.REQUEST_ENDED, AsrStatus.RECEIVE_TIMEOUT -> {
                // 正常轮换只换 ASR 请求，不能调用会取消 AI/TTS 的 restartAsrRequest()。
                val generation = asrRequest.generation
                conversation.onAsrResult(value)
                if (!asrRequest.enabled || generation != asrRequest.generation) return
                asrRequest = asrRequest.copy(generation = asrRequest.generation + 1)
                AsrRepository.publish(value.copy(status = AsrStatus.STARTING, error = null))
                Log.i(TAG, "Continue ASR: reason=${value.status} generation=${asrRequest.generation}")
            }
            AsrStatus.ERROR -> pauseRecognition(value)
            else -> {
                AsrRepository.publish(value)
                conversation.onAsrResult(value)
            }
        }
    }

    private fun pauseRecognition(value: AsrSnapshot) {
        singleAnswerWaiting = false
        asr.onCommand(AsrCommand.STOP_REPLY)
        synchronizeState(snapshot)
        AsrRepository.publish(value)
    }

    private fun onKeyword(run: Long, keyword: String, detectedName: String) {
        if (run == 0L || activeRun != run || destroyed || preferences.assistantName != detectedName) return
        val command = KwsCommand.fromKeyword(keyword, detectedName) ?: return
        executeKwsCommand(command, keyword)
    }

    private fun executeKwsCommand(command: KwsCommand, keyword: String? = null) {
        val action = command.controlCommand
        if (action == null) {
            asr.onUiVisibilityChanged(KwsRepository.isUiVisible.value, backgroundChatAvailable)
            if (!asr.onCommand(command)) return
            singleAnswerWaiting = false
            restartAsrRequest()
        } else {
            // 即使退到后台仍允许聊天，也必须丢弃命中前的 ASR 旧句；关麦不能取消当前 AI/TTS。
            asrRequest = asrRequest.copy(generation = asrRequest.generation + 1)
            conversation.clearRecognition()
            executeVoiceCommand(action, keyword)
        }
        synchronizeState(snapshot.copy(keyword = keyword ?: snapshot.keyword, command = command,
            detectionCount = snapshot.detectionCount + if (keyword != null) 1 else 0))
        if (action == null) {
            val greeting = KwsCommand.isGreeting(keyword, preferences.assistantName)
            recordVoiceCommand(getString(if (greeting) R.string.voice_command_awake else R.string.voice_command_stopped), keyword)
            voiceCues.value.play(if (greeting) R.raw.wozai else R.raw.haode)
        }
    }

    private fun executeVoiceCommand(command: AsrCommand, keyword: String? = null) {
        val reply = getString(ConversationCommands.rules.first { it.command == command }.reply)
        when (command) {
            AsrCommand.CLOSE_MICROPHONE -> {
                stopListening(preserveReply = true)
            }
            AsrCommand.STOP_REPLY -> {
                singleAnswerWaiting = false
                if (submittingText && activeRun == 0L) {
                    controlChat(resume = false)
                } else if (!asr.onCommand(command)) {
                    recordVoiceCommand(getString(R.string.voice_command_stop_failed), keyword)
                    return
                }
                conversation.stopSession()
                synchronizeState(snapshot.copy(command = null))
            }
            AsrCommand.RETURN_HOME -> {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to open system home", e)
                    recordVoiceCommand(getString(R.string.voice_command_return_failed), keyword)
                    return
                }
                singleAnswerWaiting = false
                if (submittingText && activeRun == 0L) conversation.restartListening()
                asr.onCommand(command, keepChatOnReturnHome = preferences.backgroundChat && Settings.canDrawOverlays(this))
                synchronizeState(snapshot.copy(command = null))
            }
        }
        recordVoiceCommand(reply, keyword)
        voiceCues.value.play()
    }

    private fun recordVoiceCommand(reply: String, keyword: String?) {
        if (historyReady) {
            conversation.recordVoiceCommand(keyword, reply)
            return
        }
        // KWS 操作立即执行，记录等旧话题加载完成；切换话题后不能补入旧口令。
        val topicGeneration = asrRequest.topicGeneration
        scope.launch {
            historyLoaded.await()
            if (!destroyed && asrRequest.topicGeneration == topicGeneration) {
                conversation.recordVoiceCommand(keyword, reply)
            }
        }
    }

    private fun notification(): Notification = notifications.build(
        preferences.assistantName, microphoneClosed, conversation.hasActiveReply)

    override fun onDestroy() {
        destroyed = true
        getSystemService(AppOpsManager::class.java).stopWatchingMode(overlayPermissionListener)
        backgroundChatAvailable = false
        if (overlay.isInitialized()) overlay.value.close()
        scope.cancel()
        // 没有页面绑定保活，失败后服务会销毁；保留错误供页面读取。
        stopListening(snapshot.error)
        if (voiceCues.isInitialized()) voiceCues.value.close()
        tts.close()
        history.close()
        responses.close()
        if (information.isInitialized()) information.value.close()
        if (exitRequested) {
            // 排在录音线程的 finally 之后，并等 SQL 写入排空，避免直接杀进程丢记录。
            worker.execute {
                runBlocking { history.awaitClosed() }
                Process.killProcess(Process.myPid())
            }
        }
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.spake.brony.doubaoassistant.START_KWS"
        const val ACTION_STOP = "com.spake.brony.doubaoassistant.STOP_KWS"
        const val ACTION_EXIT = "com.spake.brony.doubaoassistant.EXIT_APP"
        const val ACTION_END_TOPIC = "com.spake.brony.doubaoassistant.END_TOPIC"
        const val ACTION_CLEAR_HISTORY = "com.spake.brony.doubaoassistant.CLEAR_HISTORY"
        const val ACTION_CHECK_HISTORY = "com.spake.brony.doubaoassistant.CHECK_HISTORY"
        const val ACTION_REFRESH_BACKGROUND = "com.spake.brony.doubaoassistant.REFRESH_BACKGROUND"
        const val ACTION_PAUSE = "com.spake.brony.doubaoassistant.PAUSE_CHAT"
        const val ACTION_RESUME = "com.spake.brony.doubaoassistant.RESUME_CHAT"
        const val ACTION_REPLAY = "com.spake.brony.doubaoassistant.REPLAY_REPLY"
        const val ACTION_SEND_TEXT = "com.spake.brony.doubaoassistant.SEND_TEXT"
        const val EXTRA_MESSAGE_ID = "reply_message_id"
        const val EXTRA_TEXT = "user_text"
        private const val TAG = "DoubaoKws"
    }
}
