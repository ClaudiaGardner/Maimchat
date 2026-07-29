from __future__ import annotations

import json
import logging
import re
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


PLUGIN_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PLUGIN_DIR))

import plugin  # noqa: E402


class FakeSend:
    def __init__(self, result: bool = True) -> None:
        self.result = result
        self.calls: list[tuple[str, str, str]] = []

    async def custom(self, custom_type: str, data: str, stream_id: str) -> bool:
        self.calls.append((custom_type, data, stream_id))
        return self.result


def configured_plugin(*, camera_enabled: bool = True) -> tuple[plugin.MaimchatDevicePlugin, FakeSend]:
    instance = plugin.create_plugin()
    instance.set_plugin_config(
        {
            "plugin": {
                "enabled": True,
                "config_version": "1.0.0",
            },
            "features": {
                "camera_requests_enabled": camera_enabled,
            }
        }
    )
    sender = FakeSend()
    instance._set_context(
        SimpleNamespace(
            send=sender,
            logger=logging.getLogger("test.maimchat_plugin"),
        )
    )
    return instance, sender


class ProtocolBuilderTests(unittest.TestCase):
    def test_avatar_payload_is_renderer_neutral_and_clamped(self) -> None:
        payload = plugin.build_avatar_payload(
            emotion=" happy ",
            emotion_intensity=4,
            action="wave",
            expression="Smile",
            motion_group="Greeting",
            motion_index=-9,
            loop=True,
        )

        self.assertEqual(payload["emotion"], {"name": "happy", "intensity": 1.0})
        self.assertEqual(
            payload["action"],
            {
                "name": "wave",
                "parameters": {
                    "expression": "Smile",
                    "group": "Greeting",
                    "index": 0,
                    "loop": True,
                },
            },
        )

    def test_empty_avatar_payload_stays_empty(self) -> None:
        self.assertEqual(plugin.build_avatar_payload(emotion="", action=""), {})

    def test_explicit_expression_uses_generic_motion_action(self) -> None:
        payload = plugin.build_avatar_payload(expression="Blush")
        self.assertEqual(payload["action"]["name"], "motion")
        self.assertEqual(payload["action"]["parameters"], {"expression": "Blush"})

    def test_camera_request_has_correlation_id_and_safe_facing(self) -> None:
        payload = plugin.build_device_request_payload(camera="unsupported")
        self.assertEqual(payload["type"], "camera_snapshot")
        self.assertEqual(payload["camera"], "front")
        self.assertRegex(payload["request_id"], re.compile(r"^maimchat-[0-9a-f]{32}$"))


class PluginToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_avatar_tool_sends_custom_segment_to_current_stream(self) -> None:
        instance, sender = configured_plugin()

        result = await instance.handle_avatar_intent(
            emotion="thinking",
            action="nod",
            stream_id="stream-1",
        )

        self.assertTrue(result["success"])
        self.assertEqual(sender.calls[0][0], "avatar_intent")
        self.assertEqual(sender.calls[0][2], "stream-1")
        self.assertEqual(json.loads(sender.calls[0][1])["action"]["name"], "nod")

    async def test_camera_tool_sends_one_correlated_request(self) -> None:
        instance, sender = configured_plugin()

        result = await instance.handle_camera_snapshot(camera="back", stream_id="stream-2")

        self.assertTrue(result["success"])
        self.assertEqual(len(sender.calls), 1)
        custom_type, data, stream_id = sender.calls[0]
        payload = json.loads(data)
        self.assertEqual(custom_type, "device_request")
        self.assertEqual(stream_id, "stream-2")
        self.assertEqual(payload["camera"], "back")
        self.assertEqual(payload["request_id"], result["request_id"])

    async def test_camera_tool_respects_server_side_switch(self) -> None:
        instance, sender = configured_plugin(camera_enabled=False)

        result = await instance.handle_camera_snapshot(camera="front", stream_id="stream-3")

        self.assertFalse(result["success"])
        self.assertEqual(sender.calls, [])

    async def test_missing_stream_never_sends(self) -> None:
        instance, sender = configured_plugin()

        result = await instance.handle_avatar_intent(emotion="happy")

        self.assertFalse(result["success"])
        self.assertEqual(sender.calls, [])


if __name__ == "__main__":
    unittest.main()
