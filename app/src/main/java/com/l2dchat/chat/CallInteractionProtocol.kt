package com.l2dchat.chat

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser

/** Runtime phases shared by the Android UI and the background chat service. */
enum class CallRuntimePhase {
    IDLE,
    LISTENING,
    THINKING,
    SYNTHESIZING,
    SPEAKING,
    ERROR;

    companion object {
        fun fromWireName(value: String?): CallRuntimePhase =
                entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: IDLE
    }
}

data class CallAudioPayload(
        val requestId: String,
        val turnId: String?,
        val text: String,
        val audio: String,
        val mimeType: String
)

data class CallStatePayload(
        val requestId: String?,
        val turnId: String?,
        val phase: CallRuntimePhase,
        val message: String?
)

data class CallTtsRequest(
        val requestId: String,
        val turnId: String?,
        val replyMessageId: String,
        val text: String
)

object CallInteractionCodec {
    private val gson = Gson()

    fun parseAudio(raw: String): CallAudioPayload? =
            runCatching {
                        val json = JsonParser.parseString(raw).asJsonObject
                        val requestId = json.string("request_id").takeIf { it.isNotBlank() }
                                ?: return null
                        val audio = json.string("audio").takeIf { it.isNotBlank() } ?: return null
                        CallAudioPayload(
                                requestId = requestId,
                                turnId = json.string("turn_id").ifBlank { null },
                                text = json.string("text"),
                                audio = audio,
                                mimeType = json.string("mime_type").ifBlank { "audio/wav" }
                        )
                    }
                    .getOrNull()

    fun parseState(raw: String): CallStatePayload? =
            runCatching {
                        val json = JsonParser.parseString(raw).asJsonObject
                        CallStatePayload(
                                requestId = json.string("request_id").ifBlank { null },
                                turnId = json.string("turn_id").ifBlank { null },
                                phase = CallRuntimePhase.fromWireName(json.string("phase")),
                                message = json.string("message").ifBlank { null }
                        )
                    }
                    .getOrNull()

    fun encodeCommand(request: CallTtsRequest): String {
        val payload =
                linkedMapOf(
                        "version" to 1,
                        "client" to "maimchat_android",
                        "request_id" to request.requestId,
                        "turn_id" to request.turnId,
                        "reply_message_id" to request.replyMessageId,
                        "text" to request.text
                )
        val json = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(json, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "/maimchat call-tts $encoded"
    }

    private fun com.google.gson.JsonObject.string(name: String): String {
        val value = get(name) ?: return ""
        return if (value.isJsonNull) "" else runCatching { value.asString }.getOrDefault("")
    }
}
