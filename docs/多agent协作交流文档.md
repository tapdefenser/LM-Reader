# 协作记录

## 2026-09-25：需求文档与上游调查

本轮只做文档和公开源码查阅。工作区起始只有原始需求与空协作记录，尚未建立 Git 仓库；没有覆盖现有应用实现。

### DeepSeek 任务分工

通过用户指定的 deepseek-coding-delegate 本地 runner，限定范围、禁止改上游源码/提交/访问凭据：

| 任务 | 写入范围 | 结果 |
|---|---|---|
| EhViewer/Mihon 阅读器、图库、元数据调查 | `.research/reports/reader-audit.md` | 完成，runner exit 0 |
| 翻译项目流程、设置、遮罩与持久化调查 | `.research/reports/translation-audit.md` | 完成，runner exit 0 |
| 逐屏控件、用户需求追溯、验收草稿 | `.research/reports/ui-requirements-draft.md` | 已写草稿，但 runner exit 1；仅作为待主审素材使用 |
| 最后一次文档一致性审阅 | `.research/reports/final-doc-review.md` | 完成，runner exit 0；12 项建议由主审逐项处理 |

### 主审承担

- 浏览三个指定项目并固定提交，克隆到隔离的 `.research/` 目录；确认未改动上游源码。
- 独立阅读 ComicInfo 两侧模型、Mihon LocalSource 与 PageLoader、翻译设置常量及部分 Store/UI/枚举、流水线接口与上游契约；检查模型资产缺失。
- 选择新应用的架构与歧义默认值，重写正式规格，未将 DeepSeek 建议直接视为用户确认。
- 重点修正：XML 字段差异不等于完全不兼容；全文速译与译名开关冲突；VL 必须同样注入字典；源树扫描不能仅依赖修改时间；内部遮罩与系统浮窗分离；模型资产需要单独验证。
- 文档检查范围为本地链接、固定提交源码路径、Markdown 结构和跨文档一致性；未执行 Android 编译、模型推理或真机性能测试。

### 交接约定

最终修订：补齐跨来源唯一键、首启完成标记、漫画阅读与章节视图偏好、导出详情与产物、会话分页 rank、有效主源、路径行草稿、卷/短篇分组样例、设置导航摘要、VL 结构化响应、搜索投影与 2-gram 表。设置覆盖声明改为可验证范围；79 个主门面字符串键完成机械映射检查，结果在 `.research/reports/settings-key-coverage.json`。原审阅报告保留为修订前问题记录，不代表当前文档仍有 12 个未处理阻塞项。

搭架从 [开发文档](./开发文档.md) 第 17 节 P0 开始；正式参数表为 [翻译配置与上游对照](./翻译配置与上游对照.md)。所有源于设计基线的偏好可调整；需要实验的门槛不能用草稿中的推断代替。

---

## 2026-09-25（第二次）：第一步实现与真机联调

本轮把文档变成可安装、可扫描的应用，并在小米 14 Pro（HyperOS / Android 15，`24031PN0DC`）上真机联调。契约冻结稿是 [框架实现说明](./框架实现说明.md)。

### 交付内容

| 部分 | 产物 |
|---|---|
| 构建骨架 | `build-logic/` 约定插件（AGP 9.2.1 + Kotlin 2.3.21 + Gradle 9.6.1）、版本目录、五个模块 |
| `core:model` | 领域类型、稳定 ID（SHA-256 派生）、`ContentTree` 抽象、仓储契约 |
| `core:index` | 结构扫描器、自然序比较、ComicInfo 解析、摘要；纯 JVM，可脱离设备测试 |
| `core:database` | Room 实体/DAO/迁移、卡片投影分页、书架与分类、仓储实现 |
| `core:storage` | SAF 与直接文件两套 `ContentTree`、授权检测、扫描调度、封面与元数据补全 |
| `:app` | 图库路径配置页（两张表）、图库页、书架页、导航、设置首页、全部文件访问说明页 |

验证结果：`.\gradlew.bat build` 通过（含 lint）；单元测试 61 个（`core:index` 57 + `core:storage` 4，其中 1 个按机器条件跳过）。真机扫描 `/sdcard/Tachiyomi/downloads` 实测索引出 **762 部漫画、28543 章、235 条 ComicInfo 记录，其中 235 部已补出封面**（扫描仍在继续时读取的中间值，因此不是最终总数）。

