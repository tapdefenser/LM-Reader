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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
