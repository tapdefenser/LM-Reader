package com.lmreader.ui.settings.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Table/column names always come from the app schema, never directly from a ZIP. */
object BackupDatabase {
    fun snapshot(db: SupportSQLiteDatabase, root: File, secrets: Boolean) {
        File(root, "database").mkdirs()
        for (table in BackupArchive.tables) {
            val rows = JSONArray()
            val columns = db.query("SELECT * FROM `$table`").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONArray()
                    for (index in 0 until cursor.columnCount) row.put(when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index).let { value ->
                            if (!secrets && table == "chapter_translation" && cursor.getColumnName(index) == "configSnapshot") redact(JSONObject(value)).toString() else value
                        }
                        else -> error("不支持的数据库列")
                    })
                    rows.put(row)
                }
                cursor.columnNames.toList()
            }
            val json = JSONObject().put("version", db.version).put("columns", JSONArray(columns)).put("rows", rows)
            File(root, "database/$table.json").writeText(json.toString())
        }
    }
    fun validate(db: SupportSQLiteDatabase, root: File) {
        for (table in BackupArchive.tables) {
            val data = JSONObject(File(root, "database/$table.json").readText())
            require(data.getInt("version") == db.version) { "数据库版本不同，请用相同版本或兼容版本恢复" }
            val columns = db.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() }
            val actual = data.getJSONArray("columns")
            require((0 until actual.length()).map { actual.getString(it) } == columns) { "备份数据库结构不匹配" }
            val rows = data.getJSONArray("rows")
            for (i in 0 until rows.length()) require(rows.getJSONArray(i).length() == columns.size)
        }
    }
    fun restore(db: SupportSQLiteDatabase, root: File, restored: Boolean) {
        for (table in BackupArchive.tables.asReversed()) db.execSQL("DELETE FROM `$table`")
        for (table in BackupArchive.tables) {
            val data = JSONObject(File(root, "database/$table.json").readText())
            val columns = db.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() }
            val rows = data.getJSONArray("rows")
            val sql = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                db.execSQL(sql, Array<Any?>(columns.size) { index -> row.get(index).takeUnless { it == JSONObject.NULL } })
            }
        }
        if (restored) {
            db.execSQL("DELETE FROM directory_snapshots")
            db.execSQL("DELETE FROM scan_runs")
            db.execSQL("UPDATE library_sources SET permission = 'LOST', lastScanStatus = NULL, lastScanError = '从备份恢复，请重新授权原目录'")
            db.execSQL("UPDATE chapter_translation SET state = 'INTERRUPTED', failure = '从备份恢复，请检查路径与 API 凭据后手动重试' WHERE state IN ('PENDING','RUNNING','PAUSED')")
        }
        db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "备份数据关系损坏" } }
        db.query("SELECT 1 FROM shelf_entries LEFT JOIN categories USING(categoryId) WHERE categories.categoryId IS NULL LIMIT 1").use { require(!it.moveToFirst()) { "书架分类关系损坏" } }
        db.query("SELECT COUNT(*) FROM categories WHERE categoryId = 0").use { require(it.moveToFirst() && it.getInt(0) == 1) }
    }
    fun redact(value: Any): Any {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                if (key.equals("apiKey", true)) value.put(key, "") else value.put(key, redact(value.get(key)))
            }
            is JSONArray -> for (i in 0 until value.length()) value.put(i, redact(value.get(i)))
        }
        return value
    }
}
