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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.di.AppContainer
import com.lmreader.ui.bookshelf.BookshelfScreen
import com.lmreader.ui.library.LibraryScreen
import com.lmreader.ui.settings.SettingsHomeScreen
import com.lmreader.ui.settings.paths.GalleryPathsScreen
import com.lmreader.ui.settings.paths.GalleryPathsSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 路由常量集中一处，避免字符串散落在各个页面里。 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val BOOKSHELF = "bookshelf"
    const val SETTINGS = "settings"
    const val SETTINGS_PATHS = "settings/paths"
    const val MANGA_DETAIL = "manga/{mangaId}"
    const val TRANSLATION_QUEUE = "queue/translation"
    const val EXPORT_QUEUE = "queue/export"

    fun mangaDetail(mangaId: String) = "manga/$mangaId"
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

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }

    val destination = startDestination ?: return

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainMenuSheet(
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
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_PATHS) {
                    GalleryPathsSettingsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.MANGA_DETAIL) { entry ->
                    val mangaId = entry.arguments?.getString("mangaId").orEmpty()
                    NotImplementedScreen(
                        title = "漫画详情",
                        stage = "第二步（P2）实现",
                        details = "漫画详情页需要章节列表、加入书架、更新章节、阅读、翻译、" +
                            "筛选排序与漫画级设置。当前已识别到 mangaId=$mangaId，但详情与阅读器" +
                            "尚未实现，因此这里不显示任何占位数据。",
                        onBack = { navController.popBackStack() },
                    )
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
