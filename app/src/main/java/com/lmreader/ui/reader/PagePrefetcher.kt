package com.lmreader.ui.reader

import android.content.Context
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预取的一页。 */
data class PrefetchCandidate(
    val page: ReaderPage,
    val source: PageSource,
)

/**
 * 页面字节的磁盘预取缓存。
 *
 * ## 为什么是磁盘而不是内存
 *
 * 真机上已经吃过一次"整图分配"的亏（见 `ReaderImageView` 与交接文档）：那台设备
 * 的堆增长上限只有 256MB，而解码一页 3024×1700 就要 20MB。把几页的**图像字节**
 * 再压进堆里，等于把 OOM 重新引回来。磁盘缓存没有这个问题：多缓存几页只是多占
 * 应用私有缓存目录，交给系统的缓存清理也安全（丢了只会退化成"没预取"）。
 *
 * ## 缓存管什么、不管什么
 *
 * 管：`PageSource.open()` 这一步。真机页源是 SAF，每页一次 `openInputStream` 都要过
 * ContentProvider，翻页时的等待有相当一部分在这里。
 *
 * 不管：解码。解码仍由引擎做，因此本类不改变内存峰值。
 *
 * ## 一致的输入
 *
 * [stream] 返回 `ByteArrayInputStream` 而不是内容提供者的流。对库的解码器来说这不是
 * 可随机访问的输入，但它本来也会把整个流读进内存（同样见 `ReaderImageView` 的说明），
 * 所以换成本地字节**不会**让它退化解码策略，只省掉一次跨进程读取。
 *
 * 所有方法都可以从任意线程调用；内部状态是 `ConcurrentHashMap`。
 */
