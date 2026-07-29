package com.l2dchat.media

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Android system TTS used only as a last-resort audible reply fallback. */
class DeviceTextToSpeechPlayer(
        context: Context,
        private val onPlaybackError: (String) -> Unit,
        private val onPlaybackStateChanged: (Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    @Volatile private var ready: Boolean = false

    init {
        engine =
                TextToSpeech(context.applicationContext) { status ->
                    // Some engines invoke this callback before the constructor assignment
                    // becomes visible. Posting guarantees `engine` has been assigned first.
                    mainHandler.post { initializeEngine(status) }
                }
    }

    private fun initializeEngine(status: Int) {
        val current = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            onPlaybackError("设备未安装或无法启动系统 TTS 引擎")
            return
        }
        val languageResult = current.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                        languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            current.language = Locale.getDefault()
        }
        current.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onPlaybackStateChanged(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        onPlaybackStateChanged(false)
                    }

                    @Deprecated("Deprecated by Android")
                    override fun onError(utteranceId: String?) {
                        onPlaybackError("系统语音播放失败")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onPlaybackError("系统语音播放失败（$errorCode）")
                    }
                }
        )
        ready = true
    }

    fun speak(text: String, utteranceId: String): Result<Unit> =
            runCatching {
                require(text.isNotBlank()) { "系统语音文本为空" }
                val current = engine ?: error("系统语音尚未初始化")
                check(ready) { "系统语音尚未就绪" }
                val result =
                        current.speak(
                                text,
                                TextToSpeech.QUEUE_FLUSH,
                                Bundle(),
                                utteranceId
                        )
                check(result == TextToSpeech.SUCCESS) { "系统语音播放请求失败" }
            }

    fun stop() {
        engine?.stop()
        onPlaybackStateChanged(false)
    }

    fun shutdown() {
        ready = false
        mainHandler.removeCallbacksAndMessages(null)
        engine?.stop()
        engine?.shutdown()
        engine = null
        onPlaybackStateChanged(false)
    }
}
