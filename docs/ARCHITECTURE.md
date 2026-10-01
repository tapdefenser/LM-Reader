# Architecture / 应用架构

[Documentation / 文档](README.en.md)

The Android host uses Kotlin and Jetpack Compose. Core modules separate domain types, persistence, indexing, I/O, API calls, native inference and workflow execution. 应用宿主负责界面与任务装配，核心模块负责数据与执行边界。

| Module | Responsibility / 职责 |
|---|---|
| `app` | Navigation, reader, bubble editing, queues, foreground notifications, export and backups / 界面、阅读器、任务、导出与备份 |
| `core:model` | Domain types and repository contracts / 领域类型与仓库接口 |
| `core:database` | Room entities, DAOs and migrations, currently schema 11 / 持久化数据及迁移 |
| `core:storage` | SAF/file access, page sources, preferences and protected API profiles / 文件与目录访问、页面和设置 |
| `core:index` | Comic/chapter structure scans, ordering and metadata / 图库索引与结构扫描 |
| `core:api` | Provider requests, typed responses and logs / API 请求、结构化响应与日志 |
| `core:vision` | Local Seg/OCR inference and pinned model assets / 本地分割与 OCR |
| `core:translation` | Offline translation engine, pack catalog/install/import / 离线机翻与语言包管理 |
| `core:workflow` | Typed programs, scopes, templates and execution / 类型化工作流与执行器 |

## Data flow / 数据流

```text
Authorized folder → index → library/shelf → chapter page source → reader
                                   ↓
                         workflow + engine/API
                                   ↓
                       saved bubbles/translations
                                   ↓
                         reader overlay / export
```

Original comics remain in user-selected folders. The database stores indexes, shelf/progress, glossary and translation records. API keys use the device Keystore. Model downloads and inference caches use application-private storage. 原始漫画不随备份复制，目录授权不能跨设备迁移。

## Persistence and task boundaries / 持久化边界

- Follow system is stored as a null language preference and resolved through one `AppLanguage` policy. Default Android resources are English; Simplified Chinese uses `values-b+zh+Hans`.
- Translation jobs use workflow/configuration snapshots. Process termination reconciles running work as interrupted, for manual retry; intermediate workflow variables are not checkpointed.
- Export uses fixed page/translation snapshots, verified staged outputs and a publishing journal. Startup reconciliation supports temporary-file recovery and retry.
- Restoration validates schema 11 backups and keeps a local rollback copy before replacement. API keys, original comics, models, logs and export queues are excluded by default.

Details: [workflow guide](WORKFLOWS.en.md), [backup/recovery](BACKUP.en.md), [model protocol](MODEL_PACKS.md), [release preparation](RELEASING.en.md).
