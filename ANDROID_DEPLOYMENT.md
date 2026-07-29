# Maimchat Android 部署说明

Maimchat 本身是基于 Cubism SDK for Java 的原生 Android 客户端。本方案在现有架构上
增加 MaiBot 0.6.8 直连、平板常驻和私有更新能力，不引入额外的协议转换服务。

## 架构

```text
Android 平板
  ├─ Cubism SDK for Java：本地渲染 Live2D
  ├─ maim_message 0.6.8：标准消息协议
  ├─ WSS /maibot/ws：直连 MaiBot
  └─ HTTPS /maimchat/updates/manifest.json：APK 与模型资源更新
                    │
                    ▼
                  MaiBot
```

Android App 自己生成和解析 `MessageBase`，直接与 MaiBot 通信。
App 通过 `platform: maimchat_android` WebSocket 请求头注册回复路由，MaiBot 的
`[bot].platforms` 需要包含对应账号，例如：

```toml
[bot]
platforms = ["maimchat_android:maimchat-tablet-bot"]
```

## 本地开发与真机测试

如果 MaiBot WebSocket 只监听服务器的 `127.0.0.1:8000`，可以通过 SSH 和 ADB
分别建立两段仅本机可见的隧道：

```powershell
# 窗口 1：电脑 28000 -> MaiBot 服务器 8000
ssh -N -T -L 127.0.0.1:28000:127.0.0.1:8000 maibot-host

# 窗口 2：手机 8000 -> 电脑 28000
adb reverse tcp:8000 tcp:28000
```

App 内填写：

```text
ws://127.0.0.1:8000/ws
```

## 场地部署

正式平板不应依赖 ADB 或开发电脑。建议在可访问 MaiBot 的入口机上配置
Nginx/Caddy：

- `wss://chat.example.com/maibot/ws` 反向代理到 MaiBot `127.0.0.1:8000/ws`。
- `/maimchat/updates/` 作为只读静态目录发布更新文件。
- 在反向代理层完成 TLS、访问令牌、IP/VPN 限制和日志记录。
- 保留并转发 App 发送的 `platform` 请求头。

不要直接向公网开放 MaiBot 的 8000 端口。App 的“访问令牌”字段可填写原始 token
或完整的 `Bearer ...` 值，发送时会统一写入 `Authorization` 请求头。更新清单中的
APK 与资源包 URL 必须和清单同源，避免把访问令牌发送到其他站点。

## 发布更新

默认产物目录为 `dist/updates`，也可指定任意 Nginx/Caddy 静态目录：

```powershell
.\publish_update.ps1 `
  -BuildType debug `
  -VersionCode 26072803 `
  -VersionName "0.3.0-dev.3" `
  -Notes "Direct MaiBot connection" `
  -OutputDirectory "D:\deploy\maimchat\updates"
```

目录会包含 APK 和 `manifest.json`。App 默认从与 WSS 相同的站点读取
`/maimchat/updates/manifest.json`，下载后校验 SHA-256。

如果要私有发布一个已获授权的模型资源包，可以额外指定：

```powershell
.\publish_update.ps1 `
  -ResourceSource "D:\licensed-models\Assistant" `
  -ResourceId "Assistant"
```

Live2D 模型和动作资源可以热替换并重载；Kotlin、Java、原生库和 Manifest 的变化
仍需通过签名 APK 更新。

正式部署必须创建并备份稳定的 release keystore 与 `signing.properties`。所有后续
OTA APK 必须使用同一签名。Debug APK 只适合开发测试。

## 平板设置

- 长期接电时启用设备支持的充电保护或充电上限。
- 将 Maimchat 加入电池优化白名单并允许后台运行。
- 首次允许 Maimchat 作为安装来源；后续 APK OTA 仍需 Android 系统确认。
- App 会保持屏幕常亮、隐藏系统栏，并以最大 30 秒退避持续重连。
- 真正无人值守的静默安装和开机锁定需要将平板配置为受管设备/device owner。

## Live2D 官方示例

仓库不再分发 Cubism Core、SDK 或模型原始文件。下载 Cubism SDK for Java 后，可以
由本地脚本将 SDK 自带的官方 Hiyori 示例准备到 App 的 assets 目录：

```powershell
.\prepare_live2d_sample.ps1 -AcceptLive2DTerms
```

执行前必须阅读并接受：

- [Live2D Free Material License Agreement](https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html)
- [Terms of Use for Live2D Cubism Sample Data](https://www.live2d.com/en/learn/sample/model-terms/)

Hiyori 原始文件仍由 `.gitignore` 排除，只作为本地构建输入。App 默认优先选择
Hiyori；如要部署自有角色，请另行确认模型的分发、展示与商用许可。

使用官方示例的构建应保留以下声明：

> This content uses sample data owned and copyrighted by Live2D Inc.
