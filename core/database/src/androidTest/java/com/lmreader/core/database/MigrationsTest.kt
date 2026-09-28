package com.lmreader.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移测试（开发文档 15.4「数据库迁移必须可测试」）。
 *
 * ## 为什么 v5→v6 特别需要它
 *
 * 那次迁移里有一段**裸 SQL**：按 `(mangaId, sortKey, chapterId)` 给每章回填 `position`。
 * Room 校验不到这段 SQL，而它一旦写错，**每个用户升级后章节顺序都会乱**——
 * 不是崩溃，是"看起来正常但与升级前不一样"，最难被发现的那类错误。
 *
 * `runMigrationsAndValidate` 还顺带验证手写的 `CREATE TABLE chapter_read_state`
 * 与 Room 期望的 schema 完全一致（列序、NOT NULL、主键、外键、索引名）。
 * 不一致时 Room 在真机上会抛 "Migration didn't properly handle ..." —— 那时用户
 * 已经升级失败了，所以必须在这里挡住。
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LmReaderDatabase::class.java,
    )

    @Test
    fun `v5到v6按既有自然序回填章节位置并保留升级前的顺序`() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, " +
                    "displayName, sortKey, sourceOrderIndex, author, hasMetadata, summary, " +
                    "coverDocumentId, coverChapterId, chapterCount, chapterCountKnown, availability, " +
                    "discoveryGeneration, discoveredAt, updatedAt) " +
                    "VALUES ('m1', '/lib/A', 's1', 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'A', 'a', 0, " +
                    "NULL, 0, NULL, NULL, NULL, 3, 1, 'AVAILABLE', 1, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, " +
                    "displayName, sortKey, sourceOrderIndex, author, hasMetadata, summary, " +
                    "coverDocumentId, coverChapterId, chapterCount, chapterCountKnown, availability, " +
                    "discoveryGeneration, discoveredAt, updatedAt) " +
                    "VALUES ('m2', '/lib/B', 's1', 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'B', 'b', 1, " +
                    "NULL, 0, NULL, NULL, NULL, 2, 1, 'AVAILABLE', 1, 0, 0)",
            )

            // m1 的三章故意**乱序插入**：位置必须按 sortKey 算出来，而不是按插入顺序。
            // sortKey 形如 "第\x01NNNN话"（NaturalOrder 的预计算列）。
            insertChapter(db, "c_m1_3", "m1", "/lib/A/第3话", "第3话", "第\u00010003话", contentRevision = 500L)
            insertChapter(db, "c_m1_1", "m1", "/lib/A/第1话", "第1话", "第\u00010001话", contentRevision = 1L)
            insertChapter(db, "c_m1_2", "m1", "/lib/A/第2话", "第2话", "第\u00010002话", contentRevision = 300L)
            // m2 的两章也要各自从 0 开始编号（位置是**按漫画**的，不是全局）。
            insertChapter(db, "c_m2_1", "m2", "/lib/B/第1话", "第1话", "第\u00010001话", contentRevision = 1L)
            insertChapter(db, "c_m2_2", "m2", "/lib/B/第2话", "第2话", "第\u00010002话", contentRevision = 900L)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        // 位置：按 sortKey 升序逐部编号，升级前后顺序完全一致。
        assertEquals(0L, positionOf(db, "c_m1_1"))
        assertEquals(1L, positionOf(db, "c_m1_2"))
        assertEquals(2L, positionOf(db, "c_m1_3"))
        assertEquals(0L, positionOf(db, "c_m2_1"))
        assertEquals(1L, positionOf(db, "c_m2_2"))

        // modifiedAt：contentRevision > 1 的那些行就是当年的 mtime，回填；常量 1 的不填
        // （等下一次扫描写真实值），否则「按修改时间排序」会看到一堆并列的 1。
        assertEquals(500L, modifiedAtOf(db, "c_m1_3"))
        assertEquals(300L, modifiedAtOf(db, "c_m1_2"))
        assertEquals(900L, modifiedAtOf(db, "c_m2_2"))
        assertNull(modifiedAtOf(db, "c_m1_1"))
        assertNull(modifiedAtOf(db, "c_m2_1"))

        // 已读标记表建好了，但**不回填**：reading_progress.read 说的是"当前那一章"，
        // 当成每一章的已读会凭空多出一堆已读。
        db.query("SELECT COUNT(*) FROM chapter_read_state").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    fun `v5到v6之后详情页按显示顺序取章节`() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, " +
                    "displayName, sortKey, sourceOrderIndex, author, hasMetadata, summary, " +
                    "coverDocumentId, coverChapterId, chapterCount, chapterCountKnown, availability, " +
                    "discoveryGeneration, discoveredAt, updatedAt) " +
                    "VALUES ('m1', '/lib/A', 's1', 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'A', 'a', 0, " +
                    "NULL, 0, NULL, NULL, NULL, 2, 1, 'AVAILABLE', 1, 0, 0)",
            )
            insertChapter(db, "c_2", "m1", "/lib/A/第10话", "第10话", "第\u00010010话", contentRevision = 1L)
            insertChapter(db, "c_1", "m1", "/lib/A/第2话", "第2话", "第\u00010002话", contentRevision = 1L)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        val titles = mutableListOf<String>()
        db.query("SELECT title FROM chapters WHERE mangaId = 'm1' ORDER BY position ASC, sortKey ASC, chapterId ASC")
            .use { cursor ->
                while (cursor.moveToNext()) titles += cursor.getString(0)
            }
        // 自然序（第2话 在 第10话 之前）而不是插入顺序。
        assertEquals(EXPECTED_ORDER, titles)

        db.close()
    }

    private fun insertChapter(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        chapterId: String,
        mangaId: String,
        documentId: String,
        title: String,
        sortKey: String,
        contentRevision: Long,
    ) {
        db.execSQL(
            "INSERT INTO chapters (chapterId, mangaId, documentId, kind, title, sortKey, " +
                "pageCount, coverDocumentId, contentRevision, discoveredAt) " +
                "VALUES (?, ?, ?, 'IMAGE_DIRECTORY', ?, ?, NULL, NULL, ?, 0)",
            arrayOf<Any?>(chapterId, mangaId, documentId, title, sortKey, contentRevision),
        )
    }

    private fun positionOf(db: androidx.sqlite.db.SupportSQLiteDatabase, chapterId: String): Long =
        queryLong(db, "SELECT position FROM chapters WHERE chapterId = '$chapterId'")

    private fun modifiedAtOf(db: androidx.sqlite.db.SupportSQLiteDatabase, chapterId: String): Long? {
        db.query("SELECT modifiedAt FROM chapters WHERE chapterId = '$chapterId'").use { cursor ->
            if (!cursor.moveToFirst()) return null
            return if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    private fun queryLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** 自然序下"第2话"应排在"第10话"之前。 */
        val EXPECTED_ORDER = listOf("第2话", "第10话")
    }
}
