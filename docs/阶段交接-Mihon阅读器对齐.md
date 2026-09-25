# 阶段交接：Mihon 阅读器对齐

## 当前基线

- 工作分支：`main`
- 本阶段第一个已提交改动：`4fa2879 fix: index every archive chapter and stop deleting repaired chapter lists`
- 上一个阶段的交接见 [阶段交接-图片目录阅读器.md](./阶段交接-图片目录阅读器.md)。
- 参照基准：`.research/mihon`，固定提交 `f52d890e7f8a3c418ddab41f41d4b577bce0dc06`（与 [上游参考与复用边界](./上游参考与复用边界.md) 记录一致）。

## 本阶段目标

用户要求「除遮罩外，把 Mihon 阅读器照搬过来」。Mihon 阅读器使用 View + RecyclerView/ViewPager，本项目使用 Compose，因此这是**按行为移植**，不是复制代码——也符合 [上游参考与复用边界](./上游参考与复用边界.md) 第 5 节的许可纪律（不把上游实现复制进应用源码）。

对照后确认：开发文档 12 节列出的阅读器设置本就与 Mihon 的选项集高度重合，因此这不是扩范围，而是把 12 节做实。

### 范围决定（用户已确认）

1. **阅读模式全覆盖 + 间隔/快捷键**：Mihon 的 5 种可选项（L2R、R2L、竖向分页、条漫、条漫带间隔）+ 页间隔、双击缩放比例、音量键翻页、点击区域自定义。
2. **不照搬 WebGPU 渲染器**：Mihon 新版 `highQualityRenderer` 是自研原生 GPU 管线（`WebGpuViewer.kt` 单文件 1411 行，含 14 种转场动画）。按 `WebGpuViewer` 关闭时的经典途径移植（`PagerViewer` + `WebtoonViewer`），即绝大多数 Mihon 用户实际看到的实现。
3. **页内进度严格照搬 Mihon**：只存页码，不存页内偏移。`reading_progress.intraPageRatio` 保留但恒为 0。**开发文档 12 节「条漫保留页内比例进度」本轮不实现**（已在 [框架实现说明](./框架实现说明.md) 6.5 注明）。
4. **交付节奏**：逐阶段提交，每阶段真机验证。

## 已完成的核对工作（真机，只读）

设备：小米 14 Ultra（`aurorapro`），`192.168.1.38:35339`，已通过 `adb connect` 连接。
包名：`com.lmreader.debug`。已授予 `MANAGE_EXTERNAL_STORAGE`。

### 上一阶段两项待确认修复：成立

| 项 | 证据 |
|---|---|
| 章节页数回填 | `chapters` 表 `Swarm_Chapter 3_ The Wizard's Fortune` → `pageCount=33` |
| 直接文件封面不再误走 SAF | 同章 `coverDocumentId=/storage/emulated/0/Tachiyomi/local/10000-nichi no 7/Swarm_Chapter 3_.../001.jpg`（`file://` 路径） |
| 崩溃 | logcat 4000 行内无 `FATAL`，无 `getRootFromDocId` / `ExternalStorageProvider` 异常 |

阅读进度也在（`reading_progress` 有记录，停在**第 6 页**，不是上一份交接记的第 2 页——用户后来继续翻过）。

### 取库方式（后续复用）

`sqlite3` 不在手机上，`run-as` 也无法往 `/sdcard` 或 `/data/local/tmp` 写入（SELinux 拒绝 untrusted_app）。可用方式是流到宿主机：

```powershell
$adb = "C:\Users\Dong\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 主库 + WAL + SHM 三个文件都要取，否则读到旧快照
& $adb exec-out run-as com.lmreader.debug cat /data/data/com.lmreader.debug/databases/lmreader.db     > .scratch/vN.db
& $adb exec-out run-as com.lmreader.debug cat /data/data/com.lmreader.debug/databases/lmreader.db-wal > .scratch/vN.db-wal
& $adb exec-out run-as com.lmreader.debug cat /data/data/com.lmreader.debug/databases/lmreader.db-shm > .scratch/vN.db-shm
```

`.scratch/` 已在 `.gitignore`（第 5 行），截图与拉下来的库不入库。**教训**：只取主库而不取 WAL 会读到陈旧状态，本轮曾因此误判修复未生效。

屏幕坐标换算：`screencap` 是 1080×2400（`wm size` 的 override），抓图预览缩放到 536×1191，点屏坐标需乘约 2.015。

## 本阶段已修复的缺陷：多章节归档索引

### 症状

`/Tachiyomi/local/NovaSamus/` 磁盘上有 59 个章节 zip，索引后只挂上 1 个，其余 59 个在详情页看不到；同一来源下 `Jyminish  OOHS/` 的 2 个 zip 也只挂上 1 个。开发文档 5.3 第 10 行要求 `作品/01.cbz、02.zip、03.pdf` 得到「共 3 章」。

