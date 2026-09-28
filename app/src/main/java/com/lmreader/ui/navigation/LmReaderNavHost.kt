package com.lmreader.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.di.AppContainer
import com.lmreader.ui.bookshelf.BookshelfScreen
import com.lmreader.ui.detail.MangaDetailScreen
import com.lmreader.ui.library.LibraryScreen
import com.lmreader.ui.reader.ReaderScreen
import com.lmreader.ui.reader.ReaderViewModel
import com.lmreader.ui.settings.SettingsHomeScreen
import com.lmreader.ui.settings.reader.ReaderSettingsScreen
import com.lmreader.ui.settings.paths.GalleryPathsScreen
import com.lmreader.ui.settings.paths.GalleryPathsSettingsScreen
import com.lmreader.ui.translation.GlossaryScreen
import com.lmreader.ui.translation.TranslationLanguageScreen
import com.lmreader.ui.translation.TranslationSettingsScreen
import com.lmreader.ui.translation.TranslationStyleScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 路由常量集中一处，避免字符串散落在各个页面里。 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val BOOKSHELF = "bookshelf"
    const val SETTINGS = "settings"
    const val SETTINGS_PATHS = "settings/paths"
    const val SETTINGS_READER = "settings/reader"
    const val MANGA_DETAIL = "manga/{mangaId}"
    const val READER = "reader/{mangaId}/{chapterId}?page={page}"
    const val TRANSLATION_QUEUE = "queue/translation"
    const val EXPORT_QUEUE = "queue/export"

    // 翻译相关的页面全部挂在详情页下（"跟着每部漫画走"，用户口径）。
    const val TRANSLATION_LANGUAGE = "manga/{mangaId}/translation/language"
    const val TRANSLATION_GLOSSARY = "manga/{mangaId}/translation/glossary"
    const val TRANSLATION_STYLE = "manga/{mangaId}/translation/style"

    /** 翻译设置是全局说明页，不挂在某部漫画下。 */
    const val TRANSLATION_SETTINGS = "settings/translation"

    fun mangaDetail(mangaId: String) = "manga/$mangaId"

    fun translationLanguage(mangaId: String) = "manga/$mangaId/translation/language"

    fun translationGlossary(mangaId: String) = "manga/$mangaId/translation/glossary"

    fun translationStyle(mangaId: String) = "manga/$mangaId/translation/style"

    /**
     * 阅读器路由。
     *
     * @param chapterId 具体章节 ID；null 表示"继续阅读"（跟随进度里的章节）
     * @param page 从**哪一页**开始（0 基）。只在从章节列表点进"已读过的那一章"时才给，
     *   于是用户点那一行会回到上次停下的页，而不是从第 1 页重来。
     *   用查询参数而不是路径段：它是可选的，而路径段难以区分"不带页码"与"页码为 0"。
     */
    fun reader(mangaId: String, chapterId: String?, page: Int? = null): String {
        val base = "reader/$mangaId/${chapterId ?: "resume"}"
        return if (page == null) base else "$base?page=$page"
    }
}

/**
 * 应用导航宿主。
 *
 * 启动决策（开发文档 1.3「首启与后续启动」、第 3 节）：
 * - 尚未完成路径配置 → 直接进入设置里的图库路径列表（引导态）；
 * - 已完成 → 冷启动进入书架。
 *
 * `onboardingCompleted` 一旦置 true 就不再清除，因此删空所有路径或失去授权之后
 * 仍然进书架（书架里会显示来源失效状态与入口），不会把用户重新推回引导页。
 */