封面与元数据的补全只覆盖图片目录章节：归档（CBZ/ZIP/PDF）与 PDF 的封面、内部 ComicInfo 属于"深入"阶段，本步未实现，卡片会显示来源徽标而不是假装有封面。

### DeepSeek 分工与主审复核

| 任务 | 写入范围 | 结果 |
|---|---|---|
| `core:model` + `core:index`（扫描算法、自然序、ComicInfo、摘要 + 测试） | 仅这两个模块 | 完成；`:core:index:test` 54 项通过 |
| 构建骨架、`core:database`、`core:storage`、`:app` 全部 UI | 其余 | 主审承担 |

主审对子 agent 的产物做了独立复核：读回全部领域类型与扫描器源码、复核取消语义与 `openChild` 签名偏差、补 3 个针对"中间容器误当漫画"的验收用例、修正它未覆盖的失败原因上报路径。子 agent 报告中的三处集成问题（`TreeFactory` 归属、取消语义、`openChild` 签名）全部采纳并落地。

### 本轮发现并修复的问题（按发现顺序）

真机联调暴露的问题比预期多，且**大部分是接线与平台行为问题，不是算法问题**。逐个记录，因为它们的现象相似（"图库空白"）而根因完全不同。

1. **窗口主题与 minSdk 不匹配**：`Theme.DeviceDefault.DayNight.NoActionBar` 需要 API 29，而 minSdk 是 26。改用 `Theme.DeviceDefault.NoActionBar`。构建期就被 AAPT 拦住。
2. **底部操作条被手势导航条遮挡**：`enableEdgeToEdge()` 之后 `Scaffold` 的 `bottomBar` 只拿到系统给的 `PaddingValues`，"下一步/完成"与扫描状态被压在导航条下面。显式 `windowInsetsPadding(WindowInsets.navigationBars)`。
3. **扫描算法把中间容器当成漫画**：原始实现会对 `download/pixiv/网球王子/第一章/001.jpg` 同时产出「pixiv」和「网球王子」两张卡片，与用户原始需求给出的示例（"网球王子是一个漫画"）直接冲突。已改为"判定为漫画后不再往它的子目录继续寻找漫画"，并补 3 个用例锁住该行为。**这是本轮唯一一处必须改算法的地方**，其余都是接线问题。
4. **文档内部自相矛盾**：开发文档 5.3 第 1 行（`download/pixiv/网球王子/第一章` → 「网球王子」）与第 13 行（`库` + `作者/短篇/001.jpg` → 「作者」）表面冲突。实际不冲突：第 13 行的 `短篇` 是叶子图片目录而不是"含章节的目录"，因此最近的含章节目录就是「作者」。该解释已写入测试 KDoc。
5. **仅取 `mime_type` 一列的 SAF 查询被系统拒绝**：真机上同一目录请求完整列可读、请求单列抛 `SecurityException: Permission Denial`（这在 `adb shell` 侧无法复现，因为 shell 本来就没有授权）。已改为"存在性判断复用一次完整枚举"，顺带把每目录的 binder 查询从 2–3 次降到 1 次。
6. **`DocumentsContract` 不编码 documentId**：`buildChildDocumentsUriUsingTree` 把 documentId 交给 `Uri.Builder.appendPath`，而 SAF 的 documentId 自身含 `/`（`primary:Tachiyomi/downloads/Comic Days (JA)`），拼出的 URI 路径段数与授权树不一致。已改为自建 `SafUris`，把 documentId 编码成**单个路径段**。
7. **（决定性）扫描器持有占位工厂，调用方忘了传真实工厂**：`SourceScanRunner` 构造了按来源授权树生成的 `TreeFactory`，却**没有传给 `scanner.scan()`**；共享的 `StructureScanner` 在容器里被塞了一个 `TreeFactory { null }` 占位实现。现象极具误导性——根目录枚举正常（65 个子项）、65 个子目录全部"打不开"、**没有任何异常**、诊断里只有路径没有原因。修复：`scan()` 增加按次传入的 `factory` 参数，占位实现改成抛异常的 `FailFastTreeFactory`，让同类错误下次立刻可见。
8. **权限链路的真实结论**：Android 11+ 的 SAF 选择器禁止选中存储根、Download 根与 `Android/data`，而真实漫画库经常正好在这些位置。已补充 `MANAGE_EXTERNAL_STORAGE` 声明、应用内说明页（系统授权页要求的可到达链接）、`READ_MEDIA_VISUAL_USER_SELECTED`（Android 14 部分照片访问，lint 提示），并在拿到全部文件访问后改走 `java.io.File`（`FileContentTree`）：既绕开系统提供方的行为差异，也把每目录一次 binder 变成一次 `readdir`。
9. **每次启动的授权检测**（用户要求）：`StorageAccessCoordinator` 在启动时逐条**真实枚举**授权根，而不是只查"有没有授权记录"——真机上出现过记录存在但读取被拒的情况。结论写回来源的 `permission` 字段并汇总成横幅。
10. **错误原因不可见**：MIUI 会过滤掉应用自身的 logcat，而界面只显示"某路径失败"不显示原因，导致排障只能靠反复猜测。已改为：失败原因（异常类型 + 原始 message）写进扫描状态、行内提供"查看原因"入口、诊断弹窗可复制完整报告。

