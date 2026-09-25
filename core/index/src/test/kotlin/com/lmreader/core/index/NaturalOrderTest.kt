package com.lmreader.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开发文档 1.3「首字母排序」：默认文件名自然升序、不区分大小写、数字按数值比较。
 *
 * 这些是文件/章节排序的直接验收点（验收 A12「首章名 1/2/10，页 1/2/10」）。
 */
class NaturalOrderTest {

    @Test
    fun 数字段按数值比较而不是字典序() {
        assertLessThan("1.jpg", "2.jpg")
        assertLessThan("2.jpg", "10.jpg")
        assertLessThan("1.jpg", "10.jpg")
        assertLessThan("9.jpg", "10.jpg")
        assertLessThan("第2话", "第10话")
        assertLessThan("第1卷", "第2卷")
        assertLessThan("page9", "page10")
        assertLessThan("page099", "page100")
    }

    @Test
    fun 不区分大小写() {
        assertEquals(0, NaturalOrder.compare("A.jpg", "a.jpg"))
        assertEquals(0, NaturalOrder.compare("Chapter.PNG", "chapter.png"))
        assertLessThan("file2", "FILE10")
        assertEquals(0, NaturalOrder.compare("第1話", "第1話"))
    }

    @Test
    fun 前导零不改变大小() {
        assertEquals(0, NaturalOrder.compare("001.jpg", "1.jpg"))
        assertEquals(0, NaturalOrder.compare("第01话", "第1话"))
        assertLessThan("007.jpg", "8.jpg")
    }

    @Test
    fun 中文数字按普通文本比较不做折算() {
        // 结构判定只依据目录层级，不能从名称推断语义（开发文档 5.1 末段），
        // 因此「一/二/十」只按码位比较，不试图换算成 1/2/10。
        assertLessThan("一", "二")
        assertLessThan("第一话", "第二话")
        assertTrue("十 (U+5341) 的码位大于 二 (U+4E8C)", NaturalOrder.compare("十", "二") > 0)
    }

    @Test
    fun 前缀短串在前且空串最小() {
        assertLessThan("a", "a1")
        assertLessThan("第1话", "第1话-番外")
        assertLessThan("", "a")
        assertEquals(0, NaturalOrder.compare("", ""))
    }

    @Test
    fun 数字与其它字符混排时按码位分界() {
        // 数字 '1' 的码位小于字母 'a'，与字典序一致，避免出现“图片夹在文件名中间”的反直觉顺序。
        assertLessThan("1a", "a1")
        assertLessThan("a1", "aA")
        assertLessThan("9", "a")
    }

    @Test
    fun 排序结果稳定且为全序() {
        val names = listOf("10.jpg", "2.jpg", "第10话", "第2话", "1.jpg", "A.jpg", "a.jpg")
        val sorted = names.sortedWith { left, right -> NaturalOrder.compare(left, right) }

        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "A.jpg", "a.jpg", "第2话", "第10话"), sorted)
    }

    @Test
    fun 与自身比较为零且满足反对称() {
        val samples = listOf("", "1.jpg", "10.jpg", "第2话", "abc", "A1", "第1话-番外")
        for (left in samples) {
            assertEquals(0, NaturalOrder.compare(left, left))
            for (right in samples) {
                assertEquals(
                    "反对称性：compare(a,b) 与 compare(b,a) 必须互为相反数（$left vs $right）",
                    -NaturalOrder.compare(left, right).coerceIn(-1, 1),
                    NaturalOrder.compare(right, left).coerceIn(-1, 1),
                )
            }
        }
    }

    @Test
    fun sortKey与compare给出相同顺序() {
        val names = listOf(
            "第10话", "第2话", "第1话", "10.jpg", "2.jpg", "1.jpg",
            "page9", "page10", "A.jpg", "a.jpg", "001.jpg", "1.jpg", "第1话-番外", "第1话",
        )
        val byCompare = names.sortedWith { left, right -> NaturalOrder.compare(left, right) }
        val bySortKey = names.sortedWith { left, right -> NaturalOrder.sortKey(left).compareTo(NaturalOrder.sortKey(right)) }

        assertEquals(
            "数据库只能做字典序比较，预计算列必须与内存比较结果给出同一顺序（框架 5.2）",
            byCompare.map { NaturalOrder.sortKey(it) },
            bySortKey.map { NaturalOrder.sortKey(it) },
        )
        assertEquals(byCompare.toSet(), bySortKey.toSet())
    }

    @Test
    fun sortKey只依赖内容且可比较() {
        assertTrue(NaturalOrder.sortKey("1.jpg") < NaturalOrder.sortKey("2.jpg"))
        assertTrue(NaturalOrder.sortKey("2.jpg") < NaturalOrder.sortKey("10.jpg"))
        assertEquals(NaturalOrder.sortKey("001.jpg"), NaturalOrder.sortKey("1.jpg"))
        assertEquals(NaturalOrder.sortKey("A.jpg"), NaturalOrder.sortKey("a.jpg"))
    }

    private fun assertLessThan(lower: String, higher: String) {
        assertTrue("「$lower」应排在「$higher」之前", NaturalOrder.compare(lower, higher) < 0)
        assertTrue("「$higher」应排在「$lower」之后", NaturalOrder.compare(higher, lower) > 0)
    }
}
