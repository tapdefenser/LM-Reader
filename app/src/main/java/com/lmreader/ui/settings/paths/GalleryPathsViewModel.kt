package com.lmreader.ui.settings.paths

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.model.StableId
import com.lmreader.core.storage.access.StorageAccessCoordinator
import com.lmreader.core.storage.access.TreeAccess
import com.lmreader.core.storage.saf.SafTreeAccess
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.OverallScanState
import com.lmreader.core.storage.scan.ScanReason
import com.lmreader.core.storage.scan.ScanState
import com.lmreader.core.storage.settings.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 图库路径配置页（开发文档 4，按用户要求修订交互）。
 *
 * 本页的交互约定：
 * - `+` 打开系统目录选择器，**选完立即保存并开始扫描**，不需要二次确认；
 * - 子目录复选框与类型开关**改动即保存**，不再有行内"保存/取消"；
 * - 点路径列打开**编辑弹窗**，可在其中改名或重新选择目录，弹窗用「取消/保存」；
 * - 删除是唯一保留确认的操作，因为它会移除整条索引来源；
 * - 拖动排序即时持久化（开发文档 4.1）。
 *
 * 仍然坚持的安全边界：路径本身不允许手工输入。SAF 的树 URI 是授权句柄，
 * 手打一个路径字符串拿不到访问权限（开发文档 4.1）。弹窗里可编辑的只有**显示
 * 名称**，它不参与身份与授权。
 */
