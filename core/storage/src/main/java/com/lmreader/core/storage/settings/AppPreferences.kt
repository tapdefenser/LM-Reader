package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lmreader.core.model.LibraryDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全局偏好（开发文档 14「多级设置」中属于 DataStore 的部分）。
 *
 * 为什么单独放 DataStore 而不是数据库：开发文档 15.3 明确「全局偏好和
 * onboarding 标记归 DataStore，漫画级阅读与章节视图偏好归数据库」。
 *
 * DataStore 实例的归属见 [preferenceStore] —— 这里**不能**再声明一个
 * `preferencesDataStore` 委托，否则同一文件会被打开两次并抛异常。
 */
class AppPreferences(private val context: Context) {

    /**
     * 是否完成首次引导。默认 false（开发文档 3）。
     *
     * 语义要点：这个标记一旦置 true 就**不再清除**——后续用户删空所有路径或
     * 失去授权，冷启动仍进入书架并显示配置入口，而不是把用户重新推回引导页。
     */
    val onboardingCompleted: Flow<Boolean> = context.preferenceStore.data
        .map { it[KEY_ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted(value: Boolean) {
        context.preferenceStore.edit { it[KEY_ONBOARDING_COMPLETED] = value }
    }

    /** 图库展示方式，默认封面列表（开发文档 8.1「展示方式」）。 */
    val libraryDisplayMode: Flow<LibraryDisplayMode> = context.preferenceStore.data
        .map { prefs ->
            when (prefs[KEY_LIBRARY_DISPLAY_MODE]) {
                LibraryDisplayMode.GRID.name -> LibraryDisplayMode.GRID
                else -> LibraryDisplayMode.LIST
            }
        }

    suspend fun setLibraryDisplayMode(mode: LibraryDisplayMode) {
        context.preferenceStore.edit { it[KEY_LIBRARY_DISPLAY_MODE] = mode.name }
    }

    /**
     * 图库的图源筛选（用户勾选的来源 ID 集合）。
     *
     * 空集合表示"没有筛选"，与"用户取消勾选全部"在界面上是同一件事：
     * 两者都显示全部条目——与其给用户一个必然空白的图库，不如把空筛选当作
     * 未筛选，并在右滑栏里保留他的勾选状态。
     *
     * 存 DataStore 而不是数据库：它是界面偏好，且与"打开页面不扫描"配合时
     * 必须能立即读出来，不能等 Room 打开。
     */
    val librarySourceFilter: Flow<Set<String>> = context.preferenceStore.data
        .map { prefs ->
            prefs[KEY_LIBRARY_SOURCE_FILTER]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        }

    suspend fun setLibrarySourceFilter(sourceIds: Set<String>) {
        context.preferenceStore.edit { prefs ->
            if (sourceIds.isEmpty()) {
                prefs.remove(KEY_LIBRARY_SOURCE_FILTER)
            } else {
                prefs[KEY_LIBRARY_SOURCE_FILTER] = sourceIds.joinToString(SEPARATOR)
            }
        }
    }

    private companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_LIBRARY_DISPLAY_MODE = stringPreferencesKey("library_display_mode")
        val KEY_LIBRARY_SOURCE_FILTER = stringPreferencesKey("library_source_filter")

        /** 来源 ID 是 `s_` + 十六进制，不含逗号，因此逗号分隔是安全的。 */
        const val SEPARATOR = ","
    }
}
