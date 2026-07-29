# Maimchat 的 MaiBot 原生插件

这个目录是可直接复制进 MaiBot 1.x 的原生插件，不是独立 Adapter，也不包含
Unity、Live2D 模型或其他角色资产。

它向 MaiBot 暴露三个 LLM 工具：

- `maimchat_avatar_intent`：发送与渲染器无关的情绪和动作意图。
- `maimchat_camera_snapshot`：请求 Android 设备拍摄一张前置或后置摄像头照片。
- `maimchat_speak`：视频通话中通过可配置 HTTP TTS 服务生成语音并发回设备扬声器。

普通文字、语音和图片消息仍走 Maimchat 已有的 `maim_message` WebSocket 连接。
拍照请求不会开启持续视频流；Android App 默认拒绝远程拍照，必须先由现场用户在
“更多操作”中打开“允许 MaiBot 按需拍照”。

Android 的视频通话模式会把一段自动分轮的语音和当前摄像头关键帧合并为一条
多模态消息。消息会明确要求 MaiBot 简短口语回答并调用 `maimchat_speak`。
TTS 接口默认是 `http://127.0.0.1:9881/v1/synthesize`，应接受 JSON
`{"text":"...","language":"Chinese"}` 并直接返回 WAV；地址、音色和超时都可在
插件配置的“设备能力”中修改。

## 版本要求

- MaiBot `1.0.0` 到 `1.x`
- `maibot-plugin-sdk >= 2.5.4`
- 支持 `avatar_intent` 和 `device_request` 的 Maimchat Android 版本

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

插件向当前聊天流发送两个自定义消息段。形象意图示例：

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
