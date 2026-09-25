package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lmreader.core.model.LibraryDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 每个进程只允许一个 DataStore 实例，因此挂在 Context 扩展上。 */
private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(
    name = "lmreader_preferences",
)

/**
 * 全局偏好（开发文档 14「多级设置」中属于 DataStore 的部分）。
 *
 * 为什么单独放 DataStore 而不是数据库：开发文档 15.3 明确「全局偏好和
 * onboarding 标记归 DataStore，漫画级阅读与章节视图偏好归数据库」。
 */
class AppPreferences(private val context: Context) {

    /**
     * 是否完成首次引导。默认 false（开发文档 3）。
     *
     * 语义要点：这个标记一旦置 true 就**不再清除**——后续用户删空所有路径或
     * 失去授权，冷启动仍进入书架并显示配置入口，而不是把用户重新推回引导页。
     */
    val onboardingCompleted: Flow<Boolean> = context.preferencesStore.data
        .map { it[KEY_ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted(value: Boolean) {
        context.preferencesStore.edit { it[KEY_ONBOARDING_COMPLETED] = value }
    }

    /** 图库展示方式，默认封面列表（开发文档 8.1「展示方式」）。 */
    val libraryDisplayMode: Flow<LibraryDisplayMode> = context.preferencesStore.data
        .map { prefs ->
            when (prefs[KEY_LIBRARY_DISPLAY_MODE]) {
                LibraryDisplayMode.GRID.name -> LibraryDisplayMode.GRID
                else -> LibraryDisplayMode.LIST
            }
        }

    suspend fun setLibraryDisplayMode(mode: LibraryDisplayMode) {
        context.preferencesStore.edit { it[KEY_LIBRARY_DISPLAY_MODE] = mode.name }
    }

    private companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_LIBRARY_DISPLAY_MODE = stringPreferencesKey("library_display_mode")
    }
}
