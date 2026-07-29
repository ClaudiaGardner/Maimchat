# Maimchat 的 MaiBot 原生插件

这个目录是可直接复制进 MaiBot 1.x 的原生插件，不是独立 Adapter，也不包含
Unity、Live2D 模型或其他角色资产。

它向 MaiBot 暴露两个与设备有关的 LLM 工具：

- `maimchat_avatar_intent`：发送与渲染器无关的情绪和动作意图。
- `maimchat_camera_snapshot`：请求 Android 设备拍摄一张前置或后置摄像头照片。

普通文字、语音和图片消息仍走 Maimchat 已有的 `maim_message` WebSocket 连接。
拍照请求不会开启持续视频流；Android App 默认拒绝远程拍照，必须先由现场用户在
“更多操作”中打开“允许 MaiBot 按需拍照”。

Android 的视频通话模式会把一段自动分轮的语音和当前摄像头关键帧合并为一条
多模态消息。MaiBot 正常生成文字回复后，Android 后台服务会自动发送一条被插件
拦截的 `/maimchat call-tts` 内部命令；这条命令不会进入回复模型，也不会显示在
聊天记录中。插件用 `request_id` 和 `turn_id` 返回 `call_audio`，Android 只播放
当前轮次的音频。远端 TTS 超时、报错或音频无法播放时，设备会自动改用 Android
系统 TTS，确保每轮有可听回复。`maimchat_speak` 不再作为全局 LLM 工具注册，
因此不会误影响 QQ 通话或其他平台。

插件 1.4.0 还提供可选的端到端实时模式。Android 先通过现有 `maim_message`
连接发送隐藏的 `/maimchat realtime-session` 命令；插件只接受
`realtime_allowed_platforms` 明确列出的平台请求，使用 MaiBot 已配置的长期 API Key 签发最长
30 分钟的阿里云短期 Key，再通过 `realtime_session` 自定义段返回。Android 随后
直接连接 Qwen Omni Realtime，长期 Key 不会进入 APK。实时模式连续传输
16 kHz PCM 麦克风音频并流式播放 24 kHz PCM 回复，支持语义 VAD、插话打断以及
视频模式的每秒摄像头关键帧。授权、连接或音频初始化失败时自动回退到串联模式。

端到端模式绕过 MaiBot 的逐轮 ASR、主回复模型和 TTS，因此延迟更低，但当前会话
不会使用 MaiBot 的长期记忆和普通插件工具。插件会从 `config/bot_config.toml`
同步机器人名字、人格和回复风格，尽量保持角色一致。需要在阿里云百炼 API Key
权限中允许 `qwen3.5-omni-flash-realtime`。

插件不会启动或依赖本地 TTS/STT 服务。TTS 由插件直接连接阿里云实时 TTS
WebSocket，流式接收 PCM 后封装成 WAV 发给 Android；API Key 从 MaiBot 已有的
`config/model_config.toml` 供应商配置读取，不写入 APK 或插件仓库。音色 ID、
模型、WebSocket 地址、语速和超时可在插件配置的“设备能力”中修改。

设备录制的语音仍通过标准 `voice` 消息段交给 MaiBot。STT 应在 MaiBot 的
`model_task_config.voice` 中只配置云端 ASR 模型，例如阿里
`qwen3-asr-flash`；不要把本地 SenseVoice 等模型列为兜底。这样 TTS 与 STT 都是
直接 API 调用，H20 不需要额外运行 9881、ASR 或 TTS 服务。

## 版本要求

- MaiBot `1.0.0` 到 `1.x`
- `maibot-plugin-sdk >= 2.5.4`
- MaiBot 运行环境中的 `aiohttp`（MaiBot 1.x 已包含）
- 支持 `avatar_intent`、`device_request`、`call_audio` 和 `call_state` 的
  Maimchat Android 版本
- 端到端模式还要求支持 `realtime_session`，并允许设备访问阿里云 Realtime
  WebSocket

本插件使用 MaiBot 大更新后的 `@Tool` / `ctx.send.custom` 接口，不能直接装进
0.11.x 及更早版本。请先完成 MaiBot 本体的升级。

## 安装

把整个目录复制到 MaiBot 的 `plugins` 目录，例如：

```text
MaiBot/
└── plugins/
    └── maimchat_device/
        ├── _manifest.json
        └── plugin.py
```

然后在 MaiBot WebUI 中加载/重载 `maim-with-u.maimchat-device`，或重启 MaiBot。
首次加载会按 `plugin.py` 中的配置模型生成 `config.toml`。默认启用形象控制和
MaiBot 侧的拍照工具；Android 侧的拍照授权仍默认关闭。

## 手动联调

从已连接的 Maimchat Android 客户端发送：

```text
/maimchat avatar happy wave
/maimchat avatar thinking nod
/maimchat camera front
/maimchat camera back
```

第一组命令应立即触发表情/动作。第二组命令会在 Android 端已授权时请求一张
快照，并以新的图片消息返回；图片消息的
`additional_config.device_response.request_id` 与请求一一对应。

## 发送协议

插件向当前聊天流发送五类自定义消息段。形象意图示例：

```json
{
  "type": "avatar_intent",
  "data": {
    "emotion": {"name": "happy", "intensity": 0.8},
    "action": {"name": "wave", "parameters": {"index": 0, "loop": false}}
  }
}
```

单次拍照请求示例：

```json
{
  "type": "device_request",
  "data": {
    "request_id": "maimchat-...",
    "type": "camera_snapshot",
    "camera": "front"
  }
}
```

`send.custom` 的 `data` 实际以 JSON 字符串发送，以兼容当前
`maim_message` 自定义消息段和 Android 端解析器。

通话语音成功时发送：

```json
{
  "type": "call_audio",
  "data": {
    "version": 1,
    "request_id": "tts-...",
    "turn_id": "turn-...",
    "reply_message_id": "reply-...",
    "text": "要说出的回复",
    "mime_type": "audio/wav",
    "audio": "UklGR..."
  }
}
```

TTS 失败时发送同一 `request_id` 的 `call_state`，`phase` 为 `error`，让 Android
立即执行本地语音兜底。

端到端授权成功时发送：

```json
{
  "type": "realtime_session",
  "data": {
    "version": 1,
    "request_id": "realtime-...",
    "token": "st-...",
    "expires_at": 2000000000,
    "websocket_url": "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
    "model": "qwen3.5-omni-flash-realtime",
    "voice": "Tina",
    "instructions": "由 bot_config.toml 生成的角色说明"
  }
}
```

Android 不记录该段中的短期 Key；标准消息历史只保留脱敏占位符。短期 Key 最长
30 分钟且只在 WebSocket 握手时使用。