### 根因（两处，均为代码缺陷）

1. **`StructureScanner.scanManga` 的归档分支只取自然序第一个归档当章节**，并声明 `chaptersFullyEnumerated = false`。归档清单其实零额外 IO——子项列表已经在 `enumerateOnce()` 拿到手，逐个变成 `ChapterSpec` 只是内存里的 map；昂贵的是「枚举每个归档的页数、打开每个 PDF」，那才是「深入」阶段的事。而唯一能补齐章节的 `ChapterResolver` 只挂在详情页的「更新章节」按钮上，**扫描管线从不自动调用它**，所以旧的「稍后补齐」从未发生。

2. **`MangaRepositoryImpl.upsertScanResult` 删除失效章节时只检查 `anchorEnumerated`**。`scanManga` 通过 `enumerateOnce()` 已经把锚点目录记入 `fullyEnumerated`，因此该条件恒真：用户手动「更新章节」补齐 60 章后，下一次扫描重发只有 1 章的清单，会把另外 59 行删掉，并**连带删掉引用它们的 `reading_progress`**（该表没有外键级联）。这是一条会毁用户数据的路径。

### 修改

- `StructureScanner.scanManga`：登记**全部**直接归档并声明 `chaptersFullyEnumerated = true`。保持子目录分支「找到第一个叶子就停」不变（那里的探测确实要一次目录枚举）。
- `MangaRepositoryImpl.upsertScanResult`：删除条件改为同时要求 `anchorEnumerated && chapterCountKnown`——`chapterCountKnown` 正是「这份章节清单完整」的既有声明（开发文档 5.1）。
- 测试：`StructureAcceptanceTest` 第 10/11 行与 `StructureScannerBehaviorTest` 的压缩包用例原先断言的正是上述错误行为（期望 `["01"]` 与 `chapterCountKnown=false`），已按开发文档 5.3 改为全部章节。

### 真机验证

| | 修复前 | 修复后 |
|---|---|---|
| NovaSamus `chapterCount` | 1 | 59 |
| NovaSamus `chapterCountKnown` | 0（「已发现 1 章」） | 1（「共 59 章」） |
| NovaSamus 实际章节行 | 1 | 59 |
| Jyminish OOHS | 1 章 | 2 章 |

无需数据库迁移：`chapterId` 只由 `(documentId, kind)` 派生，所以旧的 STALE 卡片上的章节行被锚点目录漫画就地接管（`ChapterDao.upsertAll` 是 `OnConflictStrategy.REPLACE`）。库里已有这种迁移的先例。

### 遗留（本轮未处理，属独立切片）

- 发现阶段对**图片目录**漫画仍然只探测一个章节（`chaptersFullyEnumerated = false`），准确章节数仍要用户点「更新章节」。开发文档 6.1 第 3 步写的是「打开详情/更新章节时枚举该漫画章节」，但打开详情页**并不触发**枚举（`MangaDetailViewModel.loadDetail` 只读缓存）。这是「延后枚举从未被触发」的缺口，与本次修的归档分支同源但不同支。需要单独决定触发时机与限流（深入路径会打开每个子目录）。
- `ScanDao` 已声明但从未被调用，`scan_runs` 表恒为空，扫描历史无从追溯。
- 4044 / 4644 个 AVAILABLE 漫画的 `coverDocumentId` 为 NULL（`/EhViewer/download` 占 3517）。

## Mihon 阅读器规格（已提取，供实现对照）

三份完整规格已产出（分页阅读器 / 条漫阅读器 / 设置面）。关键条目：

### 阅读模式（`ReadingMode.kt`）

| 枚举 | flagValue | 英文标签 | 方向 | 类型 |
|---|---|---|---|---|
| `DEFAULT` | 0x00 | Default | — | 未解析哨兵 |
| `LEFT_TO_RIGHT` | 0x01 | Paged (left to right) | 水平 | Pager |
| `RIGHT_TO_LEFT` | 0x02 | Paged (right to left) | 水平 | Pager |
| `VERTICAL` | 0x03 | Paged (vertical) | 垂直 | Pager |
| `WEBTOON` | 0x04 | Long strip | 垂直 | Webtoon |
| `CONTINUOUS_VERTICAL` | 0x05 | Long strip with gaps | 垂直 | Webtoon |

`MASK = 0x00000007`；默认全局值是 `RIGHT_TO_LEFT`。

### 点击区域（`TapZones`，6 项，顺序固定）

`Default` → `L shaped` → `Kindle-ish` → `Edge` → `Right and Left` → `Disabled`。
导航区域用归一化矩形（0..1），区域表：

