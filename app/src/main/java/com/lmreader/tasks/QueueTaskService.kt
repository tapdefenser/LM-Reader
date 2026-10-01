package com.lmreader.tasks

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lmreader.MainActivity
import com.lmreader.R
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(kotlinx.coroutines.FlowPreview::class)
class QueueTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { AppContainer.from(this) }
    private val localized by lazy {
        val tag = container.generalPreferences.effectiveLanguageTag
        createConfigurationContext(android.content.res.Configuration(resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(tag))
        })
    }
    private fun label(id: Int, vararg args: Any): String = localized.getString(id, *args)
    private var observing = false
    private var startupFailed = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, label(R.string.lmreader_task_channel), NotificationManager.IMPORTANCE_LOW))
        if (!promote()) return
        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LMReader:ChapterTasks")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupFailed) { stopSelf(); return START_NOT_STICKY }
        // A new lease may arrive while an idle instance is waiting for stopSelf.
        // Re-promote and acknowledge every start, including reused service instances.
        if (!promote()) return START_NOT_STICKY
        if (intent?.action == PAUSE) { container.translationQueue.pause(); container.exportQueue.pauseAll() }
        if (!observing) {
            observing = true
            scope.launch {
                combine(container.translationQueue.items, container.exportQueue.tasks,
                    container.translationQueue.currentStep, container.taskService.leases) { translations, exports, step, leases ->
                    val t = translations.count { it.state == "PENDING" || it.state == "RUNNING" }
                    val e = exports.count { it.state == "PENDING" || it.state == "RUNNING" }
                    leases to (label(R.string.lmreader_task_counts, t, e) +
                        (step?.let { " · ${it.pageName}" } ?: exports.firstOrNull { it.state == "RUNNING" }?.let {
                            " · ${it.completedPages}/${it.totalPages}" }.orEmpty()))
                }.debounce(300).collect { (leases, text) ->
                    if (leases == 0) container.taskService.stopIfIdle {
                        ServiceCompat.stopForeground(this@QueueTaskService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
                    } else getSystemService(NotificationManager::class.java).notify(ID, notification(text))
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.taskService.serviceStopped(label(R.string.lmreader_task_timeout), this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        container.taskService.serviceStopped(label(R.string.lmreader_task_stopped), this)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun promote(): Boolean {
        return try {
            ServiceCompat.startForeground(this, ID, notification(label(R.string.lmreader_task_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            container.taskService.serviceReady(this); true
        } catch (error: Exception) {
            startupFailed = true; container.taskService.failStartup(error); stopSelf(); false
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            putExtra("open-queue", if (container.translationQueue.currentStep.value != null || container.translationQueue.items.value.any { it.state == "RUNNING" }) "translation" else "export")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, QueueTaskService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.lmreader_ic_task)
            .setContentTitle(label(R.string.lmreader_task_title)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, label(R.string.lmreader_task_pause), pause).build()
    }
    companion object { private const val CHANNEL = "chapter-tasks"; private const val ID = 2101; private const val PAUSE = "com.lmreader.tasks.PAUSE" }
}
