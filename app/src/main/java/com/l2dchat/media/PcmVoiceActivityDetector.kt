package com.l2dchat.media

import kotlin.math.max
import kotlin.math.sqrt

data class VoiceActivityConfig(
        val sampleRate: Int = 16_000,
        val frameDurationMs: Int = 20,
        val activationRms: Float = 0.025f,
        val activationNoiseMultiplier: Float = 2.5f,
        val releaseNoiseMultiplier: Float = 1.6f,
        val speechStartMs: Int = 120,
        val speechEndSilenceMs: Int = 900,
        val minimumSpeechMs: Int = 300,
        val maximumSpeechMs: Int = 30_000
) {
    init {
        require(sampleRate > 0)
        require(frameDurationMs > 0)
        require(activationRms in 0f..1f)
        require(activationNoiseMultiplier >= 1f)
        require(releaseNoiseMultiplier >= 1f)
        require(speechStartMs >= frameDurationMs)
        require(speechEndSilenceMs >= frameDurationMs)
        require(minimumSpeechMs >= frameDurationMs)
        require(maximumSpeechMs >= minimumSpeechMs)
    }
}

/**
 * A small adaptive energy VAD for Android microphone frames.
 *
 * It deliberately does not perform speech recognition. Its only job is to decide when a complete
 * utterance starts and ends so the existing MaiBot voice-message path can handle the WAV.
 */
class PcmVoiceActivityDetector(
        private val config: VoiceActivityConfig = VoiceActivityConfig()
) {
    enum class Event {
        SPEECH_STARTED,
        SPEECH_ENDED,
        SPEECH_DISCARDED,
        MAXIMUM_DURATION
    }

    private val startFrameCount = framesFor(config.speechStartMs)
    private val endFrameCount = framesFor(config.speechEndSilenceMs)
    private val minimumSpeechFrameCount = framesFor(config.minimumSpeechMs)
    private val maximumSpeechFrameCount = framesFor(config.maximumSpeechMs)

    private var speaking = false
    private var pendingSpeechFrames = 0
    private var utteranceFrames = 0
    private var voicedFrames = 0
    private var silenceFrames = 0
    private var noiseFloor = INITIAL_NOISE_FLOOR

    val isSpeaking: Boolean
        get() = speaking

    var lastRms: Float = 0f
        private set

    fun process(samples: ShortArray, sampleCount: Int = samples.size): Event? {
        if (sampleCount <= 0) return null
        val boundedCount = sampleCount.coerceAtMost(samples.size)
        val rms = calculateRms(samples, boundedCount)
        lastRms = rms

        if (!speaking) {
            val activationThreshold =
                    max(config.activationRms, noiseFloor * config.activationNoiseMultiplier)
            if (rms >= activationThreshold) {
                pendingSpeechFrames++
                if (pendingSpeechFrames >= startFrameCount) {
                    speaking = true
                    utteranceFrames = pendingSpeechFrames
                    voicedFrames = pendingSpeechFrames
                    silenceFrames = 0
                    pendingSpeechFrames = 0
                    return Event.SPEECH_STARTED
                }
            } else {
                pendingSpeechFrames = 0
                updateNoiseFloor(rms)
            }
            return null
        }

        utteranceFrames++
        val releaseThreshold =
                max(config.activationRms * 0.55f, noiseFloor * config.releaseNoiseMultiplier)
        if (rms >= releaseThreshold) {
            voicedFrames++
            silenceFrames = 0
        } else {
            silenceFrames++
        }

        if (utteranceFrames >= maximumSpeechFrameCount) {
            resetUtterance()
            return Event.MAXIMUM_DURATION
        }
        if (silenceFrames >= endFrameCount) {
            val accepted = voicedFrames >= minimumSpeechFrameCount
            resetUtterance()
            return if (accepted) Event.SPEECH_ENDED else Event.SPEECH_DISCARDED
        }
        return null
    }

    fun reset() {
        speaking = false
        pendingSpeechFrames = 0
        utteranceFrames = 0
        voicedFrames = 0
        silenceFrames = 0
        lastRms = 0f
    }

    private fun resetUtterance() {
        speaking = false
        pendingSpeechFrames = 0
        utteranceFrames = 0
        voicedFrames = 0
        silenceFrames = 0
    }

    private fun updateNoiseFloor(rms: Float) {
        val bounded = rms.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        noiseFloor += (bounded - noiseFloor) * NOISE_EMA_ALPHA
    }

    private fun framesFor(durationMs: Int): Int =
            ((durationMs + config.frameDurationMs - 1) / config.frameDurationMs).coerceAtLeast(1)

    companion object {
        private const val INITIAL_NOISE_FLOOR = 0.005f
        private const val MIN_NOISE_FLOOR = 0.001f
        private const val MAX_NOISE_FLOOR = 0.08f
        private const val NOISE_EMA_ALPHA = 0.05f

        fun calculateRms(samples: ShortArray, sampleCount: Int = samples.size): Float {
            if (sampleCount <= 0) return 0f
            val count = sampleCount.coerceAtMost(samples.size)
            var sumSquares = 0.0
            for (index in 0 until count) {
                val normalized = samples[index] / 32768.0
                sumSquares += normalized * normalized
            }
            return sqrt(sumSquares / count).toFloat().coerceIn(0f, 1f)
        }
    }
}
