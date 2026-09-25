package com.lmreader.core.storage.access

import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 每次启动的授权检测（用户要求：「每次启动都要有授权检测」）。
 *
 * 检测分三层，每层回答一个不同的问题，缺一层就会出现"看上去有权限、实际读不了"：
 *
 * 1. **是否拿到"全部文件访问"** —— 决定用户能不能选中受限目录（[StorageAccess]）；
 * 2. **是否还持有该路径的持久授权记录** —— 记录丢了说明用户撤销过或换过设备；
 * 3. **现在是否真的能读** —— 用一次真实的目录枚举确认。这一步不能省：
 *    真机上出现过"dumpsys 显示持久授权存在，但查询被系统拒绝"的情况，
 *    只查前两层会把失败伪装成"目录里没有漫画"。
 *
 * 检测结果写回来源的 permission 字段，界面据此显示"授权失效/点击重新选择"，
 * 而不是让用户面对一个空图库猜原因。
 */
class StorageAccessCoordinator(
    private val context: android.content.Context,
    private val treeAccess: TreeAccess,
    private val sourceRepository: SourceRepository,
) {

    /** 启动时的整体状态，供界面显示一条明确的提示。 */
    data class StartupReport(
        val allFilesAccess: Boolean,
        val allFilesAccessAvailable: Boolean,
        val checkedSources: Int,
        val revokedSources: List<LibrarySource>,
    ) {
        val allHealthy: Boolean get() = revokedSources.isEmpty()

        /** 启动提示；没有问题时返回 null，不打扰用户。 */
        val bannerMessage: String?
            get() = when {
                revokedSources.isNotEmpty() ->
                    "有 ${revokedSources.size} 条路径的授权已失效，需要重新选择目录"

                allFilesAccessAvailable && !allFilesAccess ->
                    "未获得「全部文件访问」：只能用系统选择器授权单个目录"

                else -> null
            }
    }

    /**
     * 检测所有已保存来源。
     *
     * 逐条真实枚举授权根：只要有一次成功就说明这条路径可用。
     * 失败一律标 LOST（而不是删除索引）——授权可以恢复，索引删了就找不回来
     * （验收 A07「权限丢失……不大批删索引/收藏」）。
     */
    suspend fun checkAll(sources: List<LibrarySource>): StartupReport = withContext(Dispatchers.IO) {
        val revoked = mutableListOf<LibrarySource>()
        for (source in sources) {
            val state = check(source)
            if (state == SourcePermissionState.LOST) revoked += source
            if (state != source.permission) {
                sourceRepository.updatePermission(source.sourceId, state)
            }
        }
        StartupReport(
            allFilesAccess = StorageAccess.hasAllFilesAccess(context),
            allFilesAccessAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R,
            checkedSources = sources.size,
            revokedSources = revoked,
        )
    }

    /**
     * 检测单个来源。
     *
     * 返回值语义：
     * - [SourcePermissionState.OK]：授权根完整枚举成功；
     * - [SourcePermissionState.PARTIAL]：能打开但内部有子目录读不到（扫描阶段才知道）；
     * - [SourcePermissionState.LOST]：记录缺失或枚举抛异常，必须重新授权。
     */
    suspend fun check(source: LibrarySource): SourcePermissionState = withContext(Dispatchers.IO) {
        // 直接问"现在能不能读"，而不是只问"有没有授权记录"：真机上出现过记录存在
        // 但读取被拒的情况，两者结论不同时必须以后者为准
        // （用户要求：每次启动都要有授权检测）。
        if (treeAccess.checkReadable(source.treeUri) != null) {
            SourcePermissionState.LOST
        } else {
            SourcePermissionState.OK
        }
    }
}
