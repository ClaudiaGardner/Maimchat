"""MaiBot 1.x plugin for controlling a Maimchat Android device.

The plugin deliberately sends renderer-neutral semantic messages. The Android
client decides which expression or motion exists in the currently selected
Live2D model, so this plugin does not depend on model assets or Unity.
"""

import json
from typing import Any
from uuid import uuid4

from maibot_sdk import Command, Field, MaiBotPlugin, PluginConfigBase, Tool
from maibot_sdk.types import ToolParameterInfo, ToolParamType


EMOTIONS = [
    "neutral",
    "happy",
    "sad",
    "angry",
    "surprised",
    "shy",
    "love",
    "excited",
    "confused",
    "scared",
    "thinking",
    "relaxed",
]


class PluginSectionConfig(PluginConfigBase):
    """Base plugin settings."""

    __ui_label__ = "插件"
    __ui_icon__ = "smartphone"
    __ui_order__ = 0

    enabled: bool = Field(default=True, description="是否启用 Maimchat 设备控制")
    config_version: str = Field(default="1.0.0", description="配置版本")


class FeatureConfig(PluginConfigBase):
    """Feature switches."""

    __ui_label__ = "设备能力"
    __ui_icon__ = "camera"
    __ui_order__ = 1

    camera_requests_enabled: bool = Field(
        default=True,
        description="允许工具向已在 Android 端授权的设备请求单次摄像头快照",
    )
    default_emotion_intensity: float = Field(
        default=0.7,
        ge=0.0,
        le=1.0,
        description="LLM 未指定时使用的表情强度，范围 0 到 1",
    )


class MaimchatDevicePluginConfig(PluginConfigBase):
    """Maimchat device plugin configuration."""

    plugin: PluginSectionConfig = Field(default_factory=PluginSectionConfig)
    features: FeatureConfig = Field(default_factory=FeatureConfig)


def _clean_name(value: Any, *, limit: int = 80) -> str:
    return str(value or "").strip()[:limit]


def _clamp_float(value: Any, default: float, minimum: float, maximum: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))


def _clamp_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))


def build_avatar_payload(
    *,
    emotion: Any = "",
    emotion_intensity: Any = 0.7,
    action: Any = "",
    expression: Any = "",
    motion_group: Any = "",
    motion_index: Any = 0,
    loop: Any = False,
) -> dict[str, Any]:
    """Build the renderer-neutral avatar intent understood by Maimchat."""

    emotion_name = _clean_name(emotion)
    action_name = _clean_name(action)
    expression_name = _clean_name(expression)
    motion_group_name = _clean_name(motion_group)

    payload: dict[str, Any] = {}
    if emotion_name:
        payload["emotion"] = {
            "name": emotion_name,
            "intensity": _clamp_float(emotion_intensity, 0.7, 0.0, 1.0),
        }

    action_parameters: dict[str, Any] = {}
    if expression_name:
        action_parameters["expression"] = expression_name
    if motion_group_name:
        action_parameters["group"] = motion_group_name
    if motion_group_name or action_name:
        action_parameters["index"] = _clamp_int(motion_index, 0, 0, 999)
        action_parameters["loop"] = bool(loop)

    if action_name or action_parameters:
        payload["action"] = {
            "name": action_name or "motion",
            "parameters": action_parameters,
        }
    return payload


def build_device_request_payload(*, camera: Any = "front", request_id: Any = "") -> dict[str, str]:
    """Build a correlated, one-shot camera request."""

    normalized_camera = _clean_name(camera).lower()
    if normalized_camera not in {"front", "back"}:
        normalized_camera = "front"
    normalized_request_id = _clean_name(request_id, limit=160)
    return {
        "request_id": normalized_request_id or f"maimchat-{uuid4().hex}",
        "type": "camera_snapshot",
        "camera": normalized_camera,
    }


