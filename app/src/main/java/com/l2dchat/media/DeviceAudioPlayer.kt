package com.l2dchat.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plays MaiBot voice/voiceurl segments through Android's normal media output route. */
class DeviceAudioPlayer(
        context: Context,
        private val onPlaybackError: (String) -> Unit,
        private val onPlaybackStateChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var temporaryFile: File? = null
    private var isPlaying = false

    suspend fun play(payload: String): Result<Unit> {
        val source =
                if (payload.startsWith("http://") || payload.startsWith("https://")) {
                    AudioSource.Url(payload)
                } else {
                    withContext(Dispatchers.IO) {
                        val encoded =
                                if (payload.startsWith("data:", ignoreCase = true)) {
                                    payload.substringAfter(',', missingDelimiterValue = "")
                                } else {
                                    payload
                                }
                        require(encoded.isNotBlank()) { "收到的语音为空" }
                        require(encoded.length <= MAX_BASE64_CHARS) { "收到的语音文件过大" }
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        require(bytes.isNotEmpty()) { "收到的语音无法解码" }
                        val directory =
                                File(appContext.cacheDir, "received-audio").apply { mkdirs() }
                        val file = File(directory, "maibot_${System.nanoTime()}.wav")
                        file.writeBytes(bytes)
                        AudioSource.Local(file)
                    }
                }

        return withContext(Dispatchers.Main.immediate) {
            runCatching {
                stop()
                val mediaPlayer =
                        MediaPlayer().apply {
                            setAudioAttributes(
                                    AudioAttributes.Builder()
                                            .setUsage(
                                                    if (Build.VERSION.SDK_INT >=
                                                                    Build.VERSION_CODES.O
                                                    ) {
                                                        AudioAttributes.USAGE_ASSISTANT
                                                    } else {
                                                        AudioAttributes.USAGE_MEDIA
                                                    }
                                            )
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build()
                            )
                            when (source) {
                                is AudioSource.Local -> {
                                    temporaryFile = source.file
                                    setDataSource(source.file.path)
                                }
                                is AudioSource.Url -> setDataSource(source.url)
                            }
                            setOnCompletionListener { completed ->
                                updatePlaybackState(false)
                                completed.reset()
                                completed.release()
                                if (player === completed) player = null
                                deleteTemporaryFile()
                            }
                            setOnErrorListener { failed, what, extra ->
                                onPlaybackError("语音播放失败（$what/$extra）")
                                updatePlaybackState(false)
                                failed.reset()
                                failed.release()
                                if (player === failed) player = null
                                deleteTemporaryFile()
                                true
                            }
                            setOnPreparedListener {
                                it.start()
                                updatePlaybackState(true)
                            }
                            prepareAsync()
                        }
                player = mediaPlayer
            }.onFailure {
                updatePlaybackState(false)
                if (source is AudioSource.Local) source.file.delete()
            }
        }
    }

    fun stop() {
        updatePlaybackState(false)
        val current = player
        player = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        deleteTemporaryFile()
    }

    private fun deleteTemporaryFile() {
        temporaryFile?.delete()
        temporaryFile = null
    }

    private fun updatePlaybackState(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        onPlaybackStateChanged(playing)
    }

    private sealed interface AudioSource {
        data class Local(val file: File) : AudioSource
        data class Url(val url: String) : AudioSource
    }

    companion object {
        private const val MAX_BASE64_CHARS = 16 * 1024 * 1024
    }
}