class PagePrefetcher(
    context: Context,
    /** 磁盘缓存上限。超出后按"最早写入"淘汰。 */
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    /** 单页上限：超过它就不缓存（缓存它反而会把别的页挤掉）。 */
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = File(context.cacheDir, "reader-pages")
    private val entries = ConcurrentHashMap<String, File>()
    @Volatile private var currentScopeKey: String? = null
    private var scheduled: Set<String> = emptySet()

    /** 预取任务；新范围到来时取消上一个，避免旧任务把已失效的页读满磁盘。 */
    private var job: Job? = null

    /**
     * 请求把 [ahead] / [behind] 这些页读进缓存。
     *
     * @param scopeKey 当前阅读范围（漫画 + 当前章 + 两侧窗口边界）。它变化时清除范围外
     *   的缓存；**翻页不会改变它**，因此同一章里连续翻页不会反复删缓存。
     * @param ahead 阅读顺序上靠后的页，由近及远
     * @param behind 阅读顺序上靠前的页，由近及远
     */
    fun request(
        scopeKey: String,
        ahead: List<PrefetchCandidate>,
        behind: List<PrefetchCandidate>,
    ) {
        val wanted = LinkedHashMap<String, PrefetchCandidate>()
        for (candidate in ahead + behind) {
            wanted.putIfAbsent(candidate.page.pageId, candidate)
        }
        if (wanted.isEmpty()) return

        val changedScope = scopeKey != currentScopeKey
        if (changedScope) currentScopeKey = scopeKey

        val prefix = scopePrefix(scopeKey)
        val missing = wanted.filterKeys { pageId ->
            entries[pageId]?.let { it.isFile && it.name.startsWith(prefix) } != true
        }
        // 同一范围内、待取集合也没变时不重复排队（翻页会高频触发本方法）。
        if (!changedScope && missing.keys == scheduled && job?.isActive == true) return
        scheduled = missing.keys

        job?.cancel()
        job = scope.launch {
            try {
                if (changedScope) prune(scopeKey)
                directory.mkdirs()
                for ((pageId, candidate) in missing) {
                    val existing = entries[pageId]
                    if (existing != null && existing.isFile) continue
                    val file = fetch(candidate, scopeKey) ?: continue
                    entries[pageId] = file
                    enforceLimit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 预取失败不影响阅读：只是退化成"翻到那一页时现读"。
            }
        }
    }

    /**
     * 取一页的字节流；没有缓存时返回 null，调用方应回退到页源。
     *
     * 命中时**不删除**缓存文件：同一页可能因为重组、旋转、切换模式而被读第二次，
     * 而删掉之后重建的代价正是预取想省掉的那部分。
     */
    fun stream(pageId: String): InputStream? {
        val file = entries[pageId] ?: return null
        if (currentScopeKey?.let { !file.name.startsWith(scopePrefix(it)) } == true) return null
        if (!file.isFile) {
            entries.remove(pageId)
            return null
        }
        return runCatching { FileInputStream(file) as InputStream }.getOrNull()
    }

    /** 取消排队中的预取（阅读器关闭时调用）。已缓存的文件留着，下次打开仍能命中。 */
    fun cancelAll() {
        job?.cancel()
        job = null
        scheduled = emptySet()
    }

    /**
     * 删除 [keepScopeKey] 之外的所有缓存文件。
     *
     * "之外"不等于"立刻无用"：用户可能只是换了一章又翻回来。但两边的取舍很明显——
     * 留着会让缓存无限增长，删掉最坏只是多一次跨进程读取，因此选择删。
     */
    private suspend fun prune(keepScopeKey: String) = withContext(Dispatchers.IO) {
        val keep = scopePrefix(keepScopeKey)
        directory.listFiles()?.forEach { file ->
            if (!file.name.startsWith(keep)) {
                file.delete()
                removeEntryFor(file)
            }
        }
        Unit
    }

    /** 边读边限制大小并落盘，不把整页先装进堆里。 */
    private suspend fun fetch(candidate: PrefetchCandidate, scopeKey: String): File? =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val temporary = File.createTempFile(scopePrefix(scopeKey), ".part", directory)
            try {
                var total = 0L
                candidate.source.open(candidate.page).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxEntryBytes) return@withContext null
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (total == 0L) return@withContext null
                val file = File(directory, fileName(scopeKey, candidate.page.pageId))
                if (file.isFile) return@withContext file
                if (!temporary.renameTo(file)) return@withContext null
                file
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                temporary.delete()
            }
        }

    /** 超出总量上限时按"最早写入"淘汰到上限以下。 */
    private fun enforceLimit() {
        val files = directory.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) {
                removeEntryFor(file)
                total -= length
            }
        }
    }

    private fun removeEntryFor(file: File) {
        entries.forEach { (pageId, cached) ->
            if (cached == file) entries.remove(pageId, cached)
        }
    }

    /**
     * 缓存文件名：范围键的哈希做前缀，页 ID 十六进制编码做后缀。
     *
     * 为什么页 ID 必须编码：它由 `StableId.pageId` 生成，可能含 `:` 与 `/` 之类的字符，
     * 直接当文件名不安全。为什么范围键只取哈希：它同样含这些字符，而清理时只需要一个
     * 能用 `startsWith` 比较的前缀。
     */
    private fun fileName(scopeKey: String, pageId: String): String =
        scopePrefix(scopeKey) + pageId.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    /** 范围键的前缀形式；[prune] 靠它判断一个文件属于哪个范围。 */
    private fun scopePrefix(scopeKey: String): String = scopeKey.hashCode().toUInt().toString(16) + "_"

    companion object {
        /**
         * 磁盘缓存上限。
         *
         * 预算是页数（默认 9 页），单页 1–2MB，因此正常使用只有十几 MB；上限设大一些是
         * 为了让"翻回去再看一遍"也能命中，同时不给设备带来压力（系统可随时清理缓存目录）。
         */
        const val DEFAULT_MAX_BYTES = 192L * 1024 * 1024

        /** 单页上限 24MB：比真机上见过的最大的页还大一截，超过它的一定是异常数据。 */
        const val DEFAULT_MAX_ENTRY_BYTES = 24L * 1024 * 1024
    }
}
