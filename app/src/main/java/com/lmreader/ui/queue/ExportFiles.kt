package com.lmreader.ui.queue

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Only committed files can be reused after a process interruption. */
object ExportFiles {
    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun commit(file: File, write: (FileOutputStream) -> Unit) {
        val part = File(file.parentFile, file.name + ".part")
        try {
            FileOutputStream(part).use { write(it); it.fd.sync() }
            check(part.renameTo(file)) { "无法提交导出缓存文件" }
        } finally { part.delete() }
    }
    fun intact(file: File): Boolean {
        val receipt = File(file.parentFile, file.name + ".sha256")
        return file.isFile && receipt.isFile && receipt.readText() == hash(file)
    }
    fun receipt(file: File) = commit(File(file.parentFile, file.name + ".sha256")) {
        it.write(hash(file).toByteArray(Charsets.UTF_8))
    }
    fun ownedRoot(parent: File, id: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "无效的导出任务编号" }
        return File(parent, id).also { require(it.canonicalFile.parentFile == parent.canonicalFile) }
    }
}
