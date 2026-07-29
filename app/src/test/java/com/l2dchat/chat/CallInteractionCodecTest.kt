package com.l2dchat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallInteractionCodecTest {
    @Test
    fun parsesCorrelatedCallAudio() {
        val payload =
                CallInteractionCodec.parseAudio(
                        """
                        {
                          "request_id": "tts-12345678",
                          "turn_id": "turn-12345678",
                          "text": "你好呀",
                          "mime_type": "audio/wav",
                          "audio": "UklGRg=="
                        }
                        """.trimIndent()
                )

        requireNotNull(payload)
        assertEquals("tts-12345678", payload.requestId)
        assertEquals("turn-12345678", payload.turnId)
        assertEquals("你好呀", payload.text)
        assertEquals("UklGRg==", payload.audio)
    }

    @Test
    fun rejectsCallAudioWithoutCorrelationId() {
        assertNull(
                CallInteractionCodec.parseAudio(
                        """{"text":"你好","audio":"UklGRg=="}"""
                )
        )
    }

    @Test
    fun parsesRemoteFailureForLocalFallback() {
        val payload =
                CallInteractionCodec.parseState(
                        """
                        {
                          "request_id": "tts-12345678",
                          "turn_id": "turn-12345678",
                          "phase": "error",
                          "message": "tts unavailable"
                        }
                        """.trimIndent()
                )

        requireNotNull(payload)
        assertEquals(CallRuntimePhase.ERROR, payload.phase)
        assertEquals("tts unavailable", payload.message)
    }

    @Test
    fun messageParserAcceptsObjectValuedCustomSegmentsFromMaiBot() {
        val message =
                MessageBase.fromJsonString(
                        """
                        {
                          "message_info": {
                            "platform": "kaisy_android",
                            "message_id": "call-state-1",
                            "time": 1
                          },
                          "message_segment": {
                            "type": "seglist",
                            "data": [
                              {
                                "type": "dict",
                                "data": {
                                  "type": "call_state",
                                  "data": "{\"request_id\":\"tts-12345678\",\"phase\":\"error\",\"message\":\"tts unavailable\"}"
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)
        require(result is Live2DChatMessageHandler.ChatMessageResult.CallStateProcessed)
        assertEquals("tts-12345678", result.payload.requestId)
        assertEquals(CallRuntimePhase.ERROR, result.payload.phase)
    }
}
