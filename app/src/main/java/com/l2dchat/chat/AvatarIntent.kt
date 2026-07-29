package com.l2dchat.chat

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A renderer-neutral avatar command.
 *
 * The shape intentionally matches Amaidesu's intent payload so MaiBot integrations can send the
 * same semantic command to desktop and Android renderers.
 */
data class AvatarIntent(
        val speech: String? = null,
        val emotion: AvatarEmotion? = null,
        val action: AvatarAction? = null
) {
    fun isEmpty(): Boolean = speech.isNullOrBlank() && emotion == null && action == null
}

data class AvatarEmotion(val name: String, val intensity: Float = 0.5f)

data class AvatarAction(
        val name: String,
        val parameters: Map<String, String> = emptyMap()
)

object AvatarIntentCodec {
    private val gson = Gson()

    fun parse(payload: String?): AvatarIntent? {
        if (payload.isNullOrBlank() || payload.length > MAX_PAYLOAD_CHARS) return null
        return runCatching {
                    val root = JsonParser.parseString(payload)
                    val objectRoot =
                            when {
                                root.isJsonObject -> root.asJsonObject
                                else -> return null
                            }
                    val nested =
                            objectRoot.get("avatar_intent")
                                    ?.takeIf { it.isJsonObject }
                                    ?.asJsonObject
                                    ?: objectRoot
                    parseObject(nested)
                }
                .getOrNull()
                ?.takeUnless { it.isEmpty() }
    }

    fun fromAdditionalConfig(config: Map<String, Any>?): AvatarIntent? {
        if (config.isNullOrEmpty()) return null
        val nested = config["avatar_intent"] ?: config["maimchat_control"]
        if (nested != null) {
            val nestedPayload =
                    when (nested) {
                        is String -> nested
                        else -> gson.toJson(nested)
                    }
            parse(nestedPayload)?.let { return it }
        }

        if (config.keys.none { it in INTENT_KEYS }) return null
        return parse(gson.toJson(config))
    }

    fun toJson(intent: AvatarIntent): String = gson.toJson(intent)

    private fun parseObject(root: JsonObject): AvatarIntent {
        val speech = stringValue(root.get("speech")) ?: stringValue(root.get("text"))
        val emotion = parseEmotion(root.get("emotion"))
        val action = parseAction(root.get("action"))
        return AvatarIntent(
                speech = speech?.trim()?.take(MAX_SPEECH_CHARS)?.ifBlank { null },
                emotion = emotion,
                action = action
        )
    }

    private fun parseEmotion(element: JsonElement?): AvatarEmotion? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val name = stringValue(element)?.sanitizeName() ?: return null
            return AvatarEmotion(name)
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val name =
                (stringValue(obj.get("name")) ?: stringValue(obj.get("emotion")))
                        ?.sanitizeName()
                        ?: return null
        val intensity =
                obj.get("intensity")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?.asFloat
                        ?.coerceIn(0f, 1f)
                        ?: 0.5f
        return AvatarEmotion(name, intensity)
    }

    private fun parseAction(element: JsonElement?): AvatarAction? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val name = stringValue(element)?.sanitizeName() ?: return null
            return AvatarAction(name)
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val name =
                (stringValue(obj.get("name"))
                                ?: stringValue(obj.get("action"))
                                ?: stringValue(obj.get("type")))
                        ?.sanitizeName()
                        ?: return null
        val parameters =
                obj.get("parameters")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.entrySet()
                        ?.take(MAX_PARAMETERS)
                        ?.associate { (key, value) ->
                            key.take(MAX_PARAMETER_KEY_CHARS) to
                                    parameterValue(value).take(MAX_PARAMETER_VALUE_CHARS)
                        }
                        .orEmpty()
        return AvatarAction(name, parameters)
    }

    private fun stringValue(element: JsonElement?): String? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isString -> primitive.asString
            primitive.isNumber || primitive.isBoolean -> primitive.toString()
            else -> null
        }
    }

    private fun parameterValue(element: JsonElement): String =
            if (element.isJsonPrimitive) {
                stringValue(element).orEmpty()
            } else {
                element.toString()
            }

    private fun String.sanitizeName(): String? =
            trim().take(MAX_NAME_CHARS).ifBlank { null }

    private val INTENT_KEYS = setOf("speech", "text", "emotion", "action")
    private const val MAX_PAYLOAD_CHARS = 32 * 1024
    private const val MAX_SPEECH_CHARS = 4_000
    private const val MAX_NAME_CHARS = 80
    private const val MAX_PARAMETERS = 24
    private const val MAX_PARAMETER_KEY_CHARS = 80
    private const val MAX_PARAMETER_VALUE_CHARS = 1_000
}