### 交互修订（用户要求，已实现）

- `+` 选择目录后**立即加入列表并开始扫描**，不再需要二次确认；
- 每行的子目录复选框与类型开关**改动即生效**，取消行内"保存/取消"；
- 点路径列打开**弹窗**（可编辑显示名称 + 只读系统路径 + "重新选择目录"），取消不修改已保存数据；
- 扫描中底部按钮变为**取消扫描**，状态行显示"已找到 x 部漫画，y 章，已遍历 z 个目录"，其下显示"正在扫描：<路径>"；
- 路径行支持拖动排序，并保留上移/下移（无障碍等价操作）。

### 已知遗留与下一步必须做的

1. **`directory_snapshots` 与 `scan_runs` 已建表但未写入**：删除判定因此不完整——章节不会因为源里消失而被清理。规范化实现应在 `upsertScanResult` 里登记"完整枚举过的容器"，并在扫描结束时写运行记录。
2. **`SafUris` 的编码修复没有在真机上单独验证**：修复 7 之后走的是直接文件访问路径，SAF 分支的这次改动的实际效果仍未被观测。需要在**只授单目录、不授全部文件访问**的机型上回归一次。
3. **归档与 PDF 只被识别为章节，没有页清单**：`ARCHIVE_IMPORT` 的封面与内部 ComicInfo 读取仍未实现（框架第 9 节已声明）。
4. **封面补全只覆盖图片目录章节**，且没有缩略图缓存；万级图库滚动时的内存与磁盘策略未评估。
5. **分页仍是 `LIMIT/OFFSET`**，不是开发文档 6.4 的会话顺序表，扫描中翻页需要靠 UI 去重兜住。
6. **搜索只做子串匹配**，没有 2-gram 侧表；万级库的搜索性能未验证。
7. **`.scratch/` 中的真机排障产物（截图、拉取的数据库）不入库**；诊断探针已全部清理。
8. **`AllFilesAccessActivity` 与图库路径页的"为什么需要"入口只在 Android 11+ 显示**，未在 Android 10 及以下回归。
9. **超长运行的真机扫描内存表现未测量**：762 部/28543 章的中间结果说明写入路径可用，但峰值内存、扫描总时长、封面解码并发都需要按开发文档 16 的性能目标做基准测试。

### 交接约定

- 契约以 [框架实现说明](./框架实现说明.md) 为准；本轮对它的三处偏离（`ScanResult` 归属、`openChild` 签名、`scan(factory=…)` 参数）已同步回该文档，后续改签名必须先改文档。
- 真机排障的通用教训：**同时保留"结论"与"原因"两条信息**。本轮多次因为只显示结论（"0 部漫画"）而无法判断根因；现在失败原因、访问方式、访问目录数都可在界面内查看，不依赖会被系统过滤的 logcat。
- 依赖版本以 `gradle/libs.versions.toml` 为唯一来源，且与本机已验证的 LM-translator 构建栈一致；升级需整组评估。
