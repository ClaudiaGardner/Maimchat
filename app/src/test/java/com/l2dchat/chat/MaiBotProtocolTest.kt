package com.l2dchat.chat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotProtocolTest {
    @Test
    fun serializesMaimMessage068PrivateTextEnvelope() {
        val sender =
                UserInfo(
                        platform = "maimchat_android",
                        userId = "tablet-01",
                        userNickname = "访客"
                )
        val message =
                MessageBase(
                        messageInfo =
                                BaseMessageInfo(
                                        platform = "maimchat_android",
                                        messageId = "msg-1",
                                        time = 1_722_222_222.5,
                                        senderInfo = SenderInfo(userInfo = sender),
                                        userInfo = sender,
                                        formatInfo =
                                                FormatInfo(
                                                        contentFormat = listOf("text"),
                                                        acceptFormat =
                                                                listOf("text", "image", "emoji", "voice")
                                                ),
                                        additionalConfig = mapOf("message_type" to "chat")
                                ),
                        messageSegment = Seg("seglist", listOf(Seg("text", "你好"))),
                        rawMessage = "你好"
                )

        val json = JsonParser.parseString(message.toJsonString()).asJsonObject
        val info = json.getAsJsonObject("message_info")

        assertEquals("maimchat_android", info["platform"].asString)
        assertEquals("tablet-01", info.getAsJsonObject("user_info")["user_id"].asString)
        assertNull(info["group_info"])
        assertEquals(
                "你好",
                json.getAsJsonObject("message_segment")
                        .getAsJsonArray("data")[0]
                        .asJsonObject["data"]
                        .asString
        )
    }

    @Test
    fun parsesMaimMessage068ReplyEnvelope() {
        val message =
                MessageBase.fromJsonString(
                        """
                        {
                          "message_info": {
                            "platform": "maimchat_android",
                            "message_id": "reply-1",
                            "time": 1722222223.0,
                            "sender_info": {
                              "user_info": {
                                "platform": "maimchat_android",
                                "user_id": "maibot",
                                "user_nickname": "MaiBot"
                              }
                            },
                            "receiver_info": {
                              "user_info": {
                                "platform": "maimchat_android",
                                "user_id": "tablet-01",
                                "user_nickname": "访客"
                              }
                            }
                          },
                          "message_segment": {
                            "type": "seglist",
                            "data": [
                              {"type": "text", "data": "直连成功"}
                            ]
                          }
                        }
                        """.trimIndent()
                )

        assertEquals("reply-1", message.messageInfo.messageId)
        assertEquals("MaiBot", message.messageInfo.senderInfo?.userInfo?.userNickname)
        val segments = message.messageSegment.data as List<*>
        assertEquals("直连成功", (segments.single() as Seg).data)
    }

    @Test
    fun preservesStructuredAdditionalConfig() {
        val info =
                BaseMessageInfo(
                        additionalConfig =
                                mapOf(
                                        "motion" to
                                                mapOf(
                                                        "group" to "Happy",
                                                        "index" to 0,
                                                        "loop" to false
                                                )
                                )
                )

        val motion = info.toJson().getAsJsonObject("additional_config").getAsJsonObject("motion")
        assertEquals("Happy", motion["group"].asString)
        assertEquals(0, motion["index"].asInt)
    }

    @Test
    fun hidesMaiBotReplyControlSegmentFromChatBubble() {
        val message =
                MessageBase(
                        messageInfo =
                                BaseMessageInfo(
                                        platform = "maimchat_android",
                                        messageId = "reply-2",
                                        time = 1_722_222_224.0,
                                ),
                        messageSegment =
                                Seg(
                                        "seglist",
                                        listOf(
                                                Seg("reply", "msg-1"),
                                                Seg("text", "直连显示正常"),
                                        ),
                                ),
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(result is Live2DChatMessageHandler.ChatMessageResult.Success)
        assertEquals(
                "直连显示正常",
                (result as Live2DChatMessageHandler.ChatMessageResult.Success).message.content,
        )
    }

    @Test
    fun redactsBinaryMediaBeforePersistingHistory() {
        val message =
                MessageBase(
                        messageInfo = BaseMessageInfo(messageId = "media-1"),
                        messageSegment =
                                Seg(
                                        "seglist",
                                        listOf(
                                                Seg("text", "请看"),
                                                Seg("image", "a".repeat(100_000)),
                                                Seg("voice", "b".repeat(100_000))
                                        )
                                ),
                        rawMessage = "c".repeat(10_000)
                )

        val redacted = message.redactedForHistory()
        val segments = redacted.messageSegment.data as List<*>

        assertEquals("请看", (segments[0] as Seg).data)
        assertEquals("[media payload omitted]", (segments[1] as Seg).data)
        assertEquals("[media payload omitted]", (segments[2] as Seg).data)
        assertNull(redacted.rawMessage)
        assertTrue(redacted.toJsonString().length < 2_000)
    }

    @Test
    fun acceptsVoiceUrlAsPlayableVoice() {
        val message =
                MessageBase(
                        messageInfo = BaseMessageInfo(messageId = "voice-url-1"),
                        messageSegment = Seg("voiceurl", "https://example.test/reply.wav")
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(result is Live2DChatMessageHandler.ChatMessageResult.VoiceProcessed)
        assertEquals(
                "https://example.test/reply.wav",
                (result as Live2DChatMessageHandler.ChatMessageResult.VoiceProcessed).voiceData
        )
    }

    @Test
    fun displaysIncomingImageAsPlaceholderInsteadOfBase64() {
        val message =
                MessageBase(
                        messageInfo = BaseMessageInfo(messageId = "image-1"),
                        messageSegment = Seg("image", "a".repeat(100_000))
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(result is Live2DChatMessageHandler.ChatMessageResult.Success)
        assertEquals(
                "[图片]",
                (result as Live2DChatMessageHandler.ChatMessageResult.Success).message.content
        )
    }

    @Test
    fun parsesAmaidesuStyleAvatarIntentSegment() {
        val message =
                MessageBase(
                        messageInfo = BaseMessageInfo(messageId = "intent-1"),
                        messageSegment =
                                Seg(
                                        "seglist",
                                        listOf(
                                                Seg("text", "你好"),
                                                Seg(
                                                        "avatar_intent",
                                                        """
                                                        {
                                                          "speech": "你好",
                                                          "emotion": {
                                                            "name": "happy",
                                                            "intensity": 0.8
                                                          },
                                                          "action": {
                                                            "name": "android.wave",
                                                            "parameters": {
                                                              "group": "Wave",
                                                              "index": 1,
                                                              "loop": false
                                                            }
                                                          }
                                                        }
                                                        """.trimIndent()
                                                )
                                        )
                                )
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(result is Live2DChatMessageHandler.ChatMessageResult.Success)
        val intent =
                (result as Live2DChatMessageHandler.ChatMessageResult.Success).avatarIntent
        assertEquals("happy", intent?.emotion?.name)
        assertEquals(0.8f, intent?.emotion?.intensity)
        assertEquals("android.wave", intent?.action?.name)
        assertEquals("Wave", intent?.action?.parameters?.get("group"))
        assertEquals("1", intent?.action?.parameters?.get("index"))
    }

    @Test
    fun usesIntentSpeechWhenControlMessageHasNoTextSegment() {
        val message =
                MessageBase(
                        messageInfo = BaseMessageInfo(messageId = "intent-2"),
                        messageSegment =
                                Seg(
                                        "avatar_intent",
                                        """{"speech":"只有结构化消息","emotion":"neutral"}"""
                                )
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(result is Live2DChatMessageHandler.ChatMessageResult.Success)
        assertEquals(
                "只有结构化消息",
                (result as Live2DChatMessageHandler.ChatMessageResult.Success).message.content
        )
        assertEquals("neutral", result.avatarIntent?.emotion?.name)
    }

    @Test
    fun parsesAvatarIntentFromAdditionalConfig() {
        val message =
                MessageBase.fromJsonString(
                        """
                        {
                          "message_info": {
                            "message_id": "intent-3",
                            "additional_config": {
                              "avatar_intent": {
                                "emotion": {"name": "sad", "intensity": 0.4},
                                "action": {"name": "think", "parameters": {}}
                              }
                            }
                          },
                          "message_segment": {
                            "type": "text",
                            "data": "让我想想"
                          }
                        }
                        """.trimIndent()
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(result is Live2DChatMessageHandler.ChatMessageResult.Success)
        val intent =
                (result as Live2DChatMessageHandler.ChatMessageResult.Success).avatarIntent
        assertEquals("sad", intent?.emotion?.name)
        assertEquals("think", intent?.action?.name)
    }
}
