"""MaiBot 1.x plugin for controlling a Maimchat Android device.

The plugin deliberately sends renderer-neutral semantic messages. The Android
client decides which expression or motion exists in the currently selected
Live2D model, so this plugin does not depend on model assets or Unity.
"""

import asyncio
import base64
import json
import re
import urllib.error
import urllib.request
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
    config_version: str = Field(default="1.2.0", description="配置版本")


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
    call_tts_enabled: bool = Field(
        default=True,
        description="允许视频通话回复通过兼容的 HTTP TTS 服务生成语音",
    )
    call_tts_endpoint: str = Field(
        default="http://127.0.0.1:9881/v1/synthesize",
        description="返回 WAV 音频的 HTTP TTS 接口",
    )
    call_tts_voice: str = Field(
        default="",
        description="TTS 音色名称；留空时使用服务端默认音色",
    )
    call_tts_timeout_seconds: float = Field(
        default=180.0,
        ge=5.0,
        le=300.0,
        description="等待 TTS 生成完成的最长秒数",
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


def clean_speech_text(value: Any, *, limit: int = 240) -> str:
    """Remove common Markdown noise before passing a reply to TTS."""

    text = str(value or "")
    text = re.sub(r"!\[[^\]]*]\([^)]+\)", "", text)
    text = re.sub(r"\[([^\]]+)]\([^)]+\)", r"\1", text)
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"[`*_>#|~]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit]


def decode_call_tts_request(encoded: Any) -> dict[str, str]:
    """Decode and validate the internal Android call-TTS command payload."""

    value = str(encoded or "").strip()
    if not value or len(value) > 4096 or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("无效的通话语音请求编码")
    padding = "=" * ((4 - len(value) % 4) % 4)
    try:
        raw = base64.urlsafe_b64decode(value + padding)
        payload = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("无法解析通话语音请求") from error
    if not isinstance(payload, dict):
        raise ValueError("通话语音请求不是对象")
    if payload.get("version") != 1 or payload.get("client") != "maimchat_android":
        raise ValueError("不支持的通话语音请求版本或客户端")

    request_id = _clean_name(payload.get("request_id"), limit=160)
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{8,160}", request_id):
        raise ValueError("通话语音请求缺少有效 request_id")
    speech = clean_speech_text(payload.get("text"))
    if not speech:
        raise ValueError("通话语音请求文本为空")
    return {
        "request_id": request_id,
        "turn_id": _clean_name(payload.get("turn_id"), limit=160),
        "reply_message_id": _clean_name(payload.get("reply_message_id"), limit=160),
        "text": speech,
    }


def build_call_audio_payload(request: dict[str, str], audio: bytes) -> dict[str, str | int]:
    """Build the correlated audio response consumed by the Android service."""

    return {
        "version": 1,
        "request_id": request["request_id"],
        "turn_id": request["turn_id"],
        "reply_message_id": request["reply_message_id"],
        "text": request["text"],
        "mime_type": "audio/wav",
        "audio": base64.b64encode(audio).decode("ascii"),
    }


def build_call_error_payload(
    request: dict[str, str],
    message: str,
) -> dict[str, str | int]:
    """Build a correlated error response so Android can immediately use local TTS."""

    return {
        "version": 1,
        "request_id": request["request_id"],
        "turn_id": request["turn_id"],
        "phase": "error",
        "message": str(message)[:240],
    }


def _synthesize_voice(
    *,
    endpoint: str,
    text: str,
    voice: str,
    timeout_seconds: float,
) -> bytes:
    """Call a compatible HTTP TTS service and return its audio response."""

    payload: dict[str, str] = {"text": text, "language": "Chinese"}
    if voice:
        payload["voice"] = voice
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            audio = response.read()
            content_type = str(response.headers.get("Content-Type", "")).lower()
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"TTS HTTP {error.code}: {body[:240]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"无法连接 TTS 服务: {error.reason}") from error
    if not audio:
        raise RuntimeError("TTS 服务返回了空音频")
    if content_type and "audio" not in content_type and "octet-stream" not in content_type:
        raise RuntimeError(f"TTS 服务返回了非音频内容: {content_type}")
    return audio


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
        "maimchat_call_tts",
        description="Maimchat Android 内部通话语音请求",
        pattern=r"^/maimchat\s+call-tts\s+(?P<payload>[A-Za-z0-9_-]+)\s*$",
    )
    async def handle_call_tts(self, stream_id: str = "", **kwargs: Any):
        groups = kwargs.get("matched_groups")
        groups = groups if isinstance(groups, dict) else {}
        try:
            request = decode_call_tts_request(groups.get("payload"))
        except ValueError as error:
            self.ctx.logger.warning("拒绝无效的 Maimchat 通话语音请求: %s", error)
            return False, "", 2
        if not stream_id:
            return False, "", 2

        if not self.config.plugin.enabled:
            error_message = "Maimchat 设备插件已禁用"
        elif not self.config.features.call_tts_enabled:
            error_message = "Maimchat 通话语音输出已禁用"
        elif not self.config.features.call_tts_endpoint.strip():
            error_message = "尚未配置 Maimchat 通话 TTS 接口"
        else:
            error_message = ""

        self.ctx.logger.info(
            "收到 Maimchat 通话语音请求 request=%s turn=%s",
            request["request_id"],
            request["turn_id"] or "-",
        )
        if error_message:
            await self.ctx.send.custom(
                "call_state",
                json.dumps(
                    build_call_error_payload(request, error_message),
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                stream_id,
            )
            return True, "", 2

        try:
            audio = await asyncio.to_thread(
                _synthesize_voice,
                endpoint=self.config.features.call_tts_endpoint.strip(),
                text=request["text"],
                voice=self.config.features.call_tts_voice.strip(),
                timeout_seconds=self.config.features.call_tts_timeout_seconds,
            )
        except Exception as error:
            self.ctx.logger.error(
                "Maimchat 通话 TTS 生成失败 request=%s: %s",
                request["request_id"],
                error,
            )
            await self.ctx.send.custom(
                "call_state",
                json.dumps(
                    build_call_error_payload(request, str(error)),
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                stream_id,
            )
            return True, "", 2

        sent = await self.ctx.send.custom(
            "call_audio",
            json.dumps(
                build_call_audio_payload(request, audio),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            stream_id,
        )
        if not sent:
            self.ctx.logger.error(
                "Maimchat 通话音频发送失败 request=%s",
                request["request_id"],
            )
        return bool(sent), "", 2

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