@Composable
fun LmReaderNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    // 首次组合时同步读一次决策值：DataStore 是异步的，用 null 表示"还没读到"，
    // 在读到之前不渲染任何页面，避免先闪一下书架再跳到引导页。
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val completed = preferences.onboardingCompleted.first()
        startDestination = if (completed) Routes.BOOKSHELF else Routes.ONBOARDING
    }

    // 主菜单抽屉用框架的 ModalNavigationDrawer：
    // - 它吸附在起始侧，正是主菜单需要的方向；
    // - 它自带"从屏幕左边缘向右滑打开"的手势（用户要求），而且**关闭时不会吃掉
    //   顶栏按钮的点击**。自实现版本在这一点上踩过坑：24dp 的边缘手势区正好盖住
    //   了距边缘 20dp 的汉堡按钮，导致"菜单点不开、左滑也失灵"。
    //
    // 右侧的图源筛选栏用自实现的 EndSideDrawer：框架组件没有选择吸附侧别的参数，
    // 而"把子树设成 RTL"会把面板内容整体镜像（真机上标题与数量文案都会反过来）。
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }

    val destination = startDestination ?: return

    // 侧栏「翻译队列」的角标接真实计数：入队真的会写待翻译记录，数字必须跟着动
    // （开发文档 8.1「队列入口显示活动任务数」；写死 0 就是在骗用户）。
    val pendingTranslations by container.translationRepository
        .observePendingCount()
        .collectAsStateWithLifecycle(initialValue = 0)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            MainMenuSheet(
                activeTranslationTasks = pendingTranslations,
                onNavigate = { target ->
                    when (target) {
                        MainDestination.LIBRARY -> navController.navigateSingleTop(Routes.LIBRARY)
                        MainDestination.BOOKSHELF -> navController.navigateSingleTop(Routes.BOOKSHELF)
                        MainDestination.TRANSLATION_QUEUE ->
                            navController.navigateSingleTop(Routes.TRANSLATION_QUEUE)

                        MainDestination.EXPORT_QUEUE ->
                            navController.navigateSingleTop(Routes.EXPORT_QUEUE)

                        MainDestination.SETTINGS -> navController.navigateSingleTop(Routes.SETTINGS)
                        MainDestination.MENU -> Unit
                    }
                },
                onDismiss = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = destination) {
                composable(
                    route = Routes.READER,
                    arguments = listOf(
                        // 必须显式声明为 Int：查询参数默认按 String 解析，
                        // `getInt("page")` 会拿到 null，起始页就静默失效。
                        //
                        // `readOnly` + 在路由里给默认值，是为了不重复声明默认值——
                        // 路由模板一处、构建 URL 一处，两处不一致时最难查。
                        navArgument("page") {
                            type = NavType.IntType
                            defaultValue = ReaderViewModel.NO_START_PAGE
                        },
                    ),
                ) { entry ->
                    ReaderScreen(
                        container = container,
                        mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                        chapterId = entry.arguments?.getString("chapterId").orEmpty(),
                        startPage = entry.arguments?.getInt("page") ?: ReaderViewModel.NO_START_PAGE,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.ONBOARDING) {
                    GalleryPathsScreen(
                        container = container,
                        onNavigateToLibrary = {
                            navController.navigate(Routes.LIBRARY) {
                                // 引导完成后清掉引导页，返回键不会回到"设置路径"。
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                        onBack = null,
                    )
                }

                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        container = container,
                        onOpenMenu = openMenu,
                        onOpenManga = { mangaId -> navController.navigate(Routes.mangaDetail(mangaId)) },
                        onOpenSettings = { navController.navigateSingleTop(Routes.SETTINGS_PATHS) },
                    )
                }

                composable(Routes.BOOKSHELF) {
                    BookshelfScreen(
                        container = container,
                        onOpenMenu = openMenu,
                        onOpenManga = { mangaId -> navController.navigate(Routes.mangaDetail(mangaId)) },
                        onOpenLibrary = { navController.navigateSingleTop(Routes.LIBRARY) },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsHomeScreen(
                        onOpenPaths = { navController.navigateSingleTop(Routes.SETTINGS_PATHS) },
                        onOpenReader = { navController.navigateSingleTop(Routes.SETTINGS_READER) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_PATHS) {
                    GalleryPathsSettingsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_READER) {
                    ReaderSettingsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.MANGA_DETAIL) { entry ->
                    val mangaId = entry.arguments?.getString("mangaId").orEmpty()
                    MangaDetailScreen(
                        container = container,
                        mangaId = mangaId,
                        onBack = { navController.popBackStack() },
                        onReadChapter = { chapterId, startPage ->
                            navController.navigate(Routes.reader(mangaId, chapterId, startPage))
                        },
                        onOpenTranslationLanguage = {
                            navController.navigate(Routes.translationLanguage(mangaId))
                        },
                        onOpenGlossary = {
                            navController.navigate(Routes.translationGlossary(mangaId))
                        },
                        onOpenTranslationStyle = {
                            navController.navigate(Routes.translationStyle(mangaId))
                        },
                        onOpenTranslationSettings = {
                            navController.navigate(Routes.TRANSLATION_SETTINGS)
                        },
                    )
                }

                composable(Routes.TRANSLATION_LANGUAGE) { entry ->
                    TranslationLanguageScreen(
                        container = container,
                        mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.TRANSLATION_GLOSSARY) { entry ->
                    GlossaryScreen(
                        container = container,
                        mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.TRANSLATION_STYLE) { entry ->
                    TranslationStyleScreen(
                        container = container,
                        mangaId = entry.arguments?.getString("mangaId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.TRANSLATION_SETTINGS) {
                    TranslationSettingsScreen(onBack = { navController.popBackStack() })
                }


                composable(Routes.TRANSLATION_QUEUE) {
                    NotImplementedScreen(
                        title = "翻译队列",
                        stage = "第三步（P3）实现",
                        details = "翻译队列需要模型资产（检测/OCR）、三种翻译模式、译名字典与" +
                            "持久任务恢复。模型资产尚未登记来源与校验和，因此本步不提供任何" +
                            "可点击的翻译操作。",
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.EXPORT_QUEUE) {
                    NotImplementedScreen(
                        title = "导出队列",
                        stage = "第四步（P4）实现",
                        details = "导出需要译文产物、合成渲染与相册/SAF 目标发布。本步没有译文" +
                            "产物，因此不显示空的导出任务列表。",
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** 同一个目的地重复点选时不叠加返回栈（主菜单可能被连续点击）。 */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(graph.startDestinationId) { inclusive = false }
    }
}
