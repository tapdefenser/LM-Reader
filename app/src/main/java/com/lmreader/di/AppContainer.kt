package com.lmreader.di

import android.app.Application
import android.content.Context
import com.lmreader.core.database.DatabaseProvider
import com.lmreader.core.index.StructureScanner
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.access.StorageAccessCoordinator
import com.lmreader.core.storage.access.TreeAccess
import com.lmreader.core.storage.saf.SafTreeAccess
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.MetadataBackfillWorker
import com.lmreader.core.storage.scan.SourceScanRunner
import com.lmreader.core.storage.settings.AppPreferences

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

    val database get() = databaseComponents.database
    val sourceRepository: SourceRepository get() = databaseComponents.sources
    val mangaRepository: MangaRepository get() = databaseComponents.mangas
    val shelfRepository: ShelfRepository get() = databaseComponents.shelf

    val preferences by lazy { AppPreferences(application) }


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
