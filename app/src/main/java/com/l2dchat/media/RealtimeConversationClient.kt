package com.l2dchat.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.l2dchat.chat.CallRuntimePhase
import com.l2dchat.chat.RealtimeSessionPayload
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Direct Android ↔ Qwen Omni Realtime audio transport.
 *
 * The permanent provider key never enters this class. [RealtimeSessionPayload] contains only a
 * short-lived credential minted by the MaiBot device plugin.
 */
class RealtimeConversationClient(
        context: Context,
        private val onPhaseChanged: (CallRuntimePhase, String?) -> Unit,
        private val onSpeakingChanged: (Boolean) -> Unit,
        private val onUserTranscript: (String) -> Unit,
        private val onAssistantTranscript: (String) -> Unit,
        private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val logger = L2DLogger.module(LogModule.CHAT)
    private val gson = Gson()
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .build()
    private val captureExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "maimchat-realtime-capture")
            }
    private val playbackExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "maimchat-realtime-playback")
            }
    private val playbackQueue = LinkedBlockingQueue<AudioChunk>()
    private val playbackGeneration = AtomicInteger(0)
    private val active = AtomicBoolean(false)
    private val mediaReady = AtomicBoolean(false)
    private val responding = AtomicBoolean(false)
    private val outputEnabled = AtomicBoolean(true)
    private val inputEnabled = AtomicBoolean(true)
    private val playbackLoopStarted = AtomicBoolean(false)
    private val lifecycleLock = Any()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var session: RealtimeSessionPayload? = null
    @Volatile private var intentionalClose = false
    private var audioRouteClaimed = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn = false

    fun start(payload: RealtimeSessionPayload): Result<Unit> =
            runCatching {
                require(payload.isSuccess) { payload.error ?: "实时会话授权无效" }
                check(
                        ContextCompat.checkSelfPermission(
                                appContext,
                                Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                ) {
                    "缺少麦克风权限"
                }
                synchronized(lifecycleLock) {
                    stopLocked()
                    intentionalClose = false
                    session = payload
                    active.set(true)
                    responding.set(false)
                    val separator =
                            if (requireNotNull(payload.websocketUrl).contains("?")) "&" else "?"
                    val url =
                            requireNotNull(payload.websocketUrl) +
                                    separator +
                                    "model=" +
                                    requireNotNull(payload.model)
                    val request =
                            Request.Builder()
                                    .url(url)
                                    .addHeader(
                                            "Authorization",
                                            "Bearer ${requireNotNull(payload.token)}"
                                    )
                                    .build()
                    onPhaseChanged(CallRuntimePhase.THINKING, "正在连接端到端模型")
                    webSocket = client.newWebSocket(request, listener)
                    logger.info("端到端实时连接已发起 model=${payload.model}")
                }
            }

    fun stop() {
        synchronized(lifecycleLock) {
            intentionalClose = true
            stopLocked()
        }
    }

    fun release() {
        stop()
        captureExecutor.shutdownNow()
        playbackExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun setOutputEnabled(enabled: Boolean) {
        outputEnabled.set(enabled)
        updateAudioRoute()
        if (!enabled) {
            clearOutput()
            onSpeakingChanged(false)
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnabled.set(enabled)
        if (!enabled) {
            onPhaseChanged(CallRuntimePhase.LISTENING, "麦克风已关闭")
        }
    }

    /** Send a JPEG keyframe. The caller controls capture frequency and image size. */
    fun appendImage(jpeg: ByteArray): Boolean {
        if (!active.get() || !mediaReady.get() || jpeg.isEmpty()) return false
        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return sendEvent("input_image_buffer.append", mapOf("image" to encoded))
    }

    private val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== this@RealtimeConversationClient.webSocket) return
                    logger.info("端到端实时 WebSocket 已连接")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== this@RealtimeConversationClient.webSocket) return
                    handleServerEvent(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== this@RealtimeConversationClient.webSocket) return
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== this@RealtimeConversationClient.webSocket) return
                    val expected = intentionalClose
                    stopMedia()
                    if (!expected) {
                        onError("端到端实时连接已关闭（$code）")
                    }
                }

                override fun onFailure(
                        webSocket: WebSocket,
                        throwable: Throwable,
                        response: Response?
                ) {
                    if (webSocket !== this@RealtimeConversationClient.webSocket) return
                    val expected = intentionalClose
                    stopMedia()
                    if (!expected) {
                        onError(
                                "端到端实时连接失败：${throwable.message ?: "网络错误"}" +
                                        (response?.code?.let { "（HTTP $it）" } ?: "")
                        )
                    }
                }
            }

    private fun handleServerEvent(text: String) {
        val event =
                runCatching { JsonParser.parseString(text).asJsonObject }
                        .getOrElse {
                            onError("端到端实时服务返回了无效事件")
                            return
                        }
        when (event.string("type")) {
            "session.created" -> sendSessionUpdate()
            "session.updated" -> {
                startMedia().onFailure { error ->
                    onError("无法启动实时音频：${error.message ?: "未知错误"}")
                }
            }
            "input_audio_buffer.speech_started" -> {
                if (responding.getAndSet(false)) {
                    sendEvent("response.cancel")
                }
                clearOutput()
                onSpeakingChanged(false)
                onPhaseChanged(CallRuntimePhase.LISTENING, "正在听你说话")
            }
            "input_audio_buffer.speech_stopped" ->
                    onPhaseChanged(CallRuntimePhase.THINKING, "正在理解")
            "response.created" -> {
                responding.set(true)
                onPhaseChanged(CallRuntimePhase.THINKING, "正在回应")
            }
            "response.audio.delta" -> {
                val encoded = event.string("delta")
                if (encoded.isNotBlank() && outputEnabled.get()) {
                    val audio =
                            runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                    audio?.takeIf { it.isNotEmpty() }?.let { decoded ->
                        playbackQueue.offer(
                                AudioChunk(decoded, playbackGeneration.get())
                        )
                        onSpeakingChanged(true)
                        onPhaseChanged(CallRuntimePhase.SPEAKING, "正在回应")
                    }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                event.string("transcript")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(onUserTranscript)
            }
            "response.audio_transcript.done" -> {
                event.string("transcript")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(onAssistantTranscript)
            }
            "response.done" -> {
                responding.set(false)
                onSpeakingChanged(false)
                onPhaseChanged(CallRuntimePhase.LISTENING, "正在聆听")
            }
            "error" -> {
                val errorObject = event.getAsJsonObject("error")
                val message =
                        errorObject?.string("message")
                                ?.ifBlank { null }
                                ?: event.string("message").ifBlank { "未知错误" }
                onError("端到端实时模型错误：$message")
            }
        }
    }

    private fun sendSessionUpdate() {
        val current = session ?: return
        sendEvent(
                "session.update",
                mapOf(
                        "session" to
                                mapOf(
                                        "modalities" to listOf("text", "audio"),
                                        "voice" to current.voice,
                                        "input_audio_format" to "pcm",
                                        "output_audio_format" to "pcm",
                                        "input_audio_transcription" to
                                                mapOf(
                                                        "model" to
                                                                "qwen3-asr-flash-realtime"
                                                ),
                                        "instructions" to current.instructions.orEmpty(),
                                        "turn_detection" to
                                                mapOf(
                                                        "type" to "semantic_vad",
                                                        "threshold" to 0.5,
                                                        "prefix_padding_ms" to 300,
                                                        "silence_duration_ms" to 800,
                                                        "create_response" to true,
                                                        "interrupt_response" to true
                                                )
                                )
                )
        )
    }

    @SuppressLint("MissingPermission")
    private fun startMedia(): Result<Unit> =
            runCatching {
                if (!active.get() || audioRecord != null) return@runCatching
                claimAudioRoute()
                val inputMin =
                        AudioRecord.getMinBufferSize(
                                INPUT_SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                        )
                check(inputMin > 0) { "设备不支持 16 kHz 单声道录音" }
                val recorder =
                        AudioRecord(
                                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                INPUT_SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(inputMin * 2, INPUT_CHUNK_BYTES * 2)
                        )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    "麦克风初始化失败"
                }
                audioRecord = recorder
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler =
                            AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                                enabled = true
                            }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor =
                            NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                                enabled = true
                            }
                }
                ensurePlaybackLoop()
                recorder.startRecording()
                captureExecutor.execute { captureLoop(recorder) }
                mediaReady.set(true)
                onPhaseChanged(CallRuntimePhase.LISTENING, "端到端 · 正在聆听")
            }

    private fun captureLoop(recorder: AudioRecord) {
        val buffer = ByteArray(INPUT_CHUNK_BYTES)
        while (active.get() && recorder === audioRecord) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0 && inputEnabled.get()) {
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val encoded = Base64.encodeToString(chunk, Base64.NO_WRAP)
                if (!sendEvent("input_audio_buffer.append", mapOf("audio" to encoded))) {
                    break
                }
            } else if (read < 0) {
                onError("实时麦克风读取失败（$read）")
                break
            }
        }
    }

    private fun ensurePlaybackLoop() {
        if (!playbackLoopStarted.compareAndSet(false, true)) return
        val outputMin =
                AudioTrack.getMinBufferSize(
                        OUTPUT_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
        check(outputMin > 0) { "设备不支持 24 kHz 单声道播放" }
        val track =
                AudioTrack.Builder()
                        .setAudioAttributes(
                                AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                        )
                        .setAudioFormat(
                                AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build()
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(maxOf(outputMin * 2, OUTPUT_SAMPLE_RATE))
                        .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            track.release()
            "扬声器初始化失败"
        }
        audioTrack = track
        track.play()
        playbackExecutor.execute {
            try {
                while (!Thread.currentThread().isInterrupted &&
                                active.get() &&
                                track === audioTrack
                ) {
                    val chunk =
                            playbackQueue.poll(200, TimeUnit.MILLISECONDS)
                                    ?: continue
                    if (!active.get() ||
                                    chunk.generation != playbackGeneration.get() ||
                                    !outputEnabled.get()
                    ) {
                        continue
                    }
                    track.write(chunk.data, 0, chunk.data.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun clearOutput() {
        playbackGeneration.incrementAndGet()
        playbackQueue.clear()
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            if (active.get() && outputEnabled.get()) audioTrack?.play()
        }
    }

    private fun sendEvent(type: String, fields: Map<String, Any?> = emptyMap()): Boolean {
        val socket = webSocket ?: return false
        val payload =
                linkedMapOf<String, Any?>(
                        "event_id" to "event_${UUID.randomUUID()}",
                        "type" to type
                )
        payload.putAll(fields)
        return socket.send(gson.toJson(payload))
    }

    private fun stopLocked() {
        active.set(false)
        mediaReady.set(false)
        responding.set(false)
        clearOutput()
        stopMedia()
        webSocket?.close(1000, "client stop")
        webSocket = null
        session = null
        onSpeakingChanged(false)
    }

    private fun stopMedia() {
        active.set(false)
        playbackGeneration.incrementAndGet()
        playbackQueue.clear()
        runCatching { audioRecord?.stop() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { audioRecord?.release() }
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioRecord = null
        audioTrack = null
        echoCanceler = null
        noiseSuppressor = null
        playbackLoopStarted.set(false)
        releaseAudioRoute()
    }

    private fun claimAudioRoute() {
        synchronized(lifecycleLock) {
            if (!audioRouteClaimed) {
                previousAudioMode = audioManager.mode
                @Suppress("DEPRECATION")
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                audioRouteClaimed = true
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            updateAudioRouteLocked()
        }
    }

    private fun updateAudioRoute() {
        synchronized(lifecycleLock) {
            if (!audioRouteClaimed) return
            updateAudioRouteLocked()
        }
    }

    private fun updateAudioRouteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (outputEnabled.get()) {
                val speaker =
                        audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = outputEnabled.get()
        }
    }

    private fun releaseAudioRoute() {
        synchronized(lifecycleLock) {
            if (!audioRouteClaimed) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
            }
            audioManager.mode = previousAudioMode
            audioRouteClaimed = false
        }
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name) ?: return ""
        return if (value.isJsonNull) "" else runCatching { value.asString }.getOrDefault("")
    }

    private data class AudioChunk(val data: ByteArray, val generation: Int)

    private companion object {
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        const val INPUT_CHUNK_BYTES = 3_200
    }
}
