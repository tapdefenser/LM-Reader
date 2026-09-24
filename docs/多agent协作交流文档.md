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
