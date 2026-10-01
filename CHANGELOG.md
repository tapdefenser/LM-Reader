# Changelog / 更新记录

## v0.1.0 — release candidate / 发布候选

This is the first test-release candidate; it is not a published signed release.
这是首个测试版候选，目前尚未发布签名发行版。

- Local library/shelf, reading progress, image-folder/ZIP/CBZ/PDF reading.
- Local Seg/OCR, offline translation packs and configurable API workflows.
- Bubble overlays/edits, glossary, Cat-paw templates/editor/import/export.
- Translation/export queues, foreground notifications, pause/cancel/retry.
- PNG/JPEG/CBZ export snapshots, validation and temporary-file recovery.
- Backups with empty API keys, checked restore/rollback and startup recovery.
- About page with app version, GitHub project link and stable-release update checks.
- Simplified Chinese/English UI: system-following uses English for every non-Simplified-Chinese primary language.
- Release candidate version `0.1.0`, code `2`, only arm64-v8a/x86_64; reproducible preparation and packaging instructions.

翻译与导出任务完成后自动出队，保留译文、章节完成状态与导出产物；启动清理旧完成任务，失败、中断与暂停记录仍可恢复。

Completed translation/export tasks leave their queues automatically while translations, chapter status and published files are retained. Startup reconciles old completed tasks; failed, interrupted and paused records remain recoverable.

对应：本地图库/书架/进度与四格式阅读，本地/API 翻译，气泡编辑与字典，猫爪工作流，任务通知及队列，三格式导出和临时恢复，备份回滚，以及修正后的中英语言回退。详细边界见[中英发行说明](docs/releases/v0.1.0.md)。
