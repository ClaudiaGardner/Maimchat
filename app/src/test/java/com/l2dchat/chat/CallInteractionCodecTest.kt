package com.l2dchat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                            "platform": "android_device",
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

    @Test
    fun parsesShortLivedRealtimeSession() {
        val payload =
                CallInteractionCodec.parseRealtimeSession(
                        """
                        {
                          "version": 1,
                          "request_id": "realtime-12345678",
                          "token": "st-short-lived",
                          "expires_at": 2000000000,
                          "websocket_url": "wss://example.test/realtime",
                          "model": "qwen3.5-omni-flash-realtime",
                          "voice": "Tina",
                          "instructions": "你是实时角色"
                        }
                        """.trimIndent()
                )

        requireNotNull(payload)
        assertTrue(payload.isSuccess)
        assertEquals("st-short-lived", payload.token)
        assertEquals(2_000_000_000L, payload.expiresAt)
        assertEquals("qwen3.5-omni-flash-realtime", payload.model)
    }

    @Test
    fun parsesRealtimeAuthorizationFailure() {
        val payload =
                CallInteractionCodec.parseRealtimeSession(
                        """
                        {
                          "version": 1,
                          "request_id": "realtime-12345678",
                          "phase": "error",
                          "message": "model access denied"
                        }
                        """.trimIndent()
                )

        requireNotNull(payload)
        assertFalse(payload.isSuccess)
        assertEquals("model access denied", payload.error)
    }

    @Test
    fun realtimeCredentialIsRedactedBeforeHistoryPersistence() {
        val message =
                MessageBase.fromJsonString(
                        """
                        {
                          "message_info": {
                            "platform": "maimchat_android",
                            "message_id": "realtime-session-1",
                            "time": 1
                          },
                          "message_segment": {
                            "type": "realtime_session",
                            "data": "{\"request_id\":\"realtime-12345678\",\"token\":\"st-secret\"}"
                          }
                        }
                        """.trimIndent()
                )

        val persisted = message.redactedForHistory().toJsonString()
        assertFalse(persisted.contains("st-secret"))
        assertTrue(persisted.contains("media payload omitted"))
    }
}
