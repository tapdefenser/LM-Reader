---
name: mumu-emulator
description: 用 MuMu 模拟器（Android 15 / x86_64）作为本项目的 Android 调试目标：连接 adb、安装 APK、截图、看日志、模拟输入。当真机不在手边、或需要一台可重启的干净设备复现问题时使用。
whenToUse: 需要安装并运行 app-debug.apk、抓取 UI 截图或 logcat、模拟点击/滑动、或核对设备上的应用数据库时。
---

# MuMu 模拟器调试

## 环境（固定路径，不要每次重新探测）

```powershell
$ADB = "D:\Program Files\Netease\MuMu\nx_device\15.0\shell\adb.exe"
$SERIAL = "127.0.0.1:7555"
```

| 项 | 值 |
|---|---|
| MuMu 版本 | Netease MuMu `nx_device\15.0` |
| 系统版本 | Android 15 |
| 连接地址 | `127.0.0.1:7555` |
| 应用包名 | `com.lmreader.debug` |
| 主 Activity | `com.lmreader.MainActivity` |

## 常用命令

```powershell
$ADB = "D:\Program Files\Netease\MuMu\nx_device\15.0\shell\adb.exe"
$SERIAL = "127.0.0.1:7555"

# 连接（模拟器需先启动；未启动时 connect 会失败）
& $ADB connect $SERIAL

# 确认在线
& $ADB devices -l

# 安装（-r 保留数据）
& $ADB -s $SERIAL install -r "D:\VSC\LM-Reader\app\build\outputs\apk\debug\app-debug.apk"

# 冷启动并计时
& $ADB -s $SERIAL shell am force-stop com.lmreader.debug
& $ADB -s $SERIAL shell am start -W -n com.lmreader.debug/com.lmreader.MainActivity

# 截图到本地
& $ADB -s $SERIAL exec-out screencap -p > D:\VSC\LM-Reader\.scratch\shot.png

# 日志：清缓冲 → 操作 → 按 tag 过滤
& $ADB -s $SERIAL logcat -c
& $ADB -s $SERIAL logcat -d -s AndroidRuntime:E

# 模拟输入（坐标用 `wm size` 的实际分辨率）
& $ADB -s $SERIAL shell input tap 540 1200
& $ADB -s $SERIAL shell input swipe 540 800 540 2200 300
& $ADB -s $SERIAL shell input text "NovaSamus"

# 当前前台窗口
& $ADB -s $SERIAL shell "dumpsys window 2>/dev/null | grep -i mCurrentFocus"

# 屏幕分辨率（截图与点击坐标都以此为准）
& $ADB -s $SERIAL shell wm size
& $ADB -s $SERIAL shell wm density
```

## 在这台模拟器上调试本项目时的注意点

1. **ABI**：MuMu 是 x86_64。若 APK 只带 `arm64-v8a` 的 native 库，需要确认 MuMu 的 ARM 转译是否可用；纯 Kotlin/Java 的应用不受影响。
2. **截图坐标换算**：`screencap` 出图是设备真实分辨率（`wm size`）。看图的预览会被缩放，换算比例 = 真实宽 / 预览宽，再据此换算点击坐标。这台机器常见为 1080×2400，预览 536×1191，比例约 2.015。
3. **底部区域**：模拟器的导航栏/手势区同样会吃掉贴近屏幕底边的点击。应用侧要用 `windowInsetsPadding(WindowInsets.navigationBars)`；调试侧若点击落点无效，先怀疑落进了系统手势区。
4. **取应用数据库**：`sqlite3` 不在设备上，`run-as` 也无法往 `/sdcard` 或 `/data/local/tmp` 写（SELinux 拒绝 untrusted_app）。用流到宿主机的方式，**主库 + WAL + SHM 三个都要取**，只取主库会读到旧快照：

```powershell
foreach ($f in @("lmreader.db", "lmreader.db-wal", "lmreader.db-shm")) {
  $name = if ($f -eq "lmreader.db") { "v.db" } else { "v.db-" + ($f -replace "lmreader\.db-?", "") }
  & $ADB -s $SERIAL exec-out run-as com.lmreader.debug cat "/data/data/com.lmreader.debug/databases/$f" `
      > "D:\VSC\LM-Reader\.scratch\$name"
}
```

   取到后用 `python .scratch/<脚本>.py` 查（宿主机有 Python 与 `sqlite3` 模块）。`.scratch/` 已在 `.gitignore`。

5. **改用例数据**：模拟器上的漫画目录需自己放。`adb push` 到 `/sdcard/` 之后再在图库路径表里授权；真机上的 3864 个真实漫画不会出现在这里。
6. **logcat 只按 tag 过滤更可靠**：MIUI 真机上 `logcat` 会混进大量系统噪声；模拟器上干净得多，`-s <TAG>:*` 通常就够。

## 与本项目真机的关系

真机（小米 14 Ultra）通过 `adb connect 192.168.1.38:35339` 连接，用的是平台工具 `C:\Users\Dong\AppData\Local\Android\Sdk\platform-tools\adb.exe`。两台设备的包名相同（`com.lmreader.debug`），**注意别把 APK 装错设备**：命令里始终带 `-s $SERIAL`。

模拟器的优势是可反复冷启动、可 push 造数据；真机用于确认 MIUI 相关的行为（导航栏、后台限制、SAF 提供方差异）。