class MaimchatDevicePlugin(MaiBotPlugin):
    """Expose Maimchat avatar and device capabilities to MaiBot's planner."""

    config_model = MaimchatDevicePluginConfig

    async def on_load(self) -> None:
        self.ctx.logger.info("Maimchat 设备插件已加载")

    async def on_unload(self) -> None:
        self.ctx.logger.info("Maimchat 设备插件已卸载")

    async def on_config_update(self, scope: str, config_data: dict[str, Any], version: str) -> None:
        del config_data
        if scope == "self":
            self.ctx.logger.info("Maimchat 设备插件配置已更新: version=%s", version)

    @Tool(
        "maimchat_avatar_intent",
        brief_description="让当前 Maimchat Android 形象做出表情或动作",
        detailed_description=(
            "只用于驱动当前聊天设备上的 Live2D 形象，不代替正常文本回复。"
            "emotion 使用 neutral/happy/sad/angry/surprised/shy/love/excited/"
            "confused/scared/thinking/relaxed；action 常用 wave/nod/shake/dance/"
            "idle/sleepy。若知道模型资源名，可用 expression 或 motion_group 精确指定。"
        ),
        parameters=[
            ToolParameterInfo(
                name="emotion",
                param_type=ToolParamType.STRING,
                description="可选的语义情绪",
                required=False,
                enum_values=EMOTIONS,
                default="neutral",
            ),
            ToolParameterInfo(
                name="emotion_intensity",
                param_type=ToolParamType.NUMBER,
                description="表情强度，0 到 1",
                required=False,
                default=0.7,
            ),
            ToolParameterInfo(
                name="action",
                param_type=ToolParamType.STRING,
                description="可选的语义动作，例如 wave、nod、shake、dance、idle",
                required=False,
                default="",
            ),
            ToolParameterInfo(
                name="expression",
                param_type=ToolParamType.STRING,
                description="可选的 Live2D 表情资源名；不知道时留空",
                required=False,
                default="",
            ),
            ToolParameterInfo(
                name="motion_group",
                param_type=ToolParamType.STRING,
                description="可选的 Live2D 动作组资源名；不知道时留空",
                required=False,
                default="",
            ),
            ToolParameterInfo(
                name="motion_index",
                param_type=ToolParamType.INTEGER,
                description="动作组内索引，从 0 开始",
                required=False,
                default=0,
            ),
            ToolParameterInfo(
                name="loop",
                param_type=ToolParamType.BOOLEAN,
                description="动作是否循环，通常保持 false",
                required=False,
                default=False,
            ),
        ],
    )
    async def handle_avatar_intent(
        self,
        emotion: str = "",
        emotion_intensity: float | None = None,
        action: str = "",
        expression: str = "",
        motion_group: str = "",
        motion_index: int = 0,
        loop: bool = False,
        stream_id: str = "",
        **kwargs: Any,
    ) -> dict[str, Any]:
        del kwargs
        if not self.config.plugin.enabled:
            return {"success": False, "content": "Maimchat 设备插件已禁用"}
        if not stream_id:
            return {"success": False, "content": "缺少当前聊天流，无法定位 Maimchat 设备"}

        default_intensity = self.config.features.default_emotion_intensity
        payload = build_avatar_payload(
            emotion=emotion,
            emotion_intensity=default_intensity if emotion_intensity is None else emotion_intensity,
            action=action,
            expression=expression,
            motion_group=motion_group,
            motion_index=motion_index,
            loop=loop,
        )
        if not payload:
            return {"success": False, "content": "没有可发送的表情或动作"}

        sent = await self.ctx.send.custom(
            "avatar_intent",
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            stream_id,
        )
        return {
            "success": bool(sent),
            "content": "已驱动 Maimchat 形象" if sent else "Maimchat 形象指令发送失败",
            "intent": payload,
        }

    @Tool(
        "maimchat_camera_snapshot",
        brief_description="请求当前 Maimchat Android 设备拍摄一张照片供视觉观察",
        detailed_description=(
            "仅在确实需要观察设备现场时调用。每次调用只请求一张前置或后置摄像头照片；"
            "Android 端必须由用户提前明确开启授权。照片会作为带 request_id 的新图片消息返回。"
        ),
        parameters=[
            ToolParameterInfo(
                name="camera",
                param_type=ToolParamType.STRING,
                description="front 为前置摄像头，back 为后置摄像头",
                required=False,
                enum_values=["front", "back"],
                default="front",
            ),
        ],
    )
    async def handle_camera_snapshot(
        self,
        camera: str = "front",
        stream_id: str = "",
        **kwargs: Any,
    ) -> dict[str, Any]:
        del kwargs
        if not self.config.plugin.enabled:
            return {"success": False, "content": "Maimchat 设备插件已禁用"}
        if not self.config.features.camera_requests_enabled:
            return {"success": False, "content": "MaiBot 侧的按需拍照功能已禁用"}
        if not stream_id:
            return {"success": False, "content": "缺少当前聊天流，无法定位 Maimchat 设备"}

        payload = build_device_request_payload(camera=camera)
        sent = await self.ctx.send.custom(
            "device_request",
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            stream_id,
        )
        return {
            "success": bool(sent),
            "content": (
                "已请求单次快照；请等待设备返回带相同 request_id 的新图片消息"
                if sent
                else "设备快照请求发送失败"
            ),
            "request_id": payload["request_id"],
            "camera": payload["camera"],
        }

    @Command(
        "maimchat_avatar_test",
        description="手动测试 Maimchat 表情与动作",
        pattern=(
            r"^/maimchat\s+avatar"
            r"(?:\s+(?P<emotion>[A-Za-z_\-]+))?"
            r"(?:\s+(?P<action>[A-Za-z_\-]+))?\s*$"
        ),
    )
    async def handle_avatar_test(self, stream_id: str = "", **kwargs: Any):
        groups = kwargs.get("matched_groups")
        groups = groups if isinstance(groups, dict) else {}
        emotion = _clean_name(groups.get("emotion")) or "happy"
        action = _clean_name(groups.get("action")) or "wave"
        result = await self.handle_avatar_intent(
            emotion=emotion,
            action=action,
            stream_id=stream_id,
        )
        return bool(result["success"]), str(result["content"]), True

    @Command(
        "maimchat_camera_test",
        description="手动请求 Maimchat 单次摄像头快照",
        pattern=r"^/maimchat\s+camera(?:\s+(?P<camera>front|back))?\s*$",
    )
    async def handle_camera_test(self, stream_id: str = "", **kwargs: Any):
        groups = kwargs.get("matched_groups")
        groups = groups if isinstance(groups, dict) else {}
        camera = _clean_name(groups.get("camera")) or "front"
        result = await self.handle_camera_snapshot(camera=camera, stream_id=stream_id)
        return bool(result["success"]), str(result["content"]), True


def create_plugin() -> MaimchatDevicePlugin:
    """Create a plugin instance for MaiBot's plugin Runner."""

    return MaimchatDevicePlugin()
