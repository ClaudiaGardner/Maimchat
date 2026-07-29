package com.l2dchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmVoiceActivityDetectorTest {
    private val config =
            VoiceActivityConfig(
                    frameDurationMs = 20,
                    speechStartMs = 60,
                    speechEndSilenceMs = 100,
                    minimumSpeechMs = 100,
                    maximumSpeechMs = 1_000
            )

    @Test
    fun startsAfterConsecutiveSpeechAndEndsAfterSilence() {
        val detector = PcmVoiceActivityDetector(config)
        val silence = frame(0)
        val speech = frame(6_000)

        repeat(5) { assertNull(detector.process(silence)) }
        assertNull(detector.process(speech))
        assertNull(detector.process(speech))
        assertEquals(PcmVoiceActivityDetector.Event.SPEECH_STARTED, detector.process(speech))
        assertTrue(detector.isSpeaking)

        repeat(3) { assertNull(detector.process(speech)) }
        repeat(4) { assertNull(detector.process(silence)) }
        assertEquals(PcmVoiceActivityDetector.Event.SPEECH_ENDED, detector.process(silence))
        assertFalse(detector.isSpeaking)
    }

    @Test
    fun ignoresShortNoiseBurst() {
        val detector = PcmVoiceActivityDetector(config)
        val silence = frame(0)
        val loud = frame(9_000)

        assertNull(detector.process(loud))
        assertNull(detector.process(loud))
        assertNull(detector.process(silence))
        assertFalse(detector.isSpeaking)
    }

    @Test
    fun discardsUtteranceWithoutEnoughVoicedFrames() {
        val detector =
                PcmVoiceActivityDetector(
                        config.copy(
                                speechStartMs = 40,
                                minimumSpeechMs = 200,
                                speechEndSilenceMs = 60
                        )
                )
        val speech = frame(7_000)
        val silence = frame(0)

        assertNull(detector.process(speech))
        assertEquals(PcmVoiceActivityDetector.Event.SPEECH_STARTED, detector.process(speech))
        repeat(2) { assertNull(detector.process(speech)) }
        repeat(2) { assertNull(detector.process(silence)) }
        assertEquals(PcmVoiceActivityDetector.Event.SPEECH_DISCARDED, detector.process(silence))
    }

    @Test
    fun reportsMaximumDurationForContinuousSpeech() {
        val detector =
                PcmVoiceActivityDetector(
                        config.copy(
                                speechStartMs = 40,
                                minimumSpeechMs = 40,
                                maximumSpeechMs = 120
                        )
                )
        val speech = frame(8_000)

        assertNull(detector.process(speech))
        assertEquals(PcmVoiceActivityDetector.Event.SPEECH_STARTED, detector.process(speech))
        var event: PcmVoiceActivityDetector.Event? = null
        repeat(10) {
            if (event == null) event = detector.process(speech)
        }
        assertEquals(PcmVoiceActivityDetector.Event.MAXIMUM_DURATION, event)
        assertFalse(detector.isSpeaking)
    }

    @Test
    fun calculatesNormalizedRms() {
        assertEquals(0f, PcmVoiceActivityDetector.calculateRms(frame(0)), 0.0001f)
        assertEquals(
                0.5f,
                PcmVoiceActivityDetector.calculateRms(frame(16_384)),
                0.0001f
        )
    }

    private fun frame(value: Int): ShortArray = ShortArray(320) { value.toShort() }
}
