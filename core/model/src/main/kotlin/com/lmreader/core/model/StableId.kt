package com.lmreader.core.model

import java.security.MessageDigest

/**
 * 稳定 ID：由 SAF 文档 ID 与来源种类派生，不使用列表位置、可改标题或绝对路径
 * （开发文档 15.3「身份」）。同一 documentId 在两个表里得到不同 ID。
 *
 * 之所以用哈希而不是直接拼接：documentId 由提供方给出，长度与字符集不可控；
 * 哈希把身份压缩成定长字符串，同时保证**同一输入永远得到同一输出**，
 * 因此卸载重装、进程重启后都能重算，书架的收藏引用不会因为重扫而漂移。
 *
 * 已知限制（开发文档 6.2）：跨提供方搬迁或删除重建会让提供方换发 documentId，
 * 此时身份必然变化，本对象不做补救；「重新关联失效漫画」是独立功能。
 */
object StableId {

    /** 漫画 ID：`m_` + SHA-256(documentId + '\u0000' + kind.name) 的前 32 个十六进制字符。 */
    fun mangaId(documentId: String, kind: SourceKind): String = derive("m_", documentId, kind.name)

    /** 章节 ID：`c_` + SHA-256(documentId + '\u0000' + kind.name) 的前 32 个十六进制字符。 */
    fun chapterId(documentId: String, kind: ChapterKind): String = derive("c_", documentId, kind.name)

    /**
     * 来源 ID：`s_` + SHA-256(treeUri) 的前 32 个十六进制字符。
     *
     * 来源没有「种类」这一维度：同一棵授权树重复保存必须是同一条来源记录，
     * 否则重复配置会绕过「完全重复的同类配置不允许新增」（开发文档 4.1）。
     */
    fun sourceId(treeUri: String): String = derive("s_", treeUri, null)

    /** 分隔符用 NUL，避免 `("ab", "C")` 与 `("a", "bC")` 之类的拼接歧义撞到同一个 ID。 */
    private fun derive(prefix: String, documentId: String, discriminator: String?): String {
        val payload = if (discriminator == null) documentId else "$documentId\u0000$discriminator"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (index in 0 until 16) {
                val value = digest[index].toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        return prefix + hex
    }

    private const val HEX = "0123456789abcdef"
}
