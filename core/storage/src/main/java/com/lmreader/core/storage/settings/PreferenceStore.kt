package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 应用唯一的 DataStore 实例。
 *
 * **为什么必须集中在这里**：`preferencesDataStore` 的委托是"每个属性一个实例"，
 * 两个类各自声明一次同名的 `Context.preferencesStore` 就会打开同一个文件两次，
 * DataStore 会直接抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 * 这不是警告而是崩溃，而且只在**两个类都被用到**的路径上才触发——本轮就是这样：
 * 书架只碰 [AppPreferences]，一切正常；一进阅读器碰到 [ReaderPreferences]，立刻崩溃。
 *
 * 因此这里只允许存在这一处委托；[AppPreferences] 与 [ReaderPreferences] 都通过
 * [preferenceStore] 取用同一实例。
 */
internal val Context.preferenceStore: DataStore<Preferences> by preferencesDataStore(
    name = "lmreader_preferences",
)
