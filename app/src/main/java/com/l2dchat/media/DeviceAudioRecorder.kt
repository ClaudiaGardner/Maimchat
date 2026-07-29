package com.l2dchat.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import androidx.core.content.ContextCompat

/**
 * Records microphone input as 16 kHz, mono, 16-bit PCM WAV, which is the voice format accepted by
 * MaiBot. A recording is capped so an accidentally forgotten session cannot grow without bound.
 */
class DeviceAudioRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val mediaDirectory =
            File(appContext.cacheDir, "device-media").apply { mkdirs() }
    private val recording = AtomicBoolean(false)

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var writerFailure: Throwable? = null

    val isRecording: Boolean
        get() = recording.get()

    @Synchronized
    fun start(): Result<Unit> =
            runCatching {
                check(!recording.get() && audioRecord == null) { "麦克风已经在录音" }
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
                val bufferSize = maxOf(minimumBuffer * 2, SAMPLE_RATE)
                val recorder =
                        AudioRecord.Builder()
                                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                                .setAudioFormat(
                                        AudioFormat.Builder()
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .setSampleRate(SAMPLE_RATE)
                                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                                .build()
                                )
                                .setBufferSizeInBytes(bufferSize)
                                .build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }

                val file = File(mediaDirectory, "microphone_${System.currentTimeMillis()}.wav")
                writerFailure = null
                outputFile = file
                audioRecord = recorder
                recording.set(true)
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "麦克风未能开始录音"
                }

                writerThread =
                        thread(name = "MaimchatMicrophone", isDaemon = true) {
                            writeRecording(recorder, file, bufferSize)
                        }
            }.onFailure {
                recording.set(false)
                runCatching { audioRecord?.release() }
                audioRecord = null
                outputFile?.delete()
                outputFile = null
            }

    @Synchronized
    fun stop(): Result<File> =
            runCatching {
                check(audioRecord != null && outputFile != null) { "当前没有录音" }
                recording.set(false)
                val recorder = audioRecord
                runCatching { recorder?.stop() }
                val writer = writerThread
                writer?.join(STOP_JOIN_TIMEOUT_MS)
                check(writer?.isAlive != true) { "麦克风停止超时" }
                runCatching { recorder?.release() }
                audioRecord = null
                writerThread = null

                writerFailure?.let { throw IllegalStateException("录音写入失败", it) }
                val file = requireNotNull(outputFile)
                check(file.length() > WAV_HEADER_BYTES) { "没有录到有效声音" }
                updateWavHeader(file)
                outputFile = null
                file
            }.onFailure {
                recording.set(false)
                runCatching { audioRecord?.release() }
                outputFile?.delete()
                outputFile = null
                audioRecord = null
                writerThread = null
            }

    @Synchronized
    fun cancel() {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        writerThread?.join(STOP_JOIN_TIMEOUT_MS)
        runCatching { audioRecord?.release() }
        audioRecord = null
        writerThread = null
        outputFile?.delete()
        outputFile = null
        writerFailure = null
    }

    private fun writeRecording(recorder: AudioRecord, file: File, bufferSize: Int) {
        val startedAt = System.currentTimeMillis()
        try {
            FileOutputStream(file).use { output ->
                output.write(ByteArray(WAV_HEADER_BYTES))
                val buffer = ByteArray(bufferSize)
                while (recording.get() &&
                                System.currentTimeMillis() - startedAt < MAX_RECORDING_MS
                ) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                    } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        error("麦克风连接已断开")
                    }
                }
                output.flush()
            }
        } catch (error: Throwable) {
            if (recording.get()) writerFailure = error
        } finally {
            recording.set(false)
        }
    }

    private fun updateWavHeader(file: File) {
        val dataSize = file.length() - WAV_HEADER_BYTES
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeLittleEndianInt((dataSize + 36).toInt())
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
            wav.writeLittleEndianInt(dataSize.toInt())
        }
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
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_BYTES = 44
        private const val MAX_RECORDING_MS = 60_000L
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