class GalleryPathsViewModel(
    private val sourceRepository: SourceRepository,
    private val accessCoordinator: StorageAccessCoordinator,
    private val treeAccess: TreeAccess,
    /** SAF 授权管理与 URI 构造（持久授权、路径描述）。 */
    private val safAccess: SafTreeAccess,
    private val scanCoordinator: LibraryScanCoordinator,
    private val preferences: AppPreferences,
    private val isOnboarding: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(
        GalleryPathsUiState(
            isOnboarding = isOnboarding,
            table = SourceTableState.forKind(),
        ),
    )
    val state: StateFlow<GalleryPathsUiState> = _state.asStateFlow()

    init {
        // 每次进入本页（含冷启动）都跑一次授权检测：用户撤销权限、换设备或移除
        // SD 卡之后，界面必须立刻显示"授权失效，点击重新选择"，而不是让用户面对
        // 一个空图库猜原因（用户要求「每次启动都要有授权检测」）。
        viewModelScope.launch { runAccessCheck() }
        // 只有一张路径表：一次遍历同时解释图片与归档，因此只订阅一份来源列表。
        viewModelScope.launch {
            sourceRepository.observeSources().collect { sources ->
                _state.update { it.withSavedRows(sources) }
            }
        }
        viewModelScope.launch {
            scanCoordinator.overall.collect { overall -> _state.update { it.copy(overall = overall) } }
        }
        viewModelScope.launch {
            scanCoordinator.runnerStates.collect { states -> _state.update { it.copy(scanStates = states) } }
        }
    }

    /**
     * 系统目录选择器返回。
     *
     * uri 为 null 表示用户取消：取消不新增行（开发文档 4.1）。
     * 选择成功时立刻取持久授权并写库，然后马上对该来源发起扫描——用户不需要
     * 再点任何按钮就能在图库里看到结果。
     */
    fun onDirectoryPicked(uri: Uri?) {
        if (uri == null) return
        val granted = safAccess.takePersistablePermission(uri)
        if (!granted) {
            // 拿不到持久授权就不能写入：进程重启后这条路径会必然失效，
            // 把它留在列表里等于给用户一个骗人的配置（开发文档 4.1）。
            _state.update { it.copy(hint = "系统没有授予持久访问权限，已取消添加该目录") }
            return
        }
        val description = safAccess.describe(uri)
        val picked = PickedDirectory(
            treeUri = uri,
            documentId = description.documentId,
            displayPath = description.displayPath,
            providerLabel = description.providerLabel,
        )
        viewModelScope.launch {
            val existing = _state.value.table.rows
                .mapNotNull { it.source }
                .firstOrNull { it.treeUri == picked.treeUri.toString() }
            if (existing != null) {
                _state.update { it.copy(hint = "该目录已经在列表中") }
                return@launch
            }
            safAccess.retain(picked.treeUri.toString())
            addSource(picked)
        }
    }

    /** 立即新增一条来源并开始扫描。 */
    private suspend fun addSource(picked: PickedDirectory) {
        val source = LibrarySource(
            sourceId = StableId.sourceId(picked.treeUri.toString()),
            // 来源种类不再决定扫描行为（一次遍历同时找图片与归档章节），
            // 只作为卡片身份与徽标保留，因此新来源统一用图片目录这一支。
            kind = SourceKind.IMAGE_DIRECTORY,
            treeUri = picked.treeUri.toString(),
            displayPath = picked.displayPath,
            providerLabel = picked.providerLabel,
            displayName = null,
            recursive = true,
            mode = LayoutMode.MULTI_CHAPTER,
            // -1 交给仓储追加到表尾，避免界面自己算顺序而和数据库不一致。
            orderIndex = -1,
            permission = SourcePermissionState.OK,
            revision = 0,
            lastScanAt = null,
            lastScanStatus = null,
            lastScanError = null,
        )
        runCatching { sourceRepository.saveSource(source) }
            .onSuccess { saved ->
                _state.update { it.copy(hint = "已加入：${saved.displayPath}") }
                scanCoordinator.rescanSource(saved.sourceId, ScanReason.SAVED_SOURCE)
            }
            .onFailure { error ->
                _state.update { it.copy(hint = "添加失败：${error.message ?: "无法写入本地索引"}") }
            }
    }

    /** 子目录复选框：改动即保存，保存后重新协调该来源（开发文档 4.1）。 */
    fun setRecursive(sourceId: String, recursive: Boolean) {
        mutate(sourceId) { it.copy(recursive = recursive) }
    }

    /** 类型开关：改动即保存，随后重识别该来源。 */
    fun setMode(sourceId: String, mode: LayoutMode) {
        mutate(sourceId) { it.copy(mode = mode) }
    }

    /**
     * 统一的"改动即保存"路径。
     *
     * 保存成功后重新扫描该来源：子目录与类型都会改变结构判定结果，不重扫的话
     * 界面会显示与配置不符的旧卡片（开发文档 4.1「行保存后重新协调该源索引」）。
     */
    private fun mutate(
        sourceId: String,
        transform: (LibrarySource) -> LibrarySource,
    ) {
        viewModelScope.launch {
            val current = sourceRepository.getSource(sourceId) ?: return@launch
            val updated = transform(current)
            if (updated == current) return@launch
            runCatching { sourceRepository.saveSource(updated) }
                .onSuccess { scanCoordinator.rescanSource(sourceId, ScanReason.SAVED_SOURCE) }
                .onFailure { error ->
                    _state.update { it.copy(hint = "保存失败：${error.message ?: "无法写入本地索引"}") }
                }
        }
    }

    /** 拖动/上移下移排序：唯一即时保存的配置操作（开发文档 4.1）。 */
    fun moveRow(fromIndex: Int, toIndex: Int) {
        val rows = _state.value.table.rows
        if (fromIndex !in rows.indices || toIndex !in rows.indices || fromIndex == toIndex) return
        val ids = rows.mapNotNull { it.source?.sourceId }
        if (ids.size != rows.size) return
        val reordered = ids.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        viewModelScope.launch { sourceRepository.reorder(reordered) }
    }

    // ------------------------------------------------------------ 路径编辑弹窗

    fun openEditor(sourceId: String) {
        val source = _state.value.table.rows.firstOrNull { it.source?.sourceId == sourceId }?.source
            ?: return
        _state.update { it.copy(editor = SourceEditDialogState.from(source)) }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun onEditorNameChange(value: String) {
        _state.update { current ->
            current.copy(editor = current.editor?.copy(nameInput = value, error = null))
        }
    }

    /**
     * 弹窗里"重新选择目录"的结果。
     *
     * 不立刻落盘：用户可能接着按"取消"，此时必须保持原目录不变。因此先把新 URI
     * 暂存在弹窗状态里，点"保存"才一起提交（开发文档 4.1「取消不修改已保存数据」）。
     */
    fun onEditorDirectoryPicked(uri: Uri?) {
        if (uri == null) return
        val current = _state.value.editor ?: return
        val granted = safAccess.takePersistablePermission(uri)
        if (!granted) {
            _state.update {
                it.copy(editor = current.copy(error = "系统没有授予持久访问权限，请换一个目录"))
            }
            return
        }
        val description = safAccess.describe(uri)
        _state.update {
            it.copy(
                editor = current.copy(
                    pendingTreeUri = uri.toString(),
                    pendingDisplayPath = description.displayPath,
                    providerLabel = description.providerLabel ?: current.providerLabel,
                    error = null,
                ),
            )
        }
    }

    /** 保存弹窗：改名与（可选的）换目录一起提交。 */
    fun saveEditor() {
        val editor = _state.value.editor ?: return
        if (!editor.nameValid) return
        viewModelScope.launch {
            val current = sourceRepository.getSource(editor.sourceId)
            if (current == null) {
                _state.update { it.copy(editor = null, hint = "该路径已经不存在") }
                return@launch
            }
            val newTreeUri = editor.pendingTreeUri
            val renamed = current.copy(
                treeUri = newTreeUri ?: current.treeUri,
                displayPath = editor.pendingDisplayPath ?: current.displayPath,
                providerLabel = editor.providerLabel,
                displayName = editor.nameInput.trim().ifBlank { null },
            )
            runCatching {
                if (newTreeUri != null && newTreeUri != current.treeUri) {
                    // 换目录等于换身份：先按新树 URI 重新计算 sourceId 并存为新来源，
                    // 再删掉旧行。顺序不能反——先删后写一旦失败就丢了配置。
                    val replaced = renamed.copy(sourceId = StableId.sourceId(newTreeUri))
                    safAccess.retain(newTreeUri)
                    sourceRepository.saveSource(replaced)
                    sourceRepository.deleteSource(current.sourceId)
                    safAccess.release(current.treeUri)
                    // 换目录后旧 sourceId 的行已经不存在，重扫必须指向新身份，
                    // 否则扫描落到已删除的来源上，新目录永远拿不到卡片。
                    replaced.sourceId
                } else {
                    // 只改名/改显示名时身份不变，仍按原 sourceId 重扫。
                    sourceRepository.saveSource(renamed)
                    renamed.sourceId
                }
            }.onSuccess { rescanSourceId ->
                _state.update { it.copy(editor = null, hint = "已保存") }
                scanCoordinator.rescanSource(rescanSourceId, ScanReason.SAVED_SOURCE)
            }.onFailure { error ->
                _state.update {
                    it.copy(editor = editor.copy(error = "保存失败：${error.message ?: "无法写入本地索引"}"))
                }
            }
        }
    }

    // ------------------------------------------------------------ 删除与扫描

    /** 请求删除：先弹确认（唯一保留确认的操作）。 */
    fun requestDelete(sourceId: String) {
        val source = _state.value.table.rows.firstOrNull { it.source?.sourceId == sourceId }?.source
            ?: return
        _state.update { it.copy(pendingDelete = PendingDelete(source)) }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /**
     * 确认删除：移除索引来源并给出短时撤销。
     *
     * 不删源文件、不删书架、不删译文（开发文档 4.1）。
     */
    fun confirmDelete(): LibrarySource? {
        val pending = _state.value.pendingDelete ?: return null
        _state.update { it.copy(pendingDelete = null, lastDeleted = pending.source) }
        viewModelScope.launch {
            scanCoordinator.cancel(pending.source.sourceId)
            sourceRepository.deleteSource(pending.source.sourceId)
            safAccess.release(pending.source.treeUri)
        }
        return pending.source
    }

    /** 撤销删除：把来源整行写回（顺序保持为追加到表尾，避免与现有行冲突）。 */
    fun undoDelete() {
        val source = _state.value.lastDeleted ?: return
        _state.update { it.copy(lastDeleted = null) }
        viewModelScope.launch {
            safAccess.retain(source.treeUri)
            runCatching { sourceRepository.saveSource(source.copy(orderIndex = -1)) }
                .onSuccess { scanCoordinator.rescanSource(source.sourceId, ScanReason.SAVED_SOURCE) }
                .onFailure { error ->
                    _state.update { it.copy(hint = "撤销失败：${error.message ?: "无法写入本地索引"}") }
                }
        }
    }

    fun consumeHint() {
        _state.update { it.copy(hint = null) }
    }

    /** 从系统授权页返回后刷新状态；界面负责跳转。 */
    fun refreshAccessStatus() {
        viewModelScope.launch { runAccessCheck() }
    }

    /**
     * 授权检测：逐条真实枚举授权根。
     *
     * 只信"记录在不在"是不够的——真机上出现过记录存在但读取被拒的情况，
     * 因此这里用 StorageAccessCoordinator 做一次真实读取，结论写回来源的
     * permission 字段并汇总量级给横幅显示。
     */
    private suspend fun runAccessCheck() {
        val sources = sourceRepository.getSources()
        val report = accessCoordinator.checkAll(sources)
        _state.update {
            it.copy(
                allFilesAccess = report.allFilesAccess,
                allFilesAccessAvailable = report.allFilesAccessAvailable,
                revokedCount = report.revokedSources.size,
            )
        }
    }

    /** 打开扫描诊断（开发文档 4.1「点击失败状态查看原因、重试或授权」）。 */
    fun openDiagnostics(sourceId: String) {
        val source = _state.value.table.rows.firstOrNull { it.source?.sourceId == sourceId }?.source
            ?: return
        _state.update { it.copy(diagnosticsTarget = source) }
    }

    fun closeDiagnostics() {
        _state.update { it.copy(diagnosticsTarget = null) }
    }

    /** 诊断弹窗里的"重试"：只重扫这一个来源。 */
    fun retrySource(source: LibrarySource) {
        viewModelScope.launch {
            scanCoordinator.rescanSource(source.sourceId, ScanReason.REFRESH)
            _state.update { it.copy(diagnosticsTarget = null) }
        }
    }

    /** 强制重新扫描索引：两表已保存配置全部重建（开发文档 4.1）。 */
    fun forceRescan() {
        scanCoordinator.rescanAll(ScanReason.FORCE_REBUILD)
    }

    fun cancelRescan() {
        scanCoordinator.cancelAll()
    }

    /**
     * 完成/下一步。
     *
     * `onboardingCompleted` 一旦置 true 就不再清除：以后用户删空路径，冷启动仍进
     * 书架并显示配置入口，而不是被推回引导页（开发文档 3）。
     */
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            preferences.setOnboardingCompleted(true)
            onDone()
        }
    }

    companion object {
        fun factory(
            isOnboarding: Boolean,
            container: com.lmreader.di.AppContainer,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GalleryPathsViewModel(
                    sourceRepository = container.sourceRepository,
                    accessCoordinator = container.accessCoordinator,
                    treeAccess = container.treeAccess,
                    safAccess = container.safAccess,
                    scanCoordinator = container.scanCoordinator,
                    preferences = container.preferences,
                    isOnboarding = isOnboarding,
                )
            }
        }
    }
}

