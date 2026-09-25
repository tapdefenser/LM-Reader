package com.lmreader.core.index

/**
 * 文件名自然升序、不区分大小写，数字按数值比较（开发文档 1.3「首字母排序」）。
 * 例：1.jpg < 2.jpg < 10.jpg；"第10话" > "第2话"。
 *
 * 不能用 [String.compareTo]：字典序把 "10.jpg" 排在 "2.jpg" 之前，而文件名里的
 * 卷/话/页编号几乎总是十进制数字。中文数字（一、二、十）按普通文本比较：
 * 目录层级与名称无法可靠推断语义，本对象不做中文数字折算（开发文档 5.1 末段）。
 *
 * 比较是**不区分大小写的全序**：`compare(a, b) == 0` 表示两者在排序上等价
 * （例如 "A.jpg" 与 "a.jpg"、"007" 与 "7"），因此可以直接作为 `sortedWith`
 * 的比较器使用，且对任意输入都满足自反、反对称与传递。
 */
object NaturalOrder {

    /**
     * 自然序比较：返回值 <0 / 0 / >0 分别表示 a 在 b 之前、等价、之后。
     *
     * 数字段按数值比较（忽略前导零）；其它字符按小写后的码位比较。
     */
    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val charA = a[i]
            val charB = b[j]
            if (charA.isAsciiDigit() && charB.isAsciiDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                // 忽略前导零后再比：否则 "007" 与 "7" 会因位数不同被误判为不等。
                val startA = significantStart(a, i, endA)
                val startB = significantStart(b, j, endB)
                val lengthA = endA - startA
                val lengthB = endB - startB
                if (lengthA != lengthB) return if (lengthA < lengthB) -1 else 1
                for (offset in 0 until lengthA) {
                    val digitA = a[startA + offset]
                    val digitB = b[startB + offset]
                    if (digitA != digitB) return if (digitA < digitB) -1 else 1
                }
                i = endA
                j = endB
                continue
            }
            val lowerA = charA.lowercaseChar()
            val lowerB = charB.lowercaseChar()
            if (lowerA != lowerB) return if (lowerA < lowerB) -1 else 1
            i++
            j++
        }
        // 一个串是另一个的前缀时，短的在前（"a" < "a1"），与自然序直觉一致。
        return (a.length - i).compareTo(b.length - j)
    }

    /**
     * 自然序键：把 [compare] 的序**编码进字符串本身**，供数据库预计算列排序
     * （框架 5.2：SQLite 只能做字典序比较，比较必须在写入时算好）。
     *
     * 编码规则：小写化；每个数字段写成「哨兵 + 3 位有效位数 + 去前导零的数字」。
     * 哨兵 `'\u0001'` 小于任何可打印字符，因此 "a" < "a1"。
     * 位数前缀让 "9" < "10"（`0019` < `00210`），去前导零让 "007" 与 "7" 同键。
     *
     * 已知限制：位数前缀按 3 位十进制，数字段超过 999 位时退化为不保证正确顺序；
     * 这类文件名不构成实际场景，但不得据此声称支持任意长度数字。
     */
    fun sortKey(value: String): String {
        val builder = StringBuilder(value.length + 8)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isAsciiDigit()) {
                val end = digitRunEnd(value, index)
                val start = significantStart(value, index, end)
                val digits = value.substring(start, end)
                builder.append(DIGIT_MARKER)
                builder.append(digits.length.coerceAtMost(MAX_DIGIT_WIDTH).toString().padStart(3, '0'))
                builder.append(digits)
                index = end
            } else {
                builder.append(char.lowercaseChar())
                index++
            }
        }
        return builder.toString()
    }

    private const val DIGIT_MARKER = '\u0001'
    private const val MAX_DIGIT_WIDTH = 999

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun digitRunEnd(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isAsciiDigit()) index++
        return index
    }

    /** 跳过前导零，但至少保留一位，使全零段（"000"）的有效位数为 1。 */
    private fun significantStart(text: String, from: Int, end: Int): Int {
        var index = from
        while (index < end - 1 && text[index] == '0') index++
        return index
    }
}
