# v0.1.0 发布准备

[English](RELEASING.en.md) · [中英发行说明](releases/v0.1.0.md)

检查日期：2026-10-01。按用户选择，当前交付**未签名发布候选**，不是已公开发行或可直接安装的 APK。

## 当前候选

| 项目 | 配置 |
|---|---|
| 版本 / versionCode | `0.1.0` / `2`，唯一来源 `gradle/release.properties` |
| 拟用标签 | `v0.1.0`，尚未创建 |
| Release applicationId | `com.lmreader`；Debug 为 `com.lmreader.debug` |
| Android | min API 26、target API 36、compile SDK 37 |
| ABI | `arm64-v8a`、`x86_64`；APK 不含 32 位库 |
| Release 优化 | R8 与资源收缩开启 |
| 签名 | 本轮未配置正式密钥，产物为 unsigned |
| 候选文件 / 大小 | `LM-Reader-v0.1.0-64bit-unsigned.apk` / 195,982,080 字节，约 186.9 MiB |
| 源码基线 | 此 APK 从当时的未提交工作区构建；候选 metadata 记录该次 HEAD 和 dirty 状态 |

准备目录默认为 `.scratch/release-v0.1.0/`，含命名 APK、`RELEASE_NOTES.md`、`THIRD_PARTY_NOTICES.md`、完整视觉/机翻声明、`release-metadata.json` 和 `SHA256SUMS`。这些本机产物不入 Git。未签名候选不能直接安装，也不能充当正式下载附件。

本轮 APK 的 SHA-256：`6b38d2d26a5352c48fd88ba8023cd9b31e16ee75d8580456e045ccb4030ae4df`。签名或重建后以新生成的 `SHA256SUMS` 为准。

## 语言行为

统一策略在 `AppLanguage`，由 Activity、通知、界面文案和偏好语言标签共用。手动简中/英语优先；跟随系统只看首选系统语言。

- `zh-Hans`、`zh-CN`、`zh-SG` 和未指定繁体地区的 `zh` 使用简中。
- `zh-Hant`、`zh-TW`、`zh-HK`、`zh-MO`、英/日/韩/法/德/阿拉伯语等使用英语；无系统语言也回退英语。
- 显式 Hans 脚本优先于地区，如 `zh-Hans-HK` 仍为简中；显式 Hant 即使地区 CN 也为英语。
- Android 默认字符串资源为英语，简中放在 `values-b+zh+Hans`；保留「跟随系统」的 null 偏好，不把它改写为手动英语。
- App Bundle 不按语言拆分，保证日后通过 Bundle 安装时手动切换也能使用两种语言资源。
- 漫画标题、路径、用户输入和每部漫画的翻译目标不随界面语言改变。

四项 JVM 策略测试覆盖脚本/地区、其他/空语言和手动覆盖；三项设备测试覆盖 Android 实际资源、离线界面词典及偏好保存。设备语言用隔离 Configuration 测试，未改变系统语言。

## 构建与本机校验

工具：JDK 21、Python 3.9+ / PyYAML、Android SDK Platform `37.0`、Build Tools `36.0.0`、NDK `28.2.13676358`、CMake `3.22.1`。

```powershell
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease test --console=plain
python tools/package_release.py --allow-unsigned
```

打包工具从真实 APK 验证包名/版本/SDK、ZIP CRC、签名状态、64 位 ABI、两个自有 native 引擎、所有 ELF LOAD 段至少 16 KB 对齐、ZIP 16 KB 对齐、六个模型的 SHA-256 和界面词典 JSON。它不签名、不提交、不创建标签、不上传。没有 `--allow-unsigned` 时拒绝未签名候选或未提交源码；即使已签名，模型许可/真机验收等人工发布门槛仍由负责人确认。

`.github/workflows/verify.yml` 提供只读构建/单测/lint 工作流及报告产物，不自动创建 Release 或上传 APK。工作流需要全量提交新模块、资源、脚本及源码锁定文件后才能在 GitHub 运行；本机通过不等于远端 CI 已通过。

## 日后正式签名

