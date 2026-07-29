# Live2D 模型与资源

此目录用于存放 Live2D 模型及相关纹理、动作、着色器等资源。

## 放置指南

- 下载 Cubism SDK for Java 并接受 Live2D 条款后，可在仓库根目录运行
  `.\prepare_live2d_sample.ps1 -AcceptLive2DTerms` 准备官方 Hiyori 示例。
- 为自有模型建立独立文件夹，例如 `MyModel/`。
- 模型文件需包含 `.model3.json`、`.moc3`、纹理 (`.png` 等) 以及可选的动作/物理配置。
- 仓库会自动忽略模型原始文件，请自行备份并确认分发、展示与商用许可。

## 提示

- 应用会扫描 `assets/` 根目录下的所有模型文件夹。
- 默认选择会优先使用 Hiyori；若目录为空，运行时只会展示占位提示。
