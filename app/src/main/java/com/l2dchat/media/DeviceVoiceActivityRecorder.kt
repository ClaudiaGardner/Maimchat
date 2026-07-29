package com.l2dchat.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Continuously monitors the device microphone and emits one WAV file per detected utterance.
 *
 * Recognition remains a MaiBot responsibility. The Android side only performs local turn
 * detection, includes a short pre-roll, and pauses while the avatar is speaking.
 */
class DeviceVoiceActivityRecorder(
        context: Context,
        private val onStateChanged: (State) -> Unit,
        private val onUtteranceReady: (File) -> Unit,
        private val onError: (String) -> Unit
) {
    enum class State {
        STOPPED,
        LISTENING,
        SPEAKING,
        PAUSED
    }

    private val appContext = context.applicationContext
    private val outputDirectory =
            File(appContext.cacheDir, "device-media").apply { mkdirs() }
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val state = AtomicReference(State.STOPPED)

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    @Synchronized
    fun start(): Result<Unit> =
            runCatching {
                if (running.get()) return@runCatching
                check(
                        ContextCompat.checkSelfPermission(
                                appContext,
                                Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                ) { "没有麦克风权限" }

                val minimumBuffer =
                        AudioRecord.getMinBufferSize(
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                        )
                check(minimumBuffer > 0) { "设备不支持 16 kHz 单声道录音" }
                val bufferBytes = maxOf(minimumBuffer * 2, FRAME_SAMPLE_COUNT * 4)
                val audioRecord =
                        AudioRecord.Builder()
                                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                                .setAudioFormat(
                                        AudioFormat.Builder()
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .setSampleRate(SAMPLE_RATE)
                                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                                .build()
                                )
                                .setBufferSizeInBytes(bufferBytes)
                                .build()
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }

                recorder = audioRecord
                paused.set(false)
                running.set(true)
                audioRecord.startRecording()
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "麦克风未能开始录音"
                }
                updateState(State.LISTENING)
                captureThread =
                        thread(name = "MaimchatVoiceActivity", isDaemon = true) {
                            captureLoop(audioRecord)
                        }
            }.onFailure {
                running.set(false)
                runCatching { recorder?.release() }
                recorder = null
                updateState(State.STOPPED)
            }

    fun setPaused(value: Boolean) {
        paused.set(value)
        if (running.get()) updateState(if (value) State.PAUSED else State.LISTENING)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        captureThread?.join(STOP_JOIN_TIMEOUT_MS)
        runCatching { recorder?.release() }
        recorder = null
        captureThread = null
        paused.set(false)
        updateState(State.STOPPED)
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val detector = PcmVoiceActivityDetector()
        val samples = ShortArray(FRAME_SAMPLE_COUNT)
        val preRoll = ArrayDeque<ByteArray>()
        var utterance: ByteArrayOutputStream? = null

        try {
            while (running.get()) {
                val read = audioRecord.read(samples, 0, samples.size)
                if (read == AudioRecord.ERROR_DEAD_OBJECT) error("麦克风连接已断开")
                if (read <= 0) continue

                if (paused.get()) {
                    detector.reset()
                    preRoll.clear()
                    utterance = null
                    continue
                }

                val frame = samplesToLittleEndianBytes(samples, read)
                when (detector.process(samples, read)) {
                    PcmVoiceActivityDetector.Event.SPEECH_STARTED -> {
                        utterance = ByteArrayOutputStream(MAX_UTTERANCE_BYTES)
                        preRoll.forEach { utterance?.write(it) }
                        preRoll.clear()
                        utterance?.write(frame)
                        updateState(State.SPEAKING)
                    }
                    PcmVoiceActivityDetector.Event.SPEECH_ENDED,
                    PcmVoiceActivityDetector.Event.MAXIMUM_DURATION -> {
                        utterance?.write(frame)
                        val pcm = utterance?.toByteArray()
                        utterance = null
                        preRoll.clear()
                        updateState(State.LISTENING)
                        if (pcm != null && pcm.size >= MINIMUM_UTTERANCE_BYTES) {
                            onUtteranceReady(writeWav(pcm))
                        }
                    }
                    PcmVoiceActivityDetector.Event.SPEECH_DISCARDED -> {
                        utterance = null
                        preRoll.clear()
                        updateState(State.LISTENING)
                    }
                    null -> {
                        if (detector.isSpeaking) {
                            utterance?.write(frame)
                        } else {
                            preRoll.addLast(frame)
                            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error.message ?: "自动收音失败")
        } finally {
            running.set(false)
            updateState(State.STOPPED)
        }
    }

    private fun writeWav(pcm: ByteArray): File {
        val file =
                File(outputDirectory, "automatic_voice_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { output ->
            output.write(ByteArray(WAV_HEADER_BYTES))
            output.write(pcm)
            output.flush()
        }
        updateWavHeader(file, pcm.size)
        return file
    }

    private fun updateWavHeader(file: File, dataSize: Int) {
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeLittleEndianInt(dataSize + 36)
            wav.writeBytes("WAVE")
            wav.writeBytes("fmt ")
            wav.writeLittleEndianInt(16)
            wav.writeLittleEndianShort(1)
            wav.writeLittleEndianShort(CHANNEL_COUNT)
            wav.writeLittleEndianInt(SAMPLE_RATE)
            wav.writeLittleEndianInt(byteRate)
            wav.writeLittleEndianShort(CHANNEL_COUNT * BITS_PER_SAMPLE / 8)
            wav.writeLittleEndianShort(BITS_PER_SAMPLE)
            wav.writeBytes("data")
            wav.writeLittleEndianInt(dataSize)
        }
    }

    private fun samplesToLittleEndianBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
        return bytes
    }

    private fun updateState(newState: State) {
        if (state.getAndSet(newState) != newState) onStateChanged(newState)
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(
                byteArrayOf(
                        value.toByte(),
                        (value ushr 8).toByte(),
                        (value ushr 16).toByte(),
                        (value ushr 24).toByte()
                )
        )
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_BYTES = 44
        private const val FRAME_DURATION_MS = 20
        private const val FRAME_SAMPLE_COUNT = SAMPLE_RATE * FRAME_DURATION_MS / 1_000
        private const val PRE_ROLL_MS = 300
        private const val PRE_ROLL_FRAMES = PRE_ROLL_MS / FRAME_DURATION_MS
        private const val MINIMUM_UTTERANCE_MS = 300
        private const val MINIMUM_UTTERANCE_BYTES =
                SAMPLE_RATE * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8) * MINIMUM_UTTERANCE_MS / 1_000
        private const val MAX_UTTERANCE_BYTES =
                SAMPLE_RATE * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8) * 31
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
