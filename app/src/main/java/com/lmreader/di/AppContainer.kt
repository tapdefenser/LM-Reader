package com.lmreader.di

import android.app.Application
import android.content.Context
import com.lmreader.core.database.DatabaseProvider
import com.lmreader.core.index.StructureScanner
import com.lmreader.core.index.ChapterResolver
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.StorageAccessCoordinator
import com.lmreader.core.storage.access.TreeAccess
import com.lmreader.core.storage.saf.SafTreeAccess
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.MangaChapterSyncer
import com.lmreader.core.storage.scan.MetadataBackfillWorker
import com.lmreader.core.storage.scan.SourceScanRunner
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 依赖装配（开发文档 15.2：`app / navigation` 负责"导航、依赖装配、主题"）。
 *
 * 为什么用手写容器而不是引入 DI 框架：本应用的对象图很小且没有变体（debug/release
 * 共用一套），引入 Hilt/Koin 只会增加注解处理与构建时间。等 feature 拆成独立
 * Gradle 模块、构造链变长之后再换框架也不影响调用方（都从这里取）。
 *
 * 线程约定：容器构建本身不做 IO；数据库与 DataStore 都是惰性打开。
 */
class AppContainer(private val application: Application) {

    private val databaseComponents by lazy { DatabaseProvider.create(application) }

    /**
     * 启动时的一次性索引维护（不参与依赖图，失败不影响使用）。
     *
     * 目前只做一件事：把"来源行已经不存在的"孤儿卡片标成陈旧（只改可用性、不删行）。
     * `mangas` 没有指向 `library_sources` 的外键，早期版本删掉一条路径之后卡片会永远
     * 留在图库里——真机实测 4749 张卡片里有 4595 张是这种孤儿，图库界面因此完全没法看。
     *
     * 容器构建本身不做 IO（见类注释），所以放在独立作用域里跑；结果只记日志，
     * 因为它是维护而不是启动前提。
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 这里曾经调用 `SubsamplingScaleImageView.setPreferredBitmapConfig(RGB_565)` 以期
        // 把每页的内存减半。反编译该 fork 的解码器后确认**它无效**：位图格式在
        // `decoder.Decoder.init` 里硬编码为 ARGB_8888，静态配置根本不参与。
        // 真正的对策见 ReaderImageView（控制送进去的像素量）与 PagePrefetcher
        // （字节只落磁盘、不压堆）。留着那行只会让人以为内存已经被限制住了。

        maintenanceScope.launch {
            runCatching { mangaRepository.markOrphanedAsStale() }
                .onSuccess { hidden ->
                    if (hidden > 0) {
                        android.util.Log.i(TAG, "启动维护：隐藏孤儿卡片 $hidden 张（来源行已删除，只隐藏不删除）")
                    }
                }
                .onFailure { error -> android.util.Log.e(TAG, "启动维护：孤儿卡片清扫失败", error) }
        }
    }

    val database get() = databaseComponents.database
    val sourceRepository: SourceRepository get() = databaseComponents.sources
    val mangaRepository: MangaRepository get() = databaseComponents.mangas
    val shelfRepository: ShelfRepository get() = databaseComponents.shelf
    val readingProgressRepository get() = databaseComponents.readingProgress

    val preferences by lazy { AppPreferences(application) }

    /**
     * 阅读器偏好（开发文档 12、14）。
     *
     * 与 [preferences] 分开放：两者共用同一个 DataStore 文件，但一个是"界面与引导"、
     * 另一个是"阅读器几十项设置"，合并成一个类只会让它变成杂物间。
     */
    val readerPreferences by lazy {
        com.lmreader.core.storage.settings.ReaderPreferences(application)
    }


    /** 底层 SAF 授权管理（持久授权、URI 构造）。 */
    val safAccess by lazy { SafTreeAccess(application) }

    /**
     * 打开内容树的统一入口：有「全部文件访问」时走直接文件访问，
     * 否则走单目录 SAF 授权。扫描、补全与封面都只通过它取树。
     */
    val treeAccess by lazy { TreeAccess(application, safAccess) }

    /**
     * 每次启动的授权检测（用户要求）。
     *
     * 放在容器里而不是某个页面里：书架、图库、路径配置三处都要用同一个结论，
     * 各自检测会出现"这个页面说失效、那个页面说正常"的矛盾。
     */
    val accessCoordinator by lazy {
        StorageAccessCoordinator(
            context = application,
            treeAccess = treeAccess,
            sourceRepository = sourceRepository,
        )
    }

    val backfillWorker by lazy {
        MetadataBackfillWorker(
            context = application,
            treeAccess = treeAccess,
            mangaRepository = mangaRepository,
        )
    }

    val mangaChapterSyncer by lazy {
        MangaChapterSyncer(
            treeAccess = treeAccess,
            resolver = ChapterResolver(),
            mangaRepository = mangaRepository,
            sourceRepository = sourceRepository,
        )
    }

    val pageSourceFactory by lazy { PageSourceFactory(treeAccess) }

    /**
     * 页面字节的磁盘预取缓存（见 [com.lmreader.ui.reader.PagePrefetcher]）。
     *
     * 放在容器里而不是阅读器 ViewModel 里：缓存的价值在于**跨会话存活**，
     * 读者退出再进来时那几页应该还在。缓存目录由系统管理，随时可被清理，
     * 因此不需要应用自己关心它的生命周期。
     */
    val pagePrefetcher by lazy {
        com.lmreader.ui.reader.PagePrefetcher(application)
    }

    /**
     * 结构扫描器是**纯算法**，不认识任何具体来源：它只通过 [TreeFactory] 读目录。
     * 真实工厂由 [SourceScanRunner] 在每次扫描时按该来源的树 URI 构造（开发文档
     * 15.2「core:index 禁止依赖业务 UI」，反过来也不该持有来源状态）。
     */
    private val structureScanner by lazy { StructureScanner(FailFastTreeFactory) }

    val scanRunner by lazy {
        SourceScanRunner(
            treeAccess = treeAccess,
            scanner = structureScanner,
            mangaRepository = mangaRepository,
            sourceRepository = sourceRepository,
        )
    }

    val scanCoordinator by lazy {
        LibraryScanCoordinator(
            runner = scanRunner,
            sourceRepository = sourceRepository,
            mangaRepository = mangaRepository,
            backfillWorker = backfillWorker,
        )
    }

    companion object {
        private const val TAG = "AppContainer"

        /**
         * 失败即报错的占位工厂。
         *
         * 它存在的唯一理由是让"忘了传真实工厂"立刻可见：早先这里是 `TreeFactory { null }`，
         * 于是每个子目录都被当成"打不开"，既不抛异常也没有日志，真机上表现成
         * "0 部漫画、65 个失败路径"，排查成本极高。现在它直接抛异常。
         */
        private val FailFastTreeFactory = com.lmreader.core.index.TreeFactory { child ->
            throw IllegalStateException(
                "扫描器没有拿到本次扫描的 TreeFactory（child=${child.name}）；" +
                    "SourceScanRunner 必须把按来源授权树构造的工厂传进 scan(factory = …)",
            )
        }

        fun from(context: Context): AppContainer {
            val app = context.applicationContext as Application
            return (app as LmReaderApplicationHolder).container
        }
    }
}

/** 由 Application 实现，避免在 :app 里到处做类型转换。 */
interface LmReaderApplicationHolder {
    val container: AppContainer
}
