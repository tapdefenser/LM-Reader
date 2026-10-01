package com.lmreader.core.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream

/** Downloads belong to the process; installed descriptors remain usable across catalog refreshes. */
class TranslationModelManager(private val context: Context, val catalogs: TranslationCatalogStore,
    val installer: ModelPackageInstaller, val sources: TranslationSourceStore,
    private val fallback: TranslationModelCatalog) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val jobs=mutableMapOf<String,Job>()
    private val serial=Semaphore(1)
    private var known=installer.knownPackages(fallback.packs)
    private val retainedMutable=MutableStateFlow(known)
    val retainedPacks=retainedMutable.asStateFlow()
    private val installedMutable=MutableStateFlow(installed())
    val installedPacks=installedMutable.asStateFlow()
    private val mutable=MutableStateFlow(fallback.packs.associate {it.id to idleState(it)})
    val states=mutable.asStateFlow()
    init {
        scope.launch {sources.settings.collectLatest {catalogs.select(it)}}
        scope.launch {catalogs.state.map {it.catalog}.distinctUntilChanged().collect {refreshStates(it)}}
    }
    private fun installed()=known.filter(installer::isInstalled).associateBy {it.id}
    fun installedCatalog()=TranslationModelCatalog.fromPacks(installedPacks.value.values.toList())
    private fun partial(id:String)=known.firstOrNull {it.id==id && installer.hasPartial(it)}
    private fun idleState(pack:TranslationModelPack)=when {
        installedMutable.value.containsKey(pack.id) -> TranslationPackState(TranslationPackStatus.READY)
        partial(pack.id)!=null || installer.hasPartial(pack) -> TranslationPackState(TranslationPackStatus.PAUSED)
        else -> TranslationPackState()
    }
    private fun refreshStates(catalog:TranslationModelCatalog=catalogs.state.value.catalog) {
        known=installer.knownPackages(fallback.packs+catalog.packs)
        retainedMutable.value=known
        installedMutable.value=installed()
        val packs=(catalog.packs+known).distinctBy {it.id}
        mutable.update {previous -> packs.associate {pack ->pack.id to
            (previous[pack.id]?.takeIf {jobs[pack.id]?.isCompleted==false || it.status==TranslationPackStatus.FAILED} ?: idleState(pack))}}
    }
    private fun state(pack:TranslationModelPack,value:TranslationPackState) {mutable.update {it+(pack.id to value)}}
    fun download(pack:TranslationModelPack)=start(pack) {
        val settings=sources.settings.first();val catalog=catalogs.state.value.catalog
        require(catalog.packs.any {it==pack}) {"目录已改变，请重新选择语言包"}
        val previous=partial(pack.id)
        require(previous==null || previous.sameContents(pack)) {"未完成的包版本为 ${previous?.version}，请先取消旧下载再下载新版本"}
        serial.withPermit {
            if(settings.wifiOnly) {
                val cm=context.getSystemService(ConnectivityManager::class.java)
                require(cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) {"当前不是 Wi-Fi；可在下载源设置中允许其他网络"}
            }
            installer.download(pack,{catalog.downloadUrl(pack,it,settings)},{state(pack,it)})
        }
    }
    fun importZip(pack:TranslationModelPack,open:()->InputStream)=start(pack) {serial.withPermit {
        state(pack,TranslationPackState(TranslationPackStatus.VERIFYING,message="校验导入包"))
        withContext(Dispatchers.IO) {open().use {installer.importZip(pack,it)}}
    }}
    private fun start(pack:TranslationModelPack,action:suspend()->Unit) {
        if(jobs[pack.id]?.isCompleted==false || installedMutable.value.containsKey(pack.id)) return
        state(pack,TranslationPackState(TranslationPackStatus.DOWNLOADING,message="等待下载 / 导入"))
        jobs[pack.id]=scope.launch {
            try {action();ensureActive();refreshStates();state(pack,idleState(pack))}
            catch(cancelled:CancellationException) {refreshStates();state(pack,idleState(pack));throw cancelled}
            catch(failure:Exception) {refreshStates();state(pack,TranslationPackState(TranslationPackStatus.FAILED,message=failure.message ?: "语言包安装失败"))}
        }
    }
    fun pause(pack:TranslationModelPack) {jobs[pack.id]?.cancel()}
    fun discard(pack:TranslationModelPack) {
        val previous=jobs[pack.id];previous?.cancel()
        jobs[pack.id]=scope.launch {
            previous?.join()
            withContext(Dispatchers.IO) {(known.filter {it.id==pack.id}+pack).distinctBy {it.identity}.forEach(installer::discardPartial)}
            refreshStates();state(pack,idleState(pack))
        }
    }
    suspend fun remove(pack:TranslationModelPack,translator:BergamotTextTranslator) {
        jobs[pack.id]?.cancelAndJoin()
        val actual=installedMutable.value[pack.id] ?: return
        translator.removePack(actual);refreshStates();state(pack,idleState(catalogs.state.value.catalog.packs.firstOrNull {it.id==pack.id} ?: pack))
    }
}
