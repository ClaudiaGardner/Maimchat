package com.l2dchat.chat.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.l2dchat.chat.AvatarIntent
import com.l2dchat.chat.AvatarIntentCodec
import com.l2dchat.chat.CallAudioPayload
import com.l2dchat.chat.CallRuntimePhase
import com.l2dchat.chat.CallStatePayload
import com.l2dchat.chat.CallTtsRequest
import com.l2dchat.chat.ChatWebSocketManager
import com.l2dchat.chat.ChatWebSocketManager.ChatMessage
import com.l2dchat.chat.ChatWebSocketManager.ConnectionState
import com.l2dchat.chat.DeviceRequest
import com.l2dchat.chat.DeviceRequestCodec
import com.l2dchat.chat.MessageBase
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import com.l2dchat.media.DeviceAudioPlayer
import com.l2dchat.media.DeviceMediaPayloadEncoder
import com.l2dchat.media.DeviceTextToSpeechPlayer
import com.l2dchat.wallpaper.WallpaperComm
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatConnectionService : Service() {

    private val logger = L2DLogger.module(LogModule.CHAT)

    private val clients = CopyOnWriteArraySet<Messenger>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val incomingHandler = IncomingHandler(this)
    private val messenger = Messenger(incomingHandler)

    private lateinit var manager: ChatWebSocketManager
    private lateinit var audioPlayer: DeviceAudioPlayer
    private lateinit var systemTtsPlayer: DeviceTextToSpeechPlayer
    private var speakerEnabled: Boolean = true
    private var isSpeaking: Boolean = false
    private var callModeActive: Boolean = false
    private var callVideoEnabled: Boolean = false
    private var callPhase: CallRuntimePhase = CallRuntimePhase.IDLE
    private var callStateDetail: String? = null
    private var pendingTurnId: String? = null
    private var pendingTtsRequestId: String? = null
    private var pendingReplyText: String? = null
    private var currentSpeechText: String? = null
    private var currentSpeechRequestId: String? = null
    private var preparingRemoteAudio: Boolean = false
    private var lastVoiceReceivedAt: Long = 0L
    private var turnTimeoutJob: Job? = null
    private var ttsTimeoutJob: Job? = null
    private var botTextSettleJob: Job? = null
    private var pendingBotSpeech: String = ""
    private var pendingBotReplyMessageId: String? = null

    private var lastKnownUrl: String? = null
    private var lastKnownPlatform: String? = null
    private var lastKnownAuth: String? = null
    private var lastKnownNickname: String? = null
    private var lastKnownReceiverId: String? = null
    private var lastKnownReceiverNickname: String? = null

    override fun onCreate() {
        super.onCreate()
        manager = ChatWebSocketManager()
        audioPlayer =
                DeviceAudioPlayer(
                        context = applicationContext,
                        onPlaybackError = ::handleRemotePlaybackError,
                        onPlaybackStateChanged = ::handlePlaybackStateChanged
                )
        systemTtsPlayer =
                DeviceTextToSpeechPlayer(
                        context = applicationContext,
                        onPlaybackError = { error ->
                            serviceScope.launch { handleSystemTtsError(error) }
                        },
                        onPlaybackStateChanged = { speaking ->
                            serviceScope.launch { handlePlaybackStateChanged(speaking) }
                        }
                )
        speakerEnabled =
                getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
                        .getBoolean(KEY_SPEAKER_ENABLED, true)
        manager.setVoiceReceivedCallback(::handleVoicePayload)
        manager.setBotTextReceivedCallback(::handleBotTextReceived)
        manager.setCallAudioReceivedCallback(::handleCallAudioReceived)
        manager.setCallStateReceivedCallback(::handleCallStateReceived)
        manager.setMotionTriggerCallback { group, index, loop ->
            broadcastMotion(group, index, loop)
        }
        manager.setAvatarIntentCallback { intent -> broadcastAvatarIntent(intent) }
        manager.setDeviceRequestCallback { request -> broadcastDeviceRequest(request) }
        manager.setActiveModel(applicationContext, restoreModelName())
        applyStoredConfiguration()
        startObservers()
        logger.info("ChatConnectionService created (pid=${Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 保持粘性，便于在进程被系统回收后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.info("ChatConnectionService destroyed")
        serviceScope.cancel()
        clients.clear()
        turnTimeoutJob?.cancel()
        ttsTimeoutJob?.cancel()
        botTextSettleJob?.cancel()
        audioPlayer.stop()
        systemTtsPlayer.shutdown()
        manager.disconnect()
    }

    private fun restoreModelName(): String? {
        val prefs = getSharedPreferences(WallpaperComm.PREF_WALLPAPER, MODE_PRIVATE)
        val folder = prefs.getString(WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER, null)
        return folder?.substringAfterLast('/')?.ifBlank { null }
    }

    private fun applyStoredConfiguration() {
        val prefs = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
        lastKnownUrl = prefs.getString(KEY_LAST_URL, null)?.takeUnless { it.isNullOrBlank() }
        lastKnownPlatform = prefs.getString(KEY_PLATFORM, null)?.takeUnless { it.isNullOrBlank() }
        lastKnownAuth = prefs.getString(KEY_AUTH_TOKEN, null)?.takeUnless { it.isNullOrBlank() }
        lastKnownNickname = prefs.getString(KEY_NICKNAME, null)
        lastKnownReceiverId = prefs.getString(KEY_RECEIVER_ID, null)?.ifBlank { null }
        lastKnownReceiverNickname = prefs.getString(KEY_RECEIVER_NICKNAME, null)?.ifBlank { null }

        lastKnownPlatform?.let { manager.updatePlatformPreference(it) }
        manager.setConnectionConfig(manager.getPlatform(), lastKnownAuth)
        lastKnownNickname?.takeUnless { it.isNullOrBlank() }?.let { manager.setUserProfile(it) }
        if (!lastKnownReceiverId.isNullOrBlank() || !lastKnownReceiverNickname.isNullOrBlank()) {
            manager.setReceiverInfo(lastKnownReceiverId, lastKnownReceiverNickname)
        }
        lastKnownUrl?.let { url -> manager.connect(url, lastKnownPlatform, lastKnownAuth) }
    }

    private fun startObservers() {
        serviceScope.launch {
            manager.connectionState.collect { state -> broadcastConnectionState(state) }
        }
        serviceScope.launch {
            var lastBroadcastId: String? = null
            manager.messages.collect { list ->
                val last = list.lastOrNull() ?: return@collect
                if (last.id == lastBroadcastId) return@collect
                lastBroadcastId = last.id
                broadcastChatMessage(last)
            }
        }
        serviceScope.launch {
            var lastStandardId: String? = null
            manager.standardMessages.collect { list ->
                val last = list.lastOrNull() ?: return@collect
                val id = last.messageInfo.messageId ?: return@collect
                if (id == lastStandardId) return@collect
                lastStandardId = id
                broadcastStandardMessage(last)
            }
        }
        serviceScope.launch { manager.errors.collect { message -> notifyError(message) } }
    }

    private fun handleVoicePayload(payload: String) {
        lastVoiceReceivedAt = System.currentTimeMillis()
        if (callModeActive) {
            turnTimeoutJob?.cancel()
            ttsTimeoutJob?.cancel()
            botTextSettleJob?.cancel()
            pendingBotSpeech = ""
            pendingBotReplyMessageId = null
            pendingTtsRequestId = null
            pendingReplyText = null
        }
        if (!speakerEnabled) {
            finishCallTurn()
            return
        }
        playRemoteAudio(payload, currentSpeechText, currentSpeechRequestId)
    }

    private fun handleBotTextReceived(message: ChatMessage, includesVoice: Boolean) {
        if (!callModeActive) return
        turnTimeoutJob?.cancel()
        val speech = cleanReplyText(message.content)
        if (speech.isBlank()) {
            finishCallTurn()
            return
        }
        if (includesVoice ||
                        System.currentTimeMillis() - lastVoiceReceivedAt <
                                LEGACY_VOICE_DEDUP_WINDOW_MS
        ) {
            pendingReplyText = speech
            if (!isSpeaking) finishCallTurn()
            return
        }
        if (!speakerEnabled) {
            finishCallTurn()
            return
        }
        queueBotSpeech(speech, message.id)
    }

    private fun queueBotSpeech(speech: String, replyMessageId: String) {
        pendingBotSpeech = mergeReplyChunks(pendingBotSpeech, speech)
        pendingBotReplyMessageId = replyMessageId
        updateCallState(CallRuntimePhase.THINKING, "正在整理回复")
        botTextSettleJob?.cancel()
        botTextSettleJob =
                serviceScope.launch {
                    delay(BOT_TEXT_SETTLE_MS)
                    val settledSpeech = pendingBotSpeech
                    val settledMessageId = pendingBotReplyMessageId ?: replyMessageId
                    pendingBotSpeech = ""
                    pendingBotReplyMessageId = null
                    requestRemoteTts(settledSpeech, settledMessageId)
                }
    }

    private fun requestRemoteTts(speech: String, replyMessageId: String) {
        if (!callModeActive || !speakerEnabled || speech.isBlank()) {
            finishCallTurn()
            return
        }
        val requestId = "tts-${UUID.randomUUID()}"
        pendingTtsRequestId = requestId
        pendingReplyText = speech
        updateCallState(CallRuntimePhase.SYNTHESIZING, "正在生成回复语音")
        manager.sendCallTtsRequest(
                CallTtsRequest(
                        requestId = requestId,
                        turnId = pendingTurnId,
                        replyMessageId = replyMessageId,
                        text = speech
                )
        )
        ttsTimeoutJob?.cancel()
        ttsTimeoutJob =
                serviceScope.launch {
                    delay(REMOTE_TTS_TIMEOUT_MS)
                    if (pendingTtsRequestId == requestId) {
                        logger.warn("远端 TTS 等待超时，切换到 Android 系统语音")
                        fallbackToSystemTts("远端语音等待超时")
                    }
                }
    }

    private fun mergeReplyChunks(current: String, incoming: String): String {
        if (current.isBlank()) return incoming
        if (incoming == current || current.endsWith(incoming)) return current
        if (incoming.startsWith(current)) return incoming
        val separator =
                if (current.last().isLetterOrDigit() &&
                                incoming.first().isLetterOrDigit() &&
                                current.last().code < 128 &&
                                incoming.first().code < 128
                ) {
                    " "
                } else {
                    ""
                }
        return (current + separator + incoming).take(MAX_TTS_TEXT_CHARS)
    }

    private fun handleCallAudioReceived(payload: CallAudioPayload) {
        if (!callModeActive || payload.requestId != pendingTtsRequestId) {
            logger.debug(
                    "忽略过期通话音频 request=${payload.requestId} pending=$pendingTtsRequestId",
                    throttleMs = 1_000L,
                    throttleKey = "stale_call_audio"
            )
            return
        }
        ttsTimeoutJob?.cancel()
        pendingTtsRequestId = null
        val speech = payload.text.ifBlank { pendingReplyText.orEmpty() }
        pendingReplyText = null
        playRemoteAudio(payload.audio, speech, payload.requestId)
    }

    private fun handleCallStateReceived(payload: CallStatePayload) {
        if (!callModeActive ||
                        payload.phase != CallRuntimePhase.ERROR ||
                        payload.requestId != pendingTtsRequestId
        ) {
            return
        }
        logger.warn("远端通话语音失败：${payload.message ?: "未知错误"}")
        serviceScope.launch {
            fallbackToSystemTts(payload.message ?: "远端语音生成失败")
        }
    }

    private fun playRemoteAudio(payload: String, speech: String?, requestId: String?) {
        if (!speakerEnabled) {
            finishCallTurn()
            return
        }
        preparingRemoteAudio = true
        currentSpeechText = speech
        currentSpeechRequestId = requestId
        systemTtsPlayer.stop()
        serviceScope.launch {
            audioPlayer.play(payload).onFailure { error ->
                preparingRemoteAudio = false
                if (!currentSpeechText.isNullOrBlank() && callModeActive) {
                    logger.warn("远端语音无法播放，切换到 Android 系统语音", error)
                    fallbackToSystemTts("远端音频播放失败")
                } else {
                    notifyError("语音播放失败：${error.message ?: "未知错误"}")
                    finishCallTurn()
                }
            }
        }
    }

    private fun handleRemotePlaybackError(error: String) {
        val canFallback = !currentSpeechText.isNullOrBlank() && callModeActive
        if (canFallback) preparingRemoteAudio = true
        serviceScope.launch {
            if (canFallback) {
                logger.warn("$error，切换到 Android 系统语音")
                fallbackToSystemTts(error)
            } else {
                notifyError(error)
                finishCallTurn()
            }
        }
    }

    private suspend fun fallbackToSystemTts(reason: String) {
        val speech = pendingReplyText ?: currentSpeechText
        val requestId =
                pendingTtsRequestId
                        ?: currentSpeechRequestId
                        ?: "local-tts-${UUID.randomUUID()}"
        ttsTimeoutJob?.cancel()
        pendingTtsRequestId = null
        pendingReplyText = null
        if (speech.isNullOrBlank() || !speakerEnabled || !callModeActive) {
            finishCallTurn()
            return
        }

        preparingRemoteAudio = true
        currentSpeechText = speech
        currentSpeechRequestId = requestId
        audioPlayer.stop()
        updateCallState(CallRuntimePhase.SYNTHESIZING, "$reason，使用设备语音")
        var lastError: Throwable? = null
        repeat(SYSTEM_TTS_READY_RETRIES) {
            val result = systemTtsPlayer.speak(speech, requestId)
            if (result.isSuccess) {
                preparingRemoteAudio = false
                return
            }
            lastError = result.exceptionOrNull()
            delay(SYSTEM_TTS_RETRY_DELAY_MS)
        }
        preparingRemoteAudio = false
        val message = "回复语音播放失败：${lastError?.message ?: "系统语音不可用"}"
        notifyError(message)
        updateCallState(CallRuntimePhase.ERROR, message)
        delay(ERROR_STATE_HOLD_MS)
        finishCallTurn()
    }

    private fun handleSystemTtsError(error: String) {
        if (currentSpeechText.isNullOrBlank()) {
            logger.warn(error)
            return
        }
        preparingRemoteAudio = false
        isSpeaking = false
        broadcastSpeakingState()
        notifyError(error)
        updateCallState(CallRuntimePhase.ERROR, error)
        serviceScope.launch {
            delay(ERROR_STATE_HOLD_MS)
            finishCallTurn()
        }
    }

    private fun handlePlaybackStateChanged(speaking: Boolean) {
        isSpeaking = speaking
        broadcastSpeakingState()
        if (!callModeActive) return
        if (speaking) {
            preparingRemoteAudio = false
            updateCallState(CallRuntimePhase.SPEAKING, "正在回应")
        } else if (!preparingRemoteAudio) {
            currentSpeechText = null
            currentSpeechRequestId = null
            finishCallTurn()
        }
    }

    private fun beginCallTurn(turnId: String) {
        if (!callModeActive) return
        pendingTurnId = turnId
        pendingTtsRequestId = null
        pendingReplyText = null
        botTextSettleJob?.cancel()
        pendingBotSpeech = ""
        pendingBotReplyMessageId = null
        turnTimeoutJob?.cancel()
        ttsTimeoutJob?.cancel()
        updateCallState(CallRuntimePhase.THINKING, "正在思考")
        turnTimeoutJob =
                serviceScope.launch {
                    delay(BOT_REPLY_TIMEOUT_MS)
                    if (callModeActive &&
                                    pendingTurnId == turnId &&
                                    callPhase == CallRuntimePhase.THINKING
                    ) {
                        updateCallState(CallRuntimePhase.ERROR, "这次回复等待超时")
                        delay(ERROR_STATE_HOLD_MS)
                        finishCallTurn()
                    }
                }
    }

    private fun finishCallTurn() {
        turnTimeoutJob?.cancel()
        ttsTimeoutJob?.cancel()
        botTextSettleJob?.cancel()
        pendingTurnId = null
        pendingTtsRequestId = null
        pendingReplyText = null
        pendingBotSpeech = ""
        pendingBotReplyMessageId = null
        if (callModeActive) {
            updateCallState(CallRuntimePhase.LISTENING, "正在聆听")
        } else {
            updateCallState(CallRuntimePhase.IDLE, null)
        }
    }

    private fun cleanReplyText(value: String): String {
        val text = value.trim()
        if (text.matches(Regex("^\\[[^]]+]$"))) return ""
        return text.take(MAX_TTS_TEXT_CHARS)
    }

    private fun updateCallState(phase: CallRuntimePhase, detail: String?) {
        if (callPhase == phase && callStateDetail == detail) return
        callPhase = phase
        callStateDetail = detail
        logger.info(
                "通话状态=$phase turn=${pendingTurnId ?: "-"} " +
                        "tts=${pendingTtsRequestId ?: "-"} detail=${detail ?: "-"}"
        )
        broadcastCallState()
    }

    private fun broadcastConnectionState(state: ConnectionState) {
        val bundle =
                Bundle().apply {
                    putInt(ChatServiceProtocol.EXTRA_CONNECTION_STATE, state.ordinal)
                    putString(
                            ChatServiceProtocol.EXTRA_CONNECTION_LABEL,
                            when (state) {
                                ConnectionState.DISCONNECTED -> "未连接"
                                ConnectionState.CONNECTING -> "连接中"
                                ConnectionState.CONNECTED -> "已连接"
                                ConnectionState.ERROR -> "错误"
                            }
                    )
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_CONNECTION_STATE, bundle)
    }

    private fun broadcastChatMessage(message: ChatMessage) {
        val bundle =
                Bundle().apply {
                    putString(ChatServiceProtocol.EXTRA_MESSAGE_ID, message.id)
                    putString(ChatServiceProtocol.EXTRA_MESSAGE_CONTENT, message.content)
                    putBoolean(ChatServiceProtocol.EXTRA_MESSAGE_FROM_USER, message.isFromUser)
                    putLong(ChatServiceProtocol.EXTRA_MESSAGE_TIMESTAMP, message.timestamp)
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_NEW_MESSAGE, bundle)
    }

    private fun broadcastMotion(group: String, index: Int, loop: Boolean) {
        val bundle =
                Bundle().apply {
                    putString(ChatServiceProtocol.EXTRA_MOTION_GROUP, group)
                    putInt(ChatServiceProtocol.EXTRA_MOTION_INDEX, index)
                    putBoolean(ChatServiceProtocol.EXTRA_MOTION_LOOP, loop)
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_MOTION, bundle)
    }

    private fun broadcastAvatarIntent(intent: AvatarIntent) {
        val bundle =
                Bundle().apply {
                    putString(
                            ChatServiceProtocol.EXTRA_AVATAR_INTENT_JSON,
                            AvatarIntentCodec.toJson(intent)
                    )
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_AVATAR_INTENT, bundle)
    }

    private fun broadcastDeviceRequest(request: DeviceRequest) {
        val bundle =
                Bundle().apply {
                    putString(
                            ChatServiceProtocol.EXTRA_DEVICE_REQUEST_JSON,
                            DeviceRequestCodec.toJson(request)
                    )
                }
        sendToClients(ChatServiceProtocol.MSG_EVENT_DEVICE_REQUEST, bundle)
    }

    private fun broadcastSpeakingState(target: Messenger? = null) {
        val bundle =
                Bundle().apply {
                    putBoolean(ChatServiceProtocol.EXTRA_IS_SPEAKING, isSpeaking)
                }
        if (target != null) {
            sendToClient(target, ChatServiceProtocol.MSG_EVENT_SPEAKING_STATE, bundle)
        } else {
            sendToClients(ChatServiceProtocol.MSG_EVENT_SPEAKING_STATE, bundle)
        }
    }

    private fun broadcastCallState(target: Messenger? = null) {
        val bundle =
                Bundle().apply {
                    putBoolean(ChatServiceProtocol.EXTRA_CALL_ACTIVE, callModeActive)
                    putBoolean(ChatServiceProtocol.EXTRA_CALL_VIDEO_ENABLED, callVideoEnabled)
                    putString(ChatServiceProtocol.EXTRA_CALL_PHASE, callPhase.name)
                    putString(ChatServiceProtocol.EXTRA_CALL_TURN_ID, pendingTurnId)
                    putString(ChatServiceProtocol.EXTRA_CALL_TTS_REQUEST_ID, pendingTtsRequestId)
                    putString(ChatServiceProtocol.EXTRA_CALL_STATE_DETAIL, callStateDetail)
                }
        if (target != null) {
            sendToClient(target, ChatServiceProtocol.MSG_EVENT_CALL_STATE, bundle)
        } else {
            sendToClients(ChatServiceProtocol.MSG_EVENT_CALL_STATE, bundle)
        }
    }

    private fun sendSnapshot(target: Messenger? = null) {
        val snapshot =
                ArrayList<Bundle>(manager.messages.value.size).apply {
                    manager.messages.value.forEach { m ->
                        add(
                                Bundle().apply {
                                    putString(ChatServiceProtocol.EXTRA_MESSAGE_ID, m.id)
                                    putString(ChatServiceProtocol.EXTRA_MESSAGE_CONTENT, m.content)
                                    putBoolean(
                                            ChatServiceProtocol.EXTRA_MESSAGE_FROM_USER,
                                            m.isFromUser
                                    )
                                    putLong(
                                            ChatServiceProtocol.EXTRA_MESSAGE_TIMESTAMP,
                                            m.timestamp
                                    )
                                }
                        )
                    }
                }
        val standardSnapshot =
                ArrayList<String>(manager.standardMessages.value.size).apply {
                    manager.standardMessages.value.forEach { add(it.toJsonString()) }
                }
        val bundle =
                Bundle().apply {
                    putParcelableArrayList(ChatServiceProtocol.EXTRA_MESSAGE_BUNDLE_LIST, snapshot)
                    putStringArrayList(
                            ChatServiceProtocol.EXTRA_STANDARD_MESSAGE_LIST,
                            standardSnapshot
                    )
                }
        if (target != null) {
            sendToClient(target, ChatServiceProtocol.MSG_EVENT_SNAPSHOT, bundle)
        } else {
            sendToClients(ChatServiceProtocol.MSG_EVENT_SNAPSHOT, bundle)
        }
    }

    private fun broadcastStandardMessage(message: MessageBase) {
        val json = message.toJsonString()
        val bundle =
                Bundle().apply { putString(ChatServiceProtocol.EXTRA_STANDARD_MESSAGE_JSON, json) }
        sendToClients(ChatServiceProtocol.MSG_EVENT_STANDARD_MESSAGE, bundle)
    }

    private fun sendToClients(what: Int, data: Bundle) {
        val toRemove = mutableListOf<Messenger>()
        clients.forEach { client ->
            if (!sendToClient(client, what, data)) {
                toRemove.add(client)
            }
        }
        if (toRemove.isNotEmpty()) {
            clients.removeAll(toRemove.toSet())
        }
    }

    private fun sendToClient(client: Messenger, what: Int, data: Bundle): Boolean =
            try {
                val msg = Message.obtain(null, what).apply { this.data = data }
                client.send(msg)
                true
            } catch (e: RemoteException) {
                logger.warn("Client callback failed, removing target", e)
                false
            }

    private fun ensureConnected(triggerReconnect: Boolean = true) {
        val state = manager.connectionState.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) return
        if (!triggerReconnect) return
        val url = lastKnownUrl
        if (url.isNullOrBlank()) {
            notifyError("未设置服务器地址，无法连接")
            return
        }
        logger.debug(
                "ensureConnected() with state=$state trigger=$triggerReconnect url=$url",
                throttleMs = 1_000L,
                throttleKey = "ensure_connected"
        )
        manager.connect(url, lastKnownPlatform, lastKnownAuth)
    }

    private fun handleSendMessage(data: Bundle) {
        val text = data.getString(ChatServiceProtocol.EXTRA_MESSAGE_TEXT)?.trim()
        if (text.isNullOrEmpty()) {
            notifyError("发送内容不能为空")
            return
        }
        ensureConnected()
        val turnId = if (callModeActive) newCallTurnId() else null
        manager.sendUserMessage(text, turnId)
        turnId?.let(::beginCallTurn)
    }

    private fun handleSendMedia(data: Bundle) {
        val type = data.getString(ChatServiceProtocol.EXTRA_MEDIA_TYPE)
        val path = data.getString(ChatServiceProtocol.EXTRA_MEDIA_FILE_PATH)
        val requestId = data.getString(ChatServiceProtocol.EXTRA_MEDIA_REQUEST_ID)
        if (type !in setOf("image", "voice") || path.isNullOrBlank()) {
            notifyError("媒体消息参数无效")
            return
        }
        val mediaType = requireNotNull(type)
        val mediaPath = path
        val callTurnId =
                if (mediaType == "voice" && callModeActive) newCallTurnId() else null
        if (manager.connectionState.value != ConnectionState.CONNECTED) {
            ensureConnected()
            File(mediaPath).delete()
            notifyError("尚未连接，无法发送媒体")
            return
        }

        serviceScope.launch {
            val source = File(mediaPath)
            try {
                val payload =
                        withContext(Dispatchers.IO) {
                            DeviceMediaPayloadEncoder.encode(
                                    applicationContext,
                                    mediaType,
                                    mediaPath
                            )
                        }
                when (mediaType) {
                    "image" -> manager.sendImageMessage(payload, requestId)
                    "voice" -> {
                        manager.sendVoiceMessage(payload, callTurnId)
                        callTurnId?.let(::beginCallTurn)
                    }
                }
            } catch (error: Throwable) {
                notifyError("发送${if (mediaType == "image") "照片" else "语音"}失败：${error.message ?: "未知错误"}")
            } finally {
                withContext(Dispatchers.IO) { source.delete() }
            }
        }
    }

    private fun handleSendCallTurn(data: Bundle) {
        val imagePath = data.getString(ChatServiceProtocol.EXTRA_CALL_IMAGE_FILE_PATH)
        val voicePath = data.getString(ChatServiceProtocol.EXTRA_CALL_VOICE_FILE_PATH)
        if (imagePath.isNullOrBlank() || voicePath.isNullOrBlank()) {
            notifyError("通话消息参数无效")
            return
        }
        val imageFile = File(imagePath)
        val voiceFile = File(voicePath)
        val callTurnId = newCallTurnId()
        if (manager.connectionState.value != ConnectionState.CONNECTED) {
            ensureConnected()
            imageFile.delete()
            voiceFile.delete()
            notifyError("尚未连接，无法发送通话消息")
            return
        }

        serviceScope.launch {
            try {
                val (imagePayload, voicePayload) =
                        withContext(Dispatchers.IO) {
                            DeviceMediaPayloadEncoder.encode(
                                    applicationContext,
                                    "image",
                                    imagePath
                            ) to
                                    DeviceMediaPayloadEncoder.encode(
                                            applicationContext,
                                            "voice",
                                            voicePath
                                    )
                        }
                manager.sendCallTurnMessage(imagePayload, voicePayload, callTurnId)
                beginCallTurn(callTurnId)
            } catch (error: Throwable) {
                notifyError("发送通话消息失败：${error.message ?: "未知错误"}")
            } finally {
                withContext(Dispatchers.IO) {
                    imageFile.delete()
                    voiceFile.delete()
                }
            }
        }
    }

    private fun handleSpeakerEnabled(data: Bundle) {
        speakerEnabled =
                data.getBoolean(ChatServiceProtocol.EXTRA_SPEAKER_ENABLED, speakerEnabled)
        getSharedPreferences(CHAT_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SPEAKER_ENABLED, speakerEnabled)
                .apply()
        if (!speakerEnabled) {
            audioPlayer.stop()
            systemTtsPlayer.stop()
            finishCallTurn()
        }
    }

    private fun handleCallMode(data: Bundle) {
        val active = data.getBoolean(ChatServiceProtocol.EXTRA_CALL_ACTIVE, false)
        val video = data.getBoolean(ChatServiceProtocol.EXTRA_CALL_VIDEO_ENABLED, false)
        callModeActive = active
        callVideoEnabled = active && video
        if (active) {
            if (isSpeaking) {
                updateCallState(CallRuntimePhase.SPEAKING, "正在回应")
            } else if (pendingTtsRequestId != null) {
                updateCallState(CallRuntimePhase.SYNTHESIZING, "正在生成回复语音")
            } else if (pendingTurnId != null) {
                updateCallState(CallRuntimePhase.THINKING, "正在思考")
            } else {
                updateCallState(CallRuntimePhase.LISTENING, "正在聆听")
            }
        } else {
            turnTimeoutJob?.cancel()
            ttsTimeoutJob?.cancel()
            botTextSettleJob?.cancel()
            pendingTurnId = null
            pendingTtsRequestId = null
            pendingReplyText = null
            pendingBotSpeech = ""
            pendingBotReplyMessageId = null
            updateCallState(CallRuntimePhase.IDLE, null)
        }
        broadcastCallState()
    }

    private fun newCallTurnId(): String = "turn-${UUID.randomUUID()}"

    private fun handleConnectRequest(data: Bundle) {
        val url =
                data.getString(ChatServiceProtocol.EXTRA_URL)?.takeIf { it.isNotBlank() }
                        ?: lastKnownUrl
        if (url.isNullOrBlank()) {
            notifyError("未提供有效的服务器地址")
            return
        }
        val platform =
                data.getString(ChatServiceProtocol.EXTRA_PLATFORM)?.takeUnless { it.isBlank() }
                        ?: lastKnownPlatform
        val auth =
                data.getString(ChatServiceProtocol.EXTRA_AUTH_TOKEN)?.takeUnless { it.isBlank() }
                        ?: lastKnownAuth

        logger.info(
                "handleConnectRequest url=$url platform=$platform " +
                        "authPresent=${auth != null} caller=${data.keySet()}"
        )

        lastKnownUrl = url
        lastKnownPlatform = platform
        lastKnownAuth = auth
        persistConnectionConfig()

        if (!platform.isNullOrBlank() || auth != null) {
            manager.setConnectionConfig(platform ?: manager.getPlatform(), auth)
        }
        logger.info("Invoking ChatWebSocketManager.connect url=$url platform=$platform")
        manager.connect(url, platform, auth)
    }

    private fun handleConfigUpdate(data: Bundle) {
        var needReconnect = false
        data.getString(ChatServiceProtocol.EXTRA_PLATFORM)?.let { platform ->
            val trimmed = platform.trim()
            lastKnownPlatform = trimmed.ifBlank { null }
            manager.updatePlatformPreference(lastKnownPlatform)
            needReconnect = true
        }
        data.getString(ChatServiceProtocol.EXTRA_AUTH_TOKEN)?.let { token ->
            lastKnownAuth = token.takeUnless { it.isBlank() }
            manager.setConnectionConfig(lastKnownPlatform ?: manager.getPlatform(), lastKnownAuth)
            needReconnect = true
        }
        data.getString(ChatServiceProtocol.EXTRA_NICKNAME)?.let { name ->
            lastKnownNickname = name
            if (name.isNotBlank()) {
                manager.setUserProfile(name)
            }
        }
        if (data.containsKey(ChatServiceProtocol.EXTRA_RECEIVER_ID) ||
                        data.containsKey(ChatServiceProtocol.EXTRA_RECEIVER_NICKNAME)
        ) {
            lastKnownReceiverId =
                    data.getString(ChatServiceProtocol.EXTRA_RECEIVER_ID)?.ifBlank { null }
            lastKnownReceiverNickname =
                    data.getString(ChatServiceProtocol.EXTRA_RECEIVER_NICKNAME)?.ifBlank { null }
            manager.setReceiverInfo(lastKnownReceiverId, lastKnownReceiverNickname)
        }
        data.getString(ChatServiceProtocol.EXTRA_URL)?.let { url ->
            val trimmed = url.trim()
            lastKnownUrl = trimmed.ifBlank { null }
            needReconnect = true
        }
        persistConnectionConfig()
        if (needReconnect) {
            ensureConnected(triggerReconnect = true)
        }
    }

    private fun handleClearMessages(persist: Boolean) {
        if (persist) manager.clearMessages() else manager.clearMessagesEphemeral()
        sendSnapshot()
    }

    private fun handleSetActiveModel(data: Bundle) {
        val modelName = data.getString(ChatServiceProtocol.EXTRA_MODEL_NAME)
        manager.setActiveModel(applicationContext, modelName)
        sendSnapshot()
    }

    private fun persistConnectionConfig() {
        val editor = getSharedPreferences(CHAT_PREFS, MODE_PRIVATE).edit()
        if (lastKnownUrl != null) editor.putString(KEY_LAST_URL, lastKnownUrl)
        else editor.remove(KEY_LAST_URL)
        if (lastKnownPlatform != null) editor.putString(KEY_PLATFORM, lastKnownPlatform)
        else editor.remove(KEY_PLATFORM)
        if (lastKnownAuth != null) editor.putString(KEY_AUTH_TOKEN, lastKnownAuth)
        else editor.remove(KEY_AUTH_TOKEN)
        if (!lastKnownNickname.isNullOrEmpty()) editor.putString(KEY_NICKNAME, lastKnownNickname)
        else editor.remove(KEY_NICKNAME)
        if (lastKnownReceiverId != null) editor.putString(KEY_RECEIVER_ID, lastKnownReceiverId)
        else editor.remove(KEY_RECEIVER_ID)
        if (lastKnownReceiverNickname != null)
                editor.putString(KEY_RECEIVER_NICKNAME, lastKnownReceiverNickname)
        else editor.remove(KEY_RECEIVER_NICKNAME)
        editor.apply()
    }

    private fun notifyError(message: String) {
        val data = Bundle().apply { putString(ChatServiceProtocol.EXTRA_ERROR_MESSAGE, message) }
        sendToClients(ChatServiceProtocol.MSG_EVENT_ERROR, data)
    }

    private class IncomingHandler(service: ChatConnectionService) :
            Handler(Looper.getMainLooper()) {
        private val serviceRef = WeakReference(service)

        override fun handleMessage(msg: Message) {
            val service = serviceRef.get() ?: return
            when (msg.what) {
                ChatServiceProtocol.MSG_REGISTER_CLIENT -> {
                    msg.replyTo?.let {
                        service.clients.add(it)
                        service.sendSnapshot(it)
                        service.broadcastConnectionState(service.manager.connectionState.value)
                        service.broadcastSpeakingState(it)
                        service.broadcastCallState(it)
                    }
                }
                ChatServiceProtocol.MSG_UNREGISTER_CLIENT -> {
                    msg.replyTo?.let { service.clients.remove(it) }
                }
                ChatServiceProtocol.MSG_CONNECT -> service.handleConnectRequest(msg.data)
                ChatServiceProtocol.MSG_DISCONNECT -> service.manager.disconnect()
                ChatServiceProtocol.MSG_SEND_MESSAGE -> service.handleSendMessage(msg.data)
                ChatServiceProtocol.MSG_UPDATE_CONFIG -> service.handleConfigUpdate(msg.data)
                ChatServiceProtocol.MSG_REQUEST_SNAPSHOT -> {
                    val target = msg.replyTo
                    if (target != null) service.sendSnapshot(target) else service.sendSnapshot()
                }
                ChatServiceProtocol.MSG_CLEAR_MESSAGES -> service.handleClearMessages(true)
                ChatServiceProtocol.MSG_CLEAR_MESSAGES_EPHEMERAL ->
                        service.handleClearMessages(false)
                ChatServiceProtocol.MSG_SET_ACTIVE_MODEL -> service.handleSetActiveModel(msg.data)
                ChatServiceProtocol.MSG_SEND_MEDIA -> service.handleSendMedia(msg.data)
                ChatServiceProtocol.MSG_SEND_CALL_TURN ->
                        service.handleSendCallTurn(msg.data)
                ChatServiceProtocol.MSG_SET_SPEAKER_ENABLED ->
                        service.handleSpeakerEnabled(msg.data)
                ChatServiceProtocol.MSG_SET_CALL_MODE -> service.handleCallMode(msg.data)
                else -> super.handleMessage(msg)
            }
        }
    }

    companion object {
        private const val CHAT_PREFS = "chat_prefs"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_RECEIVER_ID = "receiver_user_id"
        private const val KEY_RECEIVER_NICKNAME = "receiver_user_nickname"
        private const val KEY_SPEAKER_ENABLED = "speaker_enabled"
        private const val MAX_TTS_TEXT_CHARS = 240
        private const val LEGACY_VOICE_DEDUP_WINDOW_MS = 8_000L
        private const val REMOTE_TTS_TIMEOUT_MS = 45_000L
        private const val BOT_REPLY_TIMEOUT_MS = 90_000L
        private const val BOT_TEXT_SETTLE_MS = 2_800L
        private const val ERROR_STATE_HOLD_MS = 1_500L
        private const val SYSTEM_TTS_READY_RETRIES = 20
        private const val SYSTEM_TTS_RETRY_DELAY_MS = 100L
    }
}
