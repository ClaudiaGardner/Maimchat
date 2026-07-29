package com.l2dchat.chat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class DeviceRequest(
        val requestId: String,
        val type: Type,
        val camera: CameraFacing = CameraFacing.FRONT
) {
    enum class Type {
        CAMERA_SNAPSHOT
    }

    enum class CameraFacing {
        FRONT,
        BACK
    }
}

object DeviceRequestCodec {
    private val gson = Gson()

    fun parse(payload: String?): DeviceRequest? {
        if (payload.isNullOrBlank() || payload.length > MAX_PAYLOAD_CHARS) return null
        return runCatching {
                    val root = JsonParser.parseString(payload)
                    if (!root.isJsonObject) return null
                    val objectRoot = root.asJsonObject
                    val nested =
                            objectRoot.get("device_request")
                                    ?.takeIf { it.isJsonObject }
                                    ?.asJsonObject
                                    ?: objectRoot
                    parseObject(nested)
                }
                .getOrNull()
    }

    fun fromAdditionalConfig(config: Map<String, Any>?): DeviceRequest? {
        if (config.isNullOrEmpty()) return null
        val nested = config["device_request"] ?: return null
        val payload = if (nested is String) nested else gson.toJson(nested)
        return parse(payload)
    }

    fun toJson(request: DeviceRequest): String =
            gson.toJson(
                    mapOf(
                            "request_id" to request.requestId,
                            "type" to "camera_snapshot",
                            "camera" to request.camera.name.lowercase()
                    )
            )

    private fun parseObject(root: JsonObject): DeviceRequest? {
        val rawType =
                root.string("type")
                        ?: root.string("action")
                        ?: root.string("request")
                        ?: return null
        val type =
                when (rawType.lowercase()) {
                    "camera_snapshot", "request_image", "image_req", "snapshot" ->
                            DeviceRequest.Type.CAMERA_SNAPSHOT
                    else -> return null
                }
        val requestId =
                (root.string("request_id") ?: root.string("id"))
                        ?.trim()
                        ?.take(MAX_REQUEST_ID_CHARS)
                        ?.ifBlank { null }
                        ?: "camera_${System.currentTimeMillis()}"
        val camera =
                when (root.string("camera")?.lowercase()) {
                    "back", "rear", "environment" -> DeviceRequest.CameraFacing.BACK
                    else -> DeviceRequest.CameraFacing.FRONT
                }
        return DeviceRequest(requestId, type, camera)
    }

    private fun JsonObject.string(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            null
        }
    }

    private const val MAX_PAYLOAD_CHARS = 8 * 1024
    private const val MAX_REQUEST_ID_CHARS = 160
}