保存正式密钥及密码的可靠备份。Android 的安装与持续升级需要正确签名，签名规则见 [Android 官方说明](https://developer.android.com/studio/publish/app-signing)。本项目从环境变量读取四项：

| 变量 | 内容 |
|---|---|
| `LMREADER_KEYSTORE_FILE` | 正式 keystore 的绝对路径 |
| `LMREADER_KEYSTORE_PASSWORD` | store password |
| `LMREADER_KEY_ALIAS` | key alias |
| `LMREADER_KEY_PASSWORD` | key password |

四项应一起设置或一起清空。禁止把密钥/密码写进仓库。签名构建使用 `--no-configuration-cache`，避免密码进入配置缓存；无需在聊天、issue 或日志中提供密码。

```powershell
# 四个变量已在本机设置后：
.\gradlew.bat --no-configuration-cache :app:assembleRelease :app:lintRelease
python tools/package_release.py --apk app/build/outputs/apk/release/app-release.apk
```

正式包需通过 `apksigner verify --verbose`、安装与相同证书的覆盖升级，并验收收缩后的 JNI、四种输入、翻译/编辑、导出/恢复与通知。Debug/Release 为不同包，数据迁移用备份恢复。实际正式包改变后重新生成 hash，不复用未签名包校验值。

## GitHub 发行步骤

1. 审阅并提交全部当前实现，包含未跟踪的核心模块、schema、资源、测试和工具；在干净检出验证构建。
2. 确认项目自身 LICENSE、完整第三方声明及 Seg 权重再分发依据。当前声明明确该权重未完成独立授权核对，不能把上游源码 MIT 当作权重许可。
3. 完成正式签名、Release 安装/升级和 ARM 验收；更新中英发行说明及实际 APK 校验值。
4. 在对应已验证提交创建并推送 `v0.1.0`，创建 **draft / prerelease**，附签名 APK、SHA256SUMS、声明和来源。确认后再公开。

GitHub Release 依赖标签指向的提交，见[官方说明](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)。可通过网页操作；已安装并认证 GitHub CLI 时可用：

```sh
# 只在完整源码已提交、签名产物已验收后执行：
git tag -a v0.1.0 -m 'LM-Reader v0.1.0'
git push origin v0.1.0
gh release create v0.1.0 --verify-tag --draft --prerelease \
  --title 'LM-Reader v0.1.0' --notes-file docs/releases/v0.1.0.md \
  .scratch/release-v0.1.0/LM-Reader-v0.1.0-64bit.apk \
  .scratch/release-v0.1.0/SHA256SUMS
```

本轮没有创建远端 Release/标签；工作区候选不能用旧 HEAD 作为发行源码。发行说明中的候选状态在正式附件就绪后再更新。

## 验证记录与剩余验收

Debug/Release 构建和双变体 lint 本机通过；全模块 JVM 测试 **407 项通过、1 项跳过、0 项失败**（Debug 与 Release 的同组测试不重复计数）。MuMu Android 15 的语言 3 项和可靠性 14 项共 **17 项通过**。语言测试包含日、韩、法、阿拉伯语与繁中资源回退，简中资源及手动优先。

lint 无 Fatal/Error，Debug 有 83 条、Release 有 79 条 Warning；这不是零警告构建。打包复核了 22 个 native 库、六个模型和清单中的 37 份文件；公开文档链接已校验，GitHub 工作流已通过 YAML 静态解析。

构建日志：`.scratch/release-preparation-build.log` 和 `.scratch/release-preparation-final-build.log`；设备日志：`.scratch/release-preparation-device-tests.log`。最新 APK 大小/hash、ABI 和 ELF 对齐值由候选 `release-metadata.json` 记录，汇总在 `.scratch/release-preparation-verification.json`。

仍待：正式证书与安装升级、干净检出/远端 CI、项目 LICENSE/完整依赖声明/Seg 分发依据、ARM 长时锁屏与省电、真实 SD/云提供方、实盘耗尽和系统杀进程矩阵。更多导出格式、任意工作流断点、跨 schema 迁移不属于当前首版能力。

本轮补充了「关于 / GitHub / 检查更新」、已完成任务自动出队及中英文档。新增队列设备测试已编译；模拟器关闭后，本轮未重跑设备测试。最新构建记录见 `.scratch/github-preparation-repair-build.log` 与 `.scratch/github-preparation-package-build.log`。
