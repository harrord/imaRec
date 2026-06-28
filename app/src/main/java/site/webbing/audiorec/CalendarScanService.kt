package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闪念胶囊：后台定时扫描系统日历的前台服务。
 *
 * 仿照 [LocationTriggerService]：前台服务 + 协程 `while(isActive){ scanOnce(); delay(interval) }`
 * + WakeLock + 独立通知渠道。锁屏也能扫（前台服务不受 Doze 限制）。
 *
 * 工作模式：
 * - 启动时立即扫描一次，之后按 [CalendarCapsuleConfig.scanIntervalMinutes] 循环
 * - 每次扫描调用 [CalendarReader.queryUpcomingEvents] 查询未来 7 天日程，
 *   再用 [CalendarReader.filterMatching] 按锚点小时 + 去重筛选
 * - 匹配的日程全部调用 [ImaUploader.createTextNote] 建笔记上传到 IMA
 * - 笔记创建成功才标记 processed，失败不标记，下次扫描重试
 * - 每次扫描结束（无论是否匹配到事件）都刷新通知，保证配置变更后通知立即更新
 *
 * 扫描循环内检测权限：无 READ_CALENDAR 时优雅停止服务 + Toast 提示。
 * 支持 [ACTION_REFRESH] action：收到后只刷新通知、不重启扫描循环。
 */
