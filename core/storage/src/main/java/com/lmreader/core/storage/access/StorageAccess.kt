package com.lmreader.core.storage.access

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 存储访问方式（开发文档 4.1 的授权策略，按真机限制补充）。
 *
 * 本应用支持两条并存的授权路径，界面必须如实告诉用户当前处在哪一条：
 *
 * 1. **全部文件访问**（Android 11+ 的 `MANAGE_EXTERNAL_STORAGE`）：
 *    可以选中存储根、Download 根、Android/data 等任意目录；
 * 2. **SAF 单目录授权**：权限面更小，但 Android 11+ 的系统选择器禁止选中
 *    存储根、Download 根及 Android/data、Android/obb。
 *
 * 为什么必须两条都留：真实漫画库经常在 `/sdcard/Tachiyomi/downloads`
 * （Download 下的子目录，SAF 可授权）或 `/sdcard/Download` 本身（SAF 在部分
 * 系统上禁止授权根）。只留一条会让一部分用户根本选不到自己的库。
 */
object StorageAccess {

    /**
     * 是否已获得"全部文件访问"。
     *
     * Android 10 及以下没有这个概念：那时 `READ_EXTERNAL_STORAGE` 就等于全部
     * 文件访问，因此直接按运行时权限判断，不谎报"不支持"。
     */
    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 是否需要在界面上引导用户去开启（Android 11+ 且尚未开启）。 */
    fun needsAllFilesAccessRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess(context)

    /**
     * 跳到系统的"全部文件访问"授权页。
     *
     * 用 [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION] 并带上包名，
     * 直接落到本应用的开关；不支持该 Action 的定制系统回退到应用详情页，
     * 而不是抛 ActivityNotFoundException 让用户点了没反应。
     */
    fun allFilesAccessIntent(context: Context): Intent {
        val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        val resoleable = appSpecific.resolveActivity(context.packageManager) != null
        return if (resoleable) appSpecific else fallback
    }

    /** Android 13+ 的媒体读取权限；低版本不需要。 */
    fun mediaPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
        else -> null
    }

    /** 界面用的简短状态说明。 */
    fun describe(context: Context): String = when {
        hasAllFilesAccess(context) -> "已获得全部文件访问，可以读取任意目录"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            "未获得全部文件访问：只能用系统选择器授权单个目录，" +
                "且无法选中存储根、Download 根与 Android/data"

        else -> "未获得存储读取权限"
    }
}
