package com.lmreader.ui.paging

/**
 * 图库/书架列表的「40 项增长」状态机（开发文档 6.4 / 8.1）。
 *
 * 为什么单独抽出：分页额度、扫描中补位、追加下一批这三条规则在扫描、搜索和
 * 刷新下都要成立，把它们放在 Compose 里会无法测试。这里只做纯状态计算，
 * 不接触数据库与协程调度。
 *
 * 本步限制（开发文档 6.4 的 P1 项）：后端使用 LIMIT/OFFSET 而不是会话顺序表，
 * 扫描中新增条目可能让同一项在两次翻页里出现，因此这里用已见过的 id 去重。
 */
class PagingState<T>(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val idOf: (T) -> String,
) {
    private val seen = LinkedHashSet<String>()
    private val mutableItems = mutableListOf<T>()

    val items: List<T> get() = mutableItems

    /** 已经取过的最大 offset，用于向后端请求下一批。 */
    var nextOffset: Int = 0
        private set

    /** 后端报告已无更多数据。 */
    var exhausted: Boolean = false
        private set

    /** 当前可用额度（已显示项数 + 目标额度）。 */
    val capacity: Int get() = maxOf(mutableItems.size, requestedCapacity)

    private var requestedCapacity = 0
    private var loading = false

    /** 是否应该再取一批：未耗尽、未在加载、已显示项数未达本次额度。 */
    fun needsMore(): Boolean = !exhausted && !loading && mutableItems.size < requestedCapacity

    fun beginLoad() {
        loading = true
    }

    fun endLoad() {
        loading = false
    }

    val isLoading: Boolean get() = loading

    /** 首次加载：申请一屏额度。 */
    fun requestInitial() {
        requestedCapacity = pageSize
    }

    /** 滚到底部：额度 +40（开发文档 8.1「底部加载」）。 */
    fun requestNextBatch() {
        requestedCapacity += pageSize
    }

    /**
     * 吸收一批结果。
     *
     * @param totalKnown 后端可给出的总数；null 表示未知，UI 不得伪造百分比。
     */
    fun append(page: PageSlice<T>, totalKnown: Int?) {
        page.items.forEach { item ->
            if (seen.add(idOf(item))) mutableItems += item
        }
        nextOffset = page.nextOffset
        exhausted = page.exhausted
        this.totalKnown = totalKnown
    }

    var totalKnown: Int? = null
        private set

    /** 排序/筛选/搜索条件变化：全新会话，清空已见集合。 */
    fun reset() {
        seen.clear()
        mutableItems.clear()
        nextOffset = 0
        exhausted = false
        requestedCapacity = pageSize
    }

    /** 当前额度是否仍有空位——扫描继续时用它决定是否立即补入新条目。 */
    fun hasFreeSlot(): Boolean = !exhausted && !loading && mutableItems.size < requestedCapacity

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
    }
}

/** 后端返回的一页切片。偏移语义由后端保证。 */
data class PageSlice<T>(
    val items: List<T>,
    val nextOffset: Int,
    val exhausted: Boolean,
)
