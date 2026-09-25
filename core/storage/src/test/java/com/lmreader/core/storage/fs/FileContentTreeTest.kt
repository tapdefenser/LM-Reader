package com.lmreader.core.storage.fs

import com.lmreader.core.model.ContentTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [FileContentTree] 的真实文件系统验证。
 *
 * 为什么放在 storage 模块的单元测试里而不是只靠真机：这个类不依赖 Android API
 * （只用 `java.io.File`），因此可以在普通 JVM 上直接验证"能不能列出子目录、
 * 能不能判断叶子章节"。真机排障时正是这一段最需要被证明是对的。
 */
class FileContentTreeTest {

    @Test
    fun `列出子项并区分目录与图片`() = runBlocking {
        val root = Files.createTempDirectory("lmreader-fs").toFile()
        try {
            File(root, "第一章").mkdirs()
            File(root, "第一章/001.jpg").writeText("x")
            File(root, "第一章/002.jpg").writeText("x")
            File(root, "封面.png").writeText("x")

            val tree = FileContentTree(root, "作品")
            val children = tree.listChildren()

            val directories = children.filter { it.isDirectory }.map { it.name }
            // 只统计**直接**子项：两张 jpg 在「第一章」里，不属于根的直接文件。
            val images = children.filter { !it.isDirectory }.map { it.name }
            assertTrue("应识别出一个子目录，实际=$directories", directories == listOf("第一章"))
            assertTrue("应识别出根下的直接图片，实际=$images", images == listOf("封面.png"))
            assertTrue("子目录不是文件", children.none { it.isDirectory && it.mimeType?.startsWith("image/") == true })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `叶子章节判定与递归下钻可用`() = runBlocking {
        val root = Files.createTempDirectory("lmreader-fs").toFile()
        try {
            File(root, "作品/第一章").mkdirs()
            File(root, "作品/第一章/001.jpg").writeText("x")
            File(root, "作品/第二章").mkdirs()
            File(root, "作品/第二章/001.jpg").writeText("x")

            val rootTree = FileContentTree(root, "库")
            val mangaNode = rootTree.listChildren().single { it.isDirectory }
            assertTrue("根应看到子目录「作品」", mangaNode.name == "作品")

            // 关键：用与扫描器相同的方式下钻
            val mangaTree = rootTree.openChild(mangaNode)
            assertTrue("子目录必须能打开（真机故障点就在这里）", mangaTree != null)
            assertTrue("作品目录含子目录", mangaTree!!.hasDirectoryChildren())

            // 作品下有第一章、第二章两个章节目录，按名称取第一个即可。
            val chapterNode = mangaTree.listChildren()
                .filter { it.isDirectory }
                .minByOrNull { it.name }
            assertTrue("应找到章节目录", chapterNode != null)
            val chapterTree = mangaTree.openChild(chapterNode!!)
            assertTrue("章节目录必须能打开", chapterTree != null)
            assertTrue("章节是叶子（没有子目录）", !chapterTree!!.hasDirectoryChildren())
            assertTrue("章节含图片", chapterTree.hasImageChild())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `documentId 是绝对路径且可再次打开`() = runBlocking {
        val root = Files.createTempDirectory("lmreader-fs").toFile()
        try {
            File(root, "作品").mkdirs()
            val tree: ContentTree = FileContentTree(root, "库")
            val child = tree.listChildren().single()
            assertTrue(
                "documentId 必须是绝对路径，实际=${child.documentId}",
                File(child.documentId).isAbsolute,
            )
            assertTrue("按 documentId 能重新打开目录", File(child.documentId).isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `真实漫画库结构可被发现`() = runBlocking {
        // 可选：在本机存在同名结构时验证一次（CI/其他机器上自动跳过）。
        val sample = File("/storage/emulated/0/Tachiyomi/downloads")
        assumeTrue("本机没有该示例目录，跳过", sample.isDirectory)
        val tree = FileContentTree(sample, "downloads")
        assertTrue("授权根应有子项", tree.listChildren().isNotEmpty())
    }
}
