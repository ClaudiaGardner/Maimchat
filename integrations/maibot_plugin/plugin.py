"""MaiBot 1.x plugin for controlling a Maimchat Android device.

The plugin deliberately sends renderer-neutral semantic messages. The Android
client decides which expression or motion exists in the currently selected
Live2D model, so this plugin does not depend on model assets or Unity.
"""

import asyncio
import base64
import io
import json
import re
import tomllib
import wave
from pathlib import Path
from typing import Any
from uuid import uuid4

from aiohttp import ClientSession, ClientTimeout, ClientWSTimeout, WSMsgType
from maibot_sdk import Command, Field, MaiBotPlugin, PluginConfigBase, Tool
from maibot_sdk.types import ToolParameterInfo, ToolParamType


DEFAULT_TTS_WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
DEFAULT_TTS_MODEL = "qwen3-tts-vc-realtime-2026-01-15"


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
    config_version: str = Field(default="1.3.0", description="配置版本")


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
        description="允许通话回复直接调用云端 TTS API 生成语音",
    )
    call_tts_provider_name: str = Field(
        default="AlibabaDashScope",
        description="从 MaiBot model_config.toml 读取 API Key 的供应商名称",
    )
    call_tts_provider_config_path: str = Field(
        default="config/model_config.toml",
        description="MaiBot 模型供应商配置文件；相对路径从 MaiBot 根目录解析",
    )
    call_tts_websocket_url: str = Field(
        default=DEFAULT_TTS_WEBSOCKET_URL,
        description="云端实时 TTS WebSocket 地址，不包含 model 查询参数",
    )
    call_tts_model: str = Field(
        default=DEFAULT_TTS_MODEL,
        description="云端实时 TTS 模型名称",
    )
    call_tts_voice: str = Field(
        default="",
        description="云端音色或复刻音色 ID",
    )
    call_tts_speech_rate: float = Field(
        default=1.0,
        ge=0.5,
        le=2.0,
        description="云端 TTS 语速倍率",
    )
    call_tts_timeout_seconds: float = Field(
        default=45.0,
        ge=5.0,
        le=300.0,
        description="等待云端 TTS 完成的最长秒数",
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


def _resolve_provider_config_path(value: str) -> Path:
    configured = Path(value).expanduser()
    candidates = [configured]
    if not configured.is_absolute():
        candidates = [
            Path.cwd() / configured,
            Path(__file__).resolve().parents[2] / configured,
        ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise RuntimeError(f"找不到 MaiBot 模型供应商配置: {value}")


def _load_provider_api_key(*, config_path: str, provider_name: str) -> str:
    """Read an existing MaiBot provider key without duplicating it in plugin config."""

    path = _resolve_provider_config_path(config_path)
    try:
        config = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as error:
        raise RuntimeError(f"无法读取 MaiBot 模型供应商配置: {error}") from error
    provider = next(
        (
            item
            for item in config.get("api_providers", [])
            if isinstance(item, dict) and str(item.get("name") or "") == provider_name
        ),
        None,
    )
    if provider is None:
        raise RuntimeError(f"未配置 TTS API 供应商: {provider_name}")
    api_key = str(provider.get("api_key") or "").strip()
    if not api_key:
        raise RuntimeError(f"TTS API 供应商 {provider_name} 的 API Key 为空")
    return api_key


def _pcm16_to_wav(pcm: bytes, sample_rate: int = 24_000) -> bytes:
    if not pcm:
        raise RuntimeError("云端 TTS 返回了空音频")
    output = io.BytesIO()
    with wave.open(output, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm)
    return output.getvalue()


def _tts_event(event_type: str, **payload: object) -> dict[str, object]:
    return {
        "event_id": f"event_{uuid4().hex}",
        "type": event_type,
        **payload,
    }


async def _synthesize_voice(
    *,
    api_key: str,
    websocket_url: str,
    model: str,
    text: str,
    voice: str,
    speech_rate: float,
    timeout_seconds: float,
) -> bytes:
    """Call the cloud realtime TTS WebSocket directly and return a WAV response."""

    if not api_key:
        raise RuntimeError("云端 TTS API Key 为空")
    if not websocket_url or not model or not voice:
        raise RuntimeError("云端 TTS 地址、模型或音色未配置")
    separator = "&" if "?" in websocket_url else "?"
    url = f"{websocket_url}{separator}model={model}"
    pcm = bytearray()
    timeout = ClientTimeout(total=timeout_seconds)
    ws_timeout = ClientWSTimeout(ws_receive=timeout_seconds, ws_close=5)

    async with ClientSession(timeout=timeout) as session:
        async with session.ws_connect(
            url,
            headers={
                "Authorization": f"Bearer {api_key}",
                "User-Agent": "maimchat-android-device/1.0",
            },
            timeout=ws_timeout,
            heartbeat=20,
        ) as websocket:
            async for message in websocket:
                if message.type == WSMsgType.TEXT:
                    payload = json.loads(message.data)
                    event_type = str(payload.get("type") or "")
                    if event_type == "session.created":
                        await websocket.send_json(
                            _tts_event(
                                "session.update",
                                session={
                                    "voice": voice,
                                    "mode": "commit",
                                    "language_type": "Chinese",
                                    "response_format": "pcm",
                                    "sample_rate": 24_000,
                                    "volume": 100,
                                    "speech_rate": speech_rate,
                                },
                            )
                        )
                    elif event_type == "session.updated":
                        await websocket.send_json(
                            _tts_event("input_text_buffer.append", text=text)
                        )
                        await websocket.send_json(_tts_event("input_text_buffer.commit"))
                    elif event_type == "response.audio.delta":
                        encoded_audio = str(payload.get("delta") or "")
                        if encoded_audio:
                            pcm.extend(base64.b64decode(encoded_audio, validate=True))
                            if len(pcm) > 24_000 * 2 * 120:
                                raise RuntimeError("云端 TTS 音频超过两分钟限制")
                    elif event_type == "response.done":
                        await websocket.send_json(_tts_event("session.finish"))
                    elif event_type == "session.finished":
                        break
                    elif event_type == "error":
                        error = payload.get("error", payload)
                        raise RuntimeError(
                            "云端 TTS 返回错误: "
                            + json.dumps(error, ensure_ascii=False)[:480]
                        )
                elif message.type in {
                    WSMsgType.CLOSE,
                    WSMsgType.CLOSED,
                    WSMsgType.ERROR,
                }:
                    raise RuntimeError(f"云端 TTS WebSocket 异常关闭: {message.type}")

    return _pcm16_to_wav(bytes(pcm))


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
        elif not self.config.features.call_tts_voice.strip():
            error_message = "尚未配置云端 TTS 音色 ID"
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
            api_key = await asyncio.to_thread(
                _load_provider_api_key,
                config_path=self.config.features.call_tts_provider_config_path.strip(),
                provider_name=self.config.features.call_tts_provider_name.strip(),
            )
            audio = await _synthesize_voice(
                api_key=api_key,
                websocket_url=self.config.features.call_tts_websocket_url.strip(),
                model=self.config.features.call_tts_model.strip(),
                text=request["text"],
                voice=self.config.features.call_tts_voice.strip(),
                speech_rate=self.config.features.call_tts_speech_rate,
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