class CalendarScanService : Service() {
    // 使用 Default 调度器：日历查询（ContentResolver）和笔记上传都是 I/O 密集型操作，
    // 不应在主线程执行，否则事件数量多时会阻塞主线程导致 ANR。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: CalendarCapsuleSettings
    private lateinit var imaSettings: ImaSettings
    private var wakeLock: PowerManager.WakeLock? = null
    private var scanJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = CalendarCapsuleSettings.get(this)
        imaSettings = ImaSettings.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // ACTION_REFRESH 仅刷新通知，不重启扫描循环
        if (intent?.action != ACTION_REFRESH) {
            startScanLoop()
        } else {
            Log.d(TAG, "onStartCommand: ACTION_REFRESH, notification updated")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ── 扫描循环 ──

    private fun startScanLoop() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            Log.d(TAG, "scan loop started")
            while (isActive) {
                // 每次扫描前短时持有 WakeLock 保证 CPU 清醒，扫描完立即释放，
                // delay 等待期间不持有 WakeLock，让 CPU 能进入深度睡眠以省电
                acquireWakeLock()
                try {
                    runCatching { scanOnce() }
                        .onFailure { Log.e(TAG, "scan failed", it) }
                } finally {
                    releaseWakeLock()
                }
                val intervalMin = settings.config.value.scanIntervalMinutes.coerceIn(1, 5)
                delay(intervalMin * 60_000L)
            }
        }
    }

    /**
     * 执行一次日历扫描与笔记创建。
     *
     * - 配置禁用时直接返回
     * - 无 READ_CALENDAR 权限时优雅停止服务 + Toast 提示
     * - 查询未来 7 天日程，按锚点小时 + 去重筛选
     * - 匹配的日程全部建笔记上传到 IMA
     * - 无论是否匹配到事件都刷新通知
     */
    private suspend fun scanOnce() {
        val config = settings.config.value
        if (!config.enabled) {
            Log.d(TAG, "scanOnce: disabled, skip")
            updateNotification()
            return
        }

        // 权限检测：无 READ_CALENDAR 时优雅停止服务 + Toast 提示
        if (!CalendarReader.hasReadCalendarPermission(this)) {
            Log.w(TAG, "scanOnce: no READ_CALENDAR permission, stopping service")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@CalendarScanService,
                    "缺少日历读取权限，闪念胶囊已停止",
                    Toast.LENGTH_LONG,
                ).show()
            }
            settings.setEnabled(false)
            stopSelf()
            return
        }

        // 基线快照：用户首次开启闪念胶囊时，把当时所有未来 7 天内的事件全部记入
        // processedEventIds（不区分锚点小时），防止历史日程被误上传。
        // 之后只有快照后新创建的事件才会被处理。一旦完成永远不再重复。
        if (!config.baselineSnapshotDone) {
            Log.d(TAG, "scanOnce: doing baseline snapshot (first time enabled)")
            val baselineEvents = CalendarReader.queryUpcomingEvents(this)
            settings.markEventsProcessed(baselineEvents.map { it.id })
            settings.markBaselineDone()
            Log.d(
                TAG,
                "baseline snapshot done: marked ${baselineEvents.size} existing events as processed, " +
                    "will only process newly created events from now on"
            )
            updateNotification()
            return // 基线快照本轮不处理任何事件，下一轮扫描开始正常处理
        }

        Log.d(
            TAG,
            "scanOnce: start, anchorHour=${config.anchorHour} interval=${config.scanIntervalMinutes}min " +
                "processed=${config.processedEventIds.size} folderId=\"${config.targetFolderId}\""
        )

        val events = CalendarReader.queryUpcomingEvents(this)
        val matched = CalendarReader.filterMatching(
            events,
            config.anchorHour,
            config.processedEventIds,
        )
        Log.d(TAG, "scanOnce: queried=${events.size} matched=${matched.size}")

        // 处理每个匹配的日程：全部都建笔记
        for (event in matched) {
            processEvent(event, config)
        }

        // 每次扫描结束（无论是否匹配到事件）都刷新通知
        updateNotification()
    }

    /**
     * 处理单个匹配的日程：提取标题和正文，上传到 IMA。
     *
     * - 笔记标题 = 事件 TITLE（截断到 60 字符），TITLE 为空则用 闪念_yyyyMMddHHmmss
     * - 笔记正文 = TITLE + "\n\n" + DESCRIPTION
     * - TITLE 和 DESCRIPTION 都为空 → 跳过该日程
     * - 笔记创建成功才标记 processed，失败不标记，下次扫描重试
     */
    private suspend fun processEvent(event: CalendarEvent, config: CalendarCapsuleConfig) {
        val title = event.title.take(60).ifBlank {
            "闪念_" + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        }
        // TITLE 和 DESCRIPTION 都为空 → 跳过该日程
        if (event.title.isBlank() && event.description.isBlank()) {
            Log.d(TAG, "processEvent: skip event ${event.id}, both title and description are empty")
            return
        }
        // 笔记正文 = TITLE + "\n\n" + DESCRIPTION
        val body = event.title + "\n\n" + event.description
        // markdown content = "# 标题\n\n正文"
        val markdown = "# $title\n\n$body"

        Log.d(TAG, "processEvent: event ${event.id} title=\"$title\" bodyLen=${body.length}")

        // 上传笔记到 IMA（两步流程）
        val imaConfig = imaSettings.config.value
        if (!imaConfig.isConfigured) {
            Log.w(TAG, "processEvent: IMA not configured, skip marking processed (will retry next scan)")
            return
        }
        val folderId = config.targetFolderId.ifBlank { imaConfig.currentFolderId }
        val uploader = ImaUploader.get(this)
        val success = runCatching {
            uploader.createTextNote(title, markdown, folderId)
        }.getOrElse { e ->
            Log.e(TAG, "processEvent: createTextNote failed for event ${event.id}", e)
            false
        }
        if (success) {
            // 笔记创建成功才标记 processed，失败不标记，下次扫描重试
            settings.markEventProcessed(event.id)
            Log.d(TAG, "processEvent: event ${event.id} processed and marked")
        } else {
            Log.w(TAG, "processEvent: createTextNote failed for event ${event.id}, will retry next scan")
        }
    }

    // ── 通知 ──

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "闪念胶囊",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台定时扫描系统日历，匹配日程时创建笔记上传到 IMA"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val config = settings.config.value
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val text = "锚点 ${config.anchorHour}:00 · 间隔 ${config.scanIntervalMinutes} 分钟 · 最近扫描 $now"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("imaRec 闪念胶囊")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification() {
        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    // ── WakeLock ──

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 30 秒超时：仅保证单次扫描期间 CPU 清醒，扫描完立即释放。
        // delay 等待期间不持有 WakeLock，让 CPU 能深度睡眠以省电。
        // 超时兜底防止异常路径下 WakeLock 泄漏。
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "imaRec:calendar_capsule_scan",
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "CalendarScanService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "calendar_capsule_channel"
        private const val ACTION_REFRESH = "site.webbing.audiorec.action.REFRESH_CALENDAR"

        /** 启动闪念胶囊扫描服务。仅在 [CalendarCapsuleConfig.enabled] 开启时调用。 */
        fun start(context: Context) {
            val intent = Intent(context, CalendarScanService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止闪念胶囊扫描服务。仅在 [CalendarCapsuleConfig.enabled] 关闭时调用。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, CalendarScanService::class.java))
        }

        /**
         * 刷新通知（不重启扫描循环）。设置页锚点小时、扫描间隔、目标文件夹变更时调用。
         * 若服务未运行会拉起服务（此时等同于 [start]）。
         */
        fun refresh(context: Context) {
            val intent = Intent(context, CalendarScanService::class.java).apply {
                action = ACTION_REFRESH
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
