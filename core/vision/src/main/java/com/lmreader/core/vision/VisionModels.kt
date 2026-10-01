package com.lmreader.core.vision

import android.content.Context
import com.lmreader.core.model.LocalVisionException
import java.io.File
import java.security.MessageDigest

internal class VisionModels(context: Context) {
    private val context = context.applicationContext
    val nativeLibraryDirectory: String get() = context.applicationInfo.nativeLibraryDir
    fun profilePrefix(): String = File(context.cacheDir, "ocr-profiles").apply { mkdirs() }
        .resolve("ocr-${System.nanoTime()}").absolutePath
    private val verified = mutableSetOf<String>()
    fun asset(name: String): String {
        val path = "vision/models/$name"
        if (name !in verified) {
            try {
                context.assets.open(path).use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    var n = stream.read(buffer)
                    while (n >= 0) { if (n > 0) digest.update(buffer,0,n); n = stream.read(buffer) }
                    check(digest.digest().hex() == hashes.getValue(name)) { "模型校验失败：$name" }
                }
                verified += name
            } catch (failure: Exception) {
                throw LocalVisionException("本地模型缺失或损坏：$name。构建前请运行 tools/fetch_vision_models.py。",failure)
            }
        }
        return path
    }
    fun file(name: String): File = synchronized(cacheLock) {
        val asset = asset(name)
        val folder = File(context.noBackupFilesDir,"vision-models").apply { mkdirs() }
        val file = File(folder, "${hashes.getValue(name)}-$name")
        if (file.isFile && file.inputStream().use { digest(it) } == hashes.getValue(name)) return@synchronized file
        val temporary = File(folder,"$name.part")
        try {
            context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.inputStream().use { digest(it) } == hashes.getValue(name))
            check(temporary.renameTo(file)) { "模型缓存保存失败" }
        } finally { temporary.delete() }
        file
    }
    fun characters(name: String): List<String> = context.assets.open(asset(name)).bufferedReader(Charsets.UTF_8).use {
        val lines = it.readLines()
        require(lines.isNotEmpty() && lines.none(String::isEmpty)) { "字符字典为空或包含空行" }
        listOf("") + lines + if (lines.last() == " ") emptyList() else listOf(" ")
    }
    companion object {
        private val cacheLock = Any()
        val hashes = mapOf(
            "seg.tflite" to "d030134cb00fb178a6433a8f2cfe5680c0bf34365ec64e0f6e5d0f28ec252c76",
            "det.onnx" to "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            "rec.onnx" to "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
            "ko.onnx" to "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
            "rec.txt" to "769e7fa79bb297b5f18d8dbd149e364a45bc61f2b3f574e5ea836f0b261c23a6",
            "ko.txt" to "2193f5dd0c62a4f268902b5d96dadfec3908d299afa9a6e5cad2c6a96c772828")
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun digest(input: java.io.InputStream): String {
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            var n = input.read(buffer)
            while (n >= 0) { if (n > 0) hash.update(buffer,0,n); n = input.read(buffer) }
            return hash.digest().hex()
        }
    }
}