- `LNavigation`：PREV `(0,0.33)-(0.33,0.66)`、PREV `(0,0)-(1,0.33)`、NEXT `(0.66,0.33)-(1,0.66)`、NEXT `(0,0.66)-(1,1)`
- `KindlishNavigation`：NEXT `(0.33,0.33)-(1,1)`、PREV `(0,0.33)-(0.33,1)`
- `EdgeNavigation`：NEXT `(0,0)-(0.33,1)`、PREV `(0.33,0.66)-(0.66,1)`、NEXT `(0.66,0)-(1,1)`
- `RightAndLeftNavigation`：LEFT `(0,0)-(0.33,1)`、RIGHT `(0.66,0)-(1,1)`
- `DisabledNavigation`：空列表
- `Default`：竖向分页 → `LNavigation`，其余 → `RightAndLeftNavigation`

命中规则：先查区域表，未命中一律 `MENU`。`constantMenuRegion = RectF(0,0,1,0.05)` 存在但行为上是死代码（fallback 也返回 MENU）。
反转模式在**归一化坐标**上镜像：水平 → `(1-right, top, 1-left, bottom)`；垂直 → `(left, 1-bottom, right, 1-top)`；两者 → `(1-right, 1-bottom, 1-left, 1-top)`。
**点击区域不随 R2L 自动翻转**——R2L 的翻页方向由适配器列表反转实现，不要"修正"这一点。

### 关键常量（不要凭感觉取值）

- `offscreenPageLimit = 1`（两侧各预载一页）
- 章节预载触发：`pages.size - page.number < 5`
- 双击缩放：目标 `scale * 2`（非动画图）；`maxScale = scale * 5`；动画时长默认 500ms（`pref_double_tap_anim_speed`）
- `navigateToPan` 平移：250ms，`EASE_OUT_QUAD`
- `landscapeZoom`：延迟 500ms + 动画 500ms，`EASE_IN_OUT_QUAD`，且**只对"适合页面"缩放类型生效**
- 条漫：`scrollDistance = 屏高 * 3 / 4`；`CONTINUOUS_VERTICAL` 的间隔是 **15dp bottomMargin**
- 条漫缩放：`MIN_RATE 0.5` / `MAX_SCALE_RATE 3`，双击目标 `2f`，动画 200ms
- 菜单滚动隐藏阈值：`ReaderHideThreshold` 5 / 13 / 31 / 47 px，默认 **31**
- `imageScaleType` 与 `zoomStart` 偏好都是 **1 基**（易错位）

### 条漫进度语义

`findLastEndVisibleItemPosition` 自底向上，条件是 `childEnd <= parentEnd || childStart < parentStart`——即**页底越过视口底**才算当前页（与分页的"页顶到达"不同）。恢复用 `scrollToPositionWithOffset(page, 0)`，页内偏移丢弃。

### 页内回调命名陷阱

`ReaderPageImageView.onImageLoaded`（视图）与 `WebtoonPageHolder.onImageDecoded`（holder）是两个东西，后者是前者触发后的反应。

## 待实现阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| A | `ReaderPreferences`（DataStore）+ 枚举 + `PageSource` 尺寸探测 + 分页三方向 + 条漫两模式骨架 | 未开始 |
| B | 6 种点击区域 + 反转 + 导航遮罩层 + 长按菜单 + 音量键 | 未开始 |
| C | 缩放（双击/双指/缩放起点/六种 scale type/裁白边/`navigateToPan`/`landscapeZoom`） | 未开始 |
| D | 连续垂直与条漫打磨：间隔、side padding、章节过渡、滚动驱动进度 | 未开始 |
| E | 阅读设置界面（阅读模式/通用/自定义滤镜三分页）+ 每漫画覆盖 + 数据库迁移 | 未开始 |
| F | 章节导航器（含竖向）、页码指示、滚动自动隐藏、亮度/灰度/反色 | 未开始 |

### 关于 `PageSource` 契约（重要修正）

原计划加入「带 offset/length 的随机读」。Mihon 的证据表明**那不是真需求**：连续模式只需要①每页一个独立流（现有契约已满足）②知道图像尺寸来算条带布局。而 `ZipFile` 恰好只支持单流，随机读反而给将来的 ZIP 接入制造麻烦。

因此契约改动收窄为：增加 `suspend fun probe(page): PageGeometry`（宽高，一次轻量解码后缓存）。这比随机读简单得多，且 ZIP 将来更好接。

### 明确不做的部分

- Mihon 的 WebGPU 渲染器与 14 种转场动画（自研原生 GPU 管线）
- 顶栏溢出菜单的 `Open in WebView` / `Open in browser`（本项目没有在线源）
- 下载/跟踪站点相关的一切
- 双页（`dual_page_view` / `split wide pages` / `rotate to fit`）：开发文档 12 节列为「后续能力验证项」

## 验证记录

```powershell
.\gradlew.bat :core:index:test :core:storage:testDebugUnitTest :core:database:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

结果：`BUILD SUCCESSFUL`。Gradle 仍输出既有的 Gradle 10 / 弃用迁移警告，本阶段没有处理。

真机验证期间只读取了测试目录与应用自身数据库，**没有创建、改名、移动或删除手机上的漫画文件**。
