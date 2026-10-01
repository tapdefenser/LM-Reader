# Backup and task recovery

[中文实现与验证记录](BACKUP.zh-CN.md) · [User guide](USAGE.en.md)

## Format and scope

Version-1 ZIP archives contain a manifest with permitted file lengths and SHA-256. Restoration rejects unknown versions/files, duplicates, traversal, missing/changed entries and excessive sizes: 64 MB per entry, 512 MB total, 100,000 entries. Logical database snapshots use fixed tables/columns and require schema 11.

Included: app/reader preferences, library index, shelf, progress/read status, glossary, translation records, page translations/edits, workflows, API profiles without keys, model-source settings and translation-queue order/excluded pages. Keys in task snapshots are also blank.

Excluded: original comics, models, request logs, outputs, export queue and staging snapshots. Android backup/device transfer exclude export journals because they depend on private snapshots in the same installation. Directory references are hints, not transferable permissions.

## Restoration

The entire package is validated before replacement. Queues, single-page tasks and scans are stopped/awaited. A full local rollback copy is synced before a journal pointer appears. Room transactions replace database rows; live DataStore/SharedPreferences restore preferences; checked atomic writes replace page/workflow files.

Failure reapplies the local copy. Interrupted restoration runs before normal startup. Failed rollback preserves the copy and blocks the current session and subsequent startup. Restored queues stay paused; unfinished translations become interrupted, sources require reauthorization, and export destinations are cleared with old hints retained. Reenter keys/install models, then resume manually. Reopen for restored language preferences to take effect.

## Completed tasks

Completed translations leave the queue while chapter completion metadata and saved translations remain. Completed exports leave after publication recovery and staging cleanup; published files remain at the destination. Startup also removes old completed tasks. Failed, interrupted and paused tasks remain available for retry or resume. Cleanup or queue-save failures retain the receipt and error for recovery.

## Tasks and export

Translation/export runners and single-page translation need a foreground-service acknowledgement. Leases cover writing/cleanup. Notifications show counts/progress, open the queue and pause tasks. Denied notification permission hides drawer progress. The service uses START_NOT_STICKY and a bounded partial wake lock. Android [foreground-service time limits](https://developer.android.com/develop/background-work/services/fgs/timeout) still apply.

Backgrounding/locking does not actively cancel chapter queues. Translation pause retains completed results; whole-comic responses can take time. Export pause preserves snapshots. Process death reconciles running records to interrupted; retry manually. Intermediate workflow variables are not checkpointed.

A UUID staging directory freezes originals, translation/render settings and page order. Rendered pages have hash receipts. Publication journals a deterministic `.lmreader-<UUID>.part` before creation, saves its URI, reopens/verifies copied bytes, commits READY, renames, then commits DONE/final URI. Journal writes check sync/rename failure.

Startup recovers creation-before-URI and rename-before-DONE windows. Only owned temporary outputs are cleaned. Cleanup failure preserves tracking/error; corrupt queue JSON is preserved without overwriting it. Retry may adopt a new destination after old publication cleanup. Staging consumes private storage, with a 2 GB limit each for original/output chapter data.

## Evidence and limits

Earlier, 14 reliability device tests passed on MuMu Android 15: backups/rollback/journals, three output formats through a real DocumentsContract test provider, write/rename/permission failures, publication windows, source changes, cleanup/save failures, special document IDs and service-controller boundaries. Interrupted windows use persistent-state injection; service tests inject the startup boundary.

This does not establish ARM long-running behavior, vendor policies, real SD/cloud providers, physical full-disk behavior or system kills at every instruction. Cross-schema migration, arbitrary workflow checkpoints and more output formats remain outside the current scope. The [release record](RELEASING.en.md) tracks the candidate build/language checks.

The completed-task dequeue regression cases compiled in this revision. Device tests were not rerun after the emulator was closed.
