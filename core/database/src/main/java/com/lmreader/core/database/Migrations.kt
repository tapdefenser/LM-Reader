package com.lmreader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移。
 *
 * 为什么哪怕在开发期也写迁移而不是 `fallbackToDestructiveMigration`：开发文档
 * 15.4 要求"数据库迁移必须可测试"，而破坏性迁移会静默删掉书架、阅读进度与
 * 译名字典——这些是用户无法重建的数据（开发文档 2「书架、进度、译名、人工修订
 * 不属于可随意清理的缓存」）。开发期用真机测试时同样会丢数据，因此规矩从第一天
 * 就成立。
 */
object Migrations {

    /**
     * v1 → v2：`library_sources` 增加用户可编辑的显示名称。
     *
     * 加可空列是唯一安全的做法：已有行没有名字，界面回退到 `displayPath`，
     * 不需要回填也不需要重算任何派生字段。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE library_sources ADD COLUMN displayName TEXT")
        }
    }

    /**
     * v2 → v3：`mangas` 增加漫画级阅读覆盖（阅读模式与屏幕方向）。
     *
     * 加**可空**列是唯一安全的做法：已有行没有覆盖，读取时回退全局默认，因此不需要
     * 回填、也不需要重算任何派生字段。存枚举名称而不是序数——序数会在枚举增删或重排后
     * 悄悄指向另一个值，而这类错误没有任何报错。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE mangas ADD COLUMN readerModeOverride TEXT")
            db.execSQL("ALTER TABLE mangas ADD COLUMN readerOrientationOverride TEXT")
        }
    }

    /**
     * v3 → v4：封面改为「图库滚动时懒加载」，并让简介补全队列能够前进。
     *
     * 四件事，都必须在同一次迁移里做完：
     * 1. 加**可空**列 `coverProbedAt`：已有行没有探测记录，读取时按"从未探测"处理；
     * 2. 加**可空**列 `metadataProbedAt`：同上；
     * 3. 已经有封面的行直接标记为已探测（`coverProbedAt = updatedAt`）——否则用户升级后
     *    第一次滚动会把几千张已经有封面的卡片全部重新枚举一遍目录，白白卡住图库；
     * 4. 校正 `mangas.sourceOrderIndex` 与 `library_sources.orderIndex` 的分叉。
     *    真机快照里 `/Tachiyomi/downloads` 的 763 行同时存在 1 与 4，而排序键就是这一列，
     *    于是那批卡片会排到别的来源前面。历史分叉只能在迁移里修一次，之后再靠写入方保持。
     *
     * `metadataProbedAt` **刻意不回填**：历史数据里没有任何"读过了但没有 XML"的记录，
     * 唯一可靠的结论是"不知道"。让它们各被读一次（每轮 120 条，读一次就标记），
     * 比继续无限重读同一批要好。
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE mangas ADD COLUMN coverProbedAt INTEGER")
            db.execSQL("ALTER TABLE mangas ADD COLUMN metadataProbedAt INTEGER")
            db.execSQL(
                "UPDATE mangas SET coverProbedAt = updatedAt WHERE coverDocumentId IS NOT NULL",
            )
            db.execSQL(
                """
                UPDATE mangas
                SET sourceOrderIndex = COALESCE(
                    (SELECT s.orderIndex FROM library_sources AS s WHERE s.sourceId = mangas.sourceId),
                    sourceOrderIndex
                )
                WHERE EXISTS (SELECT 1 FROM library_sources AS s WHERE s.sourceId = mangas.sourceId)
                """.trimIndent(),
            )
        }
    }

    /**
     * v4 → v5：把被"读不到 ComicInfo"缺陷误标成「已探测」的漫画重新排进补全队列。
     *
     * 背景（详见 `MetadataBackfillWorker` 的类注释）：补全阶段曾用
     * `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)` 直接拼
     * SAF URI，而在「全部文件访问」下 documentId 是绝对路径、提供方要的是
     * `primary:…`，两者拼出的 URI **查不到任何子项**。结果是每一部漫画都走进
     * 「没有 XML」分支：写下了 `metadataProbedAt`（过程），却没写 `hasMetadata`
     * 与 `summary`/`author`（结果）——真机上 5408 部里 5408 部都是这个状态，
     * 详情页因此永远显示"作者：未知 / 无简介"。
     *
     * 光修代码不够：`pendingBackfillIds` 要求 `metadataProbedAt IS NULL`，
     * 而重扫时 `upsertScanResult` 又会**保留**已有的 `metadataProbedAt`，
     * 于是这些行再也不会被读第二次。这里把它们退回"从未探测"，
     * 由补全按每批 120 条重新读一遍（现在能读到了）。
     *
     * 只清 `hasMetadata = 0` 的行：读到过 XML 的行结果是可信的，
     * 没有理由让它们再被读一次。清掉的是**过程**列，不影响任何用户数据
     * （书架、阅读进度、译名都不在这张表里）。
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE mangas SET metadataProbedAt = NULL WHERE hasMetadata = 0")
        }
    }

    /**
     * v5 → v6：章节的**显示顺序**（用户可排序、可手动拖动）与**按章已读标记**。
     *
     * 四件事：
     * 1. 加 `position`：详情页章节列表唯一的排序依据。排序抽屉整表重排、
     *    手动拖动、以及扫描发现新章节时的插入都写它；
     * 2. 加 `modifiedAt`：排序抽屉的「按修改时间排序」要用。**不复用
     *    `contentRevision`** —— 那一列是"内容是否变化"的判据，没有 mtime 时会退化成
     *    常量，拿它排序会让一堆章节并列；
     * 3. 回填两列，让升级后的列表**看起来和升级前一模一样**：
     *    `position` 按既有的自然序（`sortKey`）逐部漫画编号，
     *    `modifiedAt` 用 `contentRevision` 近似（早先它就是拿 mtime 填的，
     *    退回常量的那些行不填，等下一次扫描写真实值）；
     * 4. 新建 `chapter_read_state`：按章的已读标记。不回填——`reading_progress.read`
     *    描述的是"当前那一章"，把它当成每一章的已读会凭空多出一堆已读。
     *
     * 为什么 `sortKey` 留着不动：封面（开发文档 7.2）与简介（7.1 第 2 条）取的是
     * **自然序第一章**，不能因为用户手动拖过章节就换一章。两种顺序必须各有一列。
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chapters ADD COLUMN modifiedAt INTEGER")
            // 按 (mangaId, sortKey, chapterId) 的既有顺序编号。子查询只扫同一部漫画的
            // 章节，走 (mangaId, sortKey) 索引，因此是每行一次小区间扫描而不是全表平方。
            db.execSQL(
                """
                UPDATE chapters
                SET position = (
                    SELECT COUNT(*) FROM chapters AS c2
                    WHERE c2.mangaId = chapters.mangaId
                      AND (
                        c2.sortKey < chapters.sortKey
                        OR (c2.sortKey = chapters.sortKey AND c2.chapterId < chapters.chapterId)
                      )
                )
                """.trimIndent(),
            )
            // contentRevision 早先是 `node.lastModified ?: 常量`，因此 >1 的值就是 mtime。
            db.execSQL("UPDATE chapters SET modifiedAt = contentRevision WHERE contentRevision > 1")
            // 详情页按 (mangaId, position) 取章节；不建索引时每次进详情页都要排序。
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_mangaId_position ON chapters (mangaId, position)")

            // 4. 按章的已读标记（章节多选底栏的「标记已读/未读」）。
            //    与 reading_progress 分开：那张表一部漫画一行，"已读"只描述当前那一章。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chapter_read_state (
                    chapterId TEXT NOT NULL,
                    mangaId TEXT NOT NULL,
                    read INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(chapterId),
                    FOREIGN KEY(chapterId) REFERENCES chapters(chapterId) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chapter_read_state_mangaId " +
                    "ON chapter_read_state (mangaId)",
            )
        }
    }

    /**
     * v6 → v7：翻译数据（阶段 2）。三块，都是**加东西**，不动既有数据：
     *
     * 1. `mangas` 加五列：漫画级源/目标语言、自动识别开关、漫画自己的文风。
     *    全部可空或带默认 —— "没设置"必须与"设置成默认值"区分开，否则漫画这一层
     *    会把分类与全局挡住（文风是**覆盖**关系，见 `MangaTranslationSettings`）；
     * 2. `chapter_translation`：一章 × 一种目标语言的翻译记录，主键
     *    `(chapterId, targetLanguage)`。**不回填**：升级前没有任何翻译记录，
     *    凭空写一堆"未翻译"行只会让表变大；
     * 3. `manga_glossary`：漫画译名字典（TR09），主键 `(mangaId, targetLanguage, source)`。
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE mangas ADD COLUMN translationSourceLanguage TEXT")
            db.execSQL("ALTER TABLE mangas ADD COLUMN translationAutoDetectSource INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE mangas ADD COLUMN translationTargetLanguage TEXT")
            db.execSQL("ALTER TABLE mangas ADD COLUMN translationStyleMode TEXT")
            db.execSQL("ALTER TABLE mangas ADD COLUMN translationCustomStyle TEXT")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chapter_translation (
                    chapterId TEXT NOT NULL,
                    mangaId TEXT NOT NULL,
                    targetLanguage TEXT NOT NULL,
                    state TEXT NOT NULL,
                    sourceLanguage TEXT,
                    autoDetectSource INTEGER NOT NULL,
                    configSnapshot TEXT,
                    queuedAt INTEGER,
                    translatedAt INTEGER,
                    translatedCount INTEGER NOT NULL,
                    failure TEXT,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(chapterId, targetLanguage),
                    FOREIGN KEY(chapterId) REFERENCES chapters(chapterId) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chapter_translation_mangaId_state " +
                    "ON chapter_translation (mangaId, state)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS manga_glossary (
                    mangaId TEXT NOT NULL,
                    targetLanguage TEXT NOT NULL,
                    source TEXT NOT NULL,
                    target TEXT NOT NULL,
                    manual INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(mangaId, targetLanguage, source),
                    FOREIGN KEY(mangaId) REFERENCES mangas(mangaId) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_manga_glossary_mangaId " +
                    "ON manga_glossary (mangaId)",
            )
        }
    }

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