/** 页面状态。只有一张路径表：图片与归档由同一次扫描一起识别。 */
data class GalleryPathsUiState(
    val isOnboarding: Boolean,
    val table: SourceTableState,
    val overall: OverallScanState = OverallScanState(),
    val scanStates: Map<String, ScanState> = emptyMap(),
    val editor: SourceEditDialogState? = null,
    val pendingDelete: PendingDelete? = null,
    val lastDeleted: LibrarySource? = null,
    /** 正在查看扫描诊断的来源。 */
    val diagnosticsTarget: LibrarySource? = null,
    /** 「全部文件访问」是否已获得（每次启动检测的结果）。 */
    val allFilesAccess: Boolean = false,
    val allFilesAccessAvailable: Boolean = false,
    /** 授权失效的路径数量。 */
    val revokedCount: Int = 0,
    val hint: String? = null,
) {
    /** 至少一个已保存且可读的路径才能进入图库（开发文档 4.1「下一步」）。 */
    val canProceed: Boolean get() = table.rows.any { it.source != null }

    val hasAnySource: Boolean get() = canProceed

    fun withSavedRows(sources: List<LibrarySource>): GalleryPathsUiState {
        val rows = sources.map { SourceRow(source = it, key = it.sourceId) }
        return copy(table = SourceTableState.withRows(rows))
    }
}
