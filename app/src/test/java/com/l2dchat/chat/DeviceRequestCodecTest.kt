package com.l2dchat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRequestCodecTest {
    @Test
    fun parsesAmaidesuStyleImageRequest() {
        val request =
                DeviceRequestCodec.parse(
                        """
                        {
                          "request_id": "vision-42",
                          "action": "request_image",
                          "camera": "environment"
                        }
                        """.trimIndent()
                )

        assertEquals("vision-42", request?.requestId)
        assertEquals(DeviceRequest.Type.CAMERA_SNAPSHOT, request?.type)
        assertEquals(DeviceRequest.CameraFacing.BACK, request?.camera)
    }

    @Test
    fun roundTripsBinderPayload() {
        val original =
                DeviceRequest(
                        requestId = "front-1",
                        type = DeviceRequest.Type.CAMERA_SNAPSHOT,
                        camera = DeviceRequest.CameraFacing.FRONT
                )

        assertEquals(original, DeviceRequestCodec.parse(DeviceRequestCodec.toJson(original)))
    }

    @Test
    fun rejectsUnsupportedDeviceCommand() {
        assertNull(
                DeviceRequestCodec.parse(
                        """{"request_id":"unsafe","action":"delete_file"}"""
                )
        )
    }

    @Test
    fun handlerTreatsDeviceRequestAsControlMessage() {
        val message =
                MessageBase(
                        messageInfo = BaseMessageInfo(messageId = "device-1"),
                        messageSegment =
                                Seg(
                                        "device_request",
                                        """
                                        {
                                          "request_id": "camera-1",
                                          "type": "camera_snapshot",
                                          "camera": "front"
                                        }
                                        """.trimIndent()
                                )
                )

        val result = Live2DChatMessageHandler().handleStandardMessage(message)

        assertTrue(
                result is Live2DChatMessageHandler.ChatMessageResult.DeviceRequestProcessed
        )
        assertEquals(
                "camera-1",
                (result as
                                Live2DChatMessageHandler.ChatMessageResult
                                        .DeviceRequestProcessed)
                        .deviceRequest
                        .requestId
        )
    }
}
