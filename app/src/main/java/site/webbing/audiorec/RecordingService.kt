package site.webbing.audiorec

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import site.webbing.audiorec.segment.SegmentController
import site.webbing.audiorec.segment.SegmentSettings
import site.webbing.audiorec.segment.StepSensorProvider

/**
 * 录音前台 Service。
 *
 * 自引入自动分段后，本类只负责：
 * - 前台通知（含锁屏三部分卡片：状态文案 / 分段 / 暂停继续）与 [MediaSessionCompat] 的生命周期与状态同步
 * - Intent 分发（开始 / 停止 / 切换暂停 / 手动分段）
 * - 持有并驱动 [SegmentController]（录音引擎、分片、采样、上传触发均在 Controller 内）
 *
 * 录音状态变更由 Controller 通过 [onStatusUpdate] 回调上报，本类据此更新通知与 MediaSession；
 * 回到 [RecordingStatus.Idle] 时清理前台并 stopSelf。
 *
 * 通知刷新策略：
 * - 状态变化时立即重建一次（更新按钮文案/颜色/点击意图）。
 * - 此外每 10 分钟周期性 notify() 一次，重新参与系统通知排序计算，
 *   在其他媒体 APP（音乐软件等）退出或锁屏控件被挤下后让本 APP 控件重新回到锁屏可见位置。
 *   依赖 [setOnlyAlertOnce]，周期性刷新不会响铃/震动，用户无感知。
 *   间隔取 10 分钟（而非历史上放弃的 200ms 声波动画），用户点击撞上重建的概率极低。
 *   周期性刷新需要 WakeLock 保证设备休眠时协程仍能执行；WakeLock 生命周期与刷新协程绑定。
 */
class RecordingService : Service() {
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var controller: SegmentController
    private lateinit var imaSettings: ImaSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSessionCompat? = null

    // 锁屏控件周期性刷新协程（每 10 分钟 notify 一次，重新参与系统通知排序）。
    // 仅在 Recording / Paused / Monitoring 态运行；回到 Idle 时由 stopForegroundSafely 取消。
    private var lockscreenRefreshJob: Job? = null

    /**
     * 灵感文件夹配置变更监听协程。录音会话期间，用户在设置中配置/取消配置灵感文件夹后，
     * 锁屏「灵感」按钮的启用/禁用态需随之更新。仅监听 [ImaConfig.inspirationFolderId] 变化，
     * 变化时重建通知刷新按钮。仅在活跃会话期间运行；回到 Idle 时取消。
     */
    private var configObserverJob: Job? = null

    /**
     * 屏幕熄屏广播接收器：仅在录音会话活跃期间注册。
     *
     * 收到 [Intent.ACTION_SCREEN_OFF] 时调用 [SegmentController.stopInspirationByScreenOff]，
     * 由 Controller 内部判断是否处于灵感模式：是则保存灵感并切回普通录音，否则忽略。
     * 仅在录音会话活跃期间注册，避免全局监听带来的不必要开销；会话结束（Idle）时立即注销。
     */
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createChannel()
        imaSettings = ImaSettings.get(this)

        controller = SegmentController(
            service = this,
            fileManager = RecordingFileManager(this),
            uploader = ImaUploader.get(this),
            settings = SegmentSettings.get(this),
            imaSettings = ImaSettings.get(this),
            stepProvider = StepSensorProvider.get(this),
            scope = scope,
        )
        controller.onStatusUpdate = { status ->
            updateMediaSessionState(status)
            when (status) {
                is RecordingStatus.Recording, is RecordingStatus.Paused -> {
                    // 状态切换时重建通知：更新按钮文案/颜色/点击意图，并清除可能残留的分组反馈行
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken)
                }
                is RecordingStatus.Monitoring, RecordingStatus.Idle -> {
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken)
                }
            }
            if (status is RecordingStatus.Idle) {
                stopForegroundSafely()
                stopSelf()
            }
        }
        controller.onGroupFeedback = { text ->
            // 分组点击反馈：在按钮行下方临时显示一行文本；null 表示清除
            val status = RecordingStateStore.status.value
            val token = mediaSession?.sessionToken
            if (text == null) {
                notificationHelper.clearGroupFeedback(status, token)
            } else {
                notificationHelper.showGroupFeedback(status, text, token)
            }
        }
        controller.onPauseFeedback = { toggleText, hintText ->
            // 暂停选择窗口 / 定时暂停倒计时反馈：刷新按钮文本与提示行文案
            val status = RecordingStateStore.status.value
            val token = mediaSession?.sessionToken
            notificationHelper.updatePauseFeedback(status, toggleText, hintText, token)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TOGGLE_PAUSE -> {
                performHapticFeedback()
                controller.togglePause()
            }
            ACTION_MANUAL_SEGMENT -> {
                performHapticFeedback()
                scope.launch { controller.manualSegment() }
            }
            ACTION_SWITCH_KB -> {
                performHapticFeedback()
                controller.switchFolder()
            }
            ACTION_GEO_TRIGGER_SEGMENT -> {
                val label = intent.getStringExtra(EXTRA_GEO_LABEL) ?: ""
                if (controller.isActive) {
                    // 任何活跃状态（Recording/Paused/Monitoring）都强制分段+开新段带 label
                    scope.launch { controller.forceSegmentByGeoTrigger(label) }
                } else {
                    // Idle 态：设置 label 后启动新录音会话，文件名带 label
                    controller.setPendingGeoLabel(label)
                    startRecording()
                }
            }
            ACTION_GEO_TRIGGER_STOP -> {
                android.util.Log.i("GeoTriggerDebug", "RecordingService: ACTION_GEO_TRIGGER_STOP received, isActive=${controller.isActive}")
                if (controller.isActive) {
                    android.util.Log.i("GeoTriggerDebug", "RecordingService: stopping recording due to geo leave")
                    stopRecording()
                } else {
                    android.util.Log.i("GeoTriggerDebug", "RecordingService: not recording, ignore geo stop")
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 兜底：确保会话资源释放
        // 使用 runBlocking 同步等待 stopSession 完成，确保 WakeLock/传感器在 scope.cancel 前释放
        // 限制 5 秒超时：若 recorder.stop() 等异常卡住，放弃等待以避免主线程 ANR；
        // WakeLock 自身有 4 小时超时兜底，不会永久泄漏
        if (controller.isActive) {
            runBlocking {
                withTimeoutOrNull(5_000L) { controller.stopSession() }
            }
        }
        stopLockscreenRefresh()
        unregisterScreenOffReceiver()
        scope.cancel()
        releaseMediaSession()
        RecordingStateStore.update(RecordingStatus.Idle)
        super.onDestroy()
    }

    private fun startRecording() {
        if (controller.isActive) return
        setupMediaSession()
        // 必须在 5s 内 startForeground，先用初始通知占位，
        // Controller.startSession() 会立即回调 onStatusUpdate 更新为真实状态。
        startForeground(
            RECORDING_NOTIFICATION_ID,
            notificationHelper.buildRecordingNotification(RecordingStatus.Idle, mediaSession?.sessionToken),
        )
        controller.startSession()
        startLockscreenRefresh()
        startConfigObserver()
        // 注册屏幕熄屏监听：用户按电源键熄屏时，若处于灵感模式则自动保存灵感
        registerScreenOffReceiver()
    }

    private fun stopRecording() {
        // 异步停止：文件 I/O（getSegmentDurationMs）在 IO 线程执行，不阻塞主线程
        // Controller 会在 stopSession 末尾 publish Idle，触发 onStatusUpdate 完成 stopForeground/stopSelf
        scope.launch { controller.stopSession() }
    }

    private fun performHapticFeedback() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    private fun stopForegroundSafely() {
        stopLockscreenRefresh()
        stopConfigObserver()
        unregisterScreenOffReceiver()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ── 锁屏控件周期性刷新 ──

    /**
     * 启动周期性刷新协程：每 10 分钟 notify() 一次当前录音状态通知。
     *
     * 目的：让本 APP 通知重新参与系统排序计算，在其他媒体 APP 退出或锁屏控件被挤下后
     * 让本 APP 控件重新回到锁屏可见位置。依赖 NotificationCompat.setOnlyAlertOnce，
     * 不会响铃/震动，用户无感知。
     *
     * 仅在录音会话活跃期间运行（Recording / Paused / Monitoring）；回到 Idle 时协程自行退出。
     * 需要 WakeLock 保证设备休眠时协程仍能执行；WakeLock 生命周期与本协程绑定，
     * 协程取消/异常时通过 finally 自动释放，不会泄漏。
     */
    private fun startLockscreenRefresh() {
        if (lockscreenRefreshJob?.isActive == true) return
        lockscreenRefreshJob = scope.launch {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "imaRec:lockscreen_refresh",
            ).apply { setReferenceCounted(false) }
            // 单次 acquire 上限 15 分钟，略大于 10 分钟刷新间隔，
            // 防止协程异常退出时 WakeLock 永久泄漏。
            wakeLock.acquire(15 * 60 * 1000L)
            try {
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    val status = RecordingStateStore.status.value
                    if (status is RecordingStatus.Idle) break
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken)
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun stopLockscreenRefresh() {
        lockscreenRefreshJob?.cancel()
        lockscreenRefreshJob = null
    }

    // ── 灵感文件夹配置变更监听 ──

    /**
     * 启动灵感文件夹配置变更监听协程。
     *
     * 录音会话期间，用户可能在设置中配置或取消配置灵感目标文件夹。此时锁屏「灵感」按钮的
     * 启用/禁用态需随之更新：未配置时置灰不可点击，配置后变为可用。
     *
     * 仅监听 [ImaConfig.inspirationFolderId] 字段变化（[map] + [distinctUntilChanged] 过滤），
     * 避免其他配置项变化（如 activeFolders、currentFolderId）触发不必要的通知重建。
     * 变化时以当前录音状态重建通知，刷新按钮可用态。回到 Idle 时由 [stopConfigObserver] 取消。
     */
    private fun startConfigObserver() {
        if (configObserverJob?.isActive == true) return
        configObserverJob = scope.launch {
            imaSettings.config
                .map { it.inspirationFolderId }
                .distinctUntilChanged()
                .collect {
                    val status = RecordingStateStore.status.value
                    if (status is RecordingStatus.Idle) return@collect
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken)
                }
        }
    }

    private fun stopConfigObserver() {
        configObserverJob?.cancel()
        configObserverJob = null
    }

    // ── 屏幕熄屏监听（灵感模式自动保存） ──

    /**
     * 注册 [Intent.ACTION_SCREEN_OFF] 广播接收器。幂等：已注册时直接返回，
     * 避免 startRecording() 重复调用时重复注册。
     *
     * 收到熄屏广播后调用 [SegmentController.stopInspirationByScreenOff]，
     * Controller 内部判断若处于灵感模式则保存灵感并切回普通录音，否则忽略。
     */
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    controller.stopInspirationByScreenOff()
                }
            }
        }
        // ACTION_SCREEN_OFF 为系统广播，仅系统可发送；使用 RECEIVER_NOT_EXPORTED 表明
        // 不接收其他 APP 的广播，同时满足 targetSdk 34+ 对动态注册的 flag 要求。
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffReceiver = receiver
    }

    /**
     * 注销屏幕熄屏广播接收器。幂等：未注册时直接返回，注销后置空，
     * 避免 stopForegroundSafely() 与 onDestroy() 重复注销抛 IllegalArgumentException。
     */
    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // 防御性：极端情况下重复注销不崩溃
            }
        }
        screenOffReceiver = null
    }

    // ── MediaSession ──

    private fun setupMediaSession() {
        releaseMediaSession()
        val openAppIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSessionCompat(this, "imaRecRecording").apply {
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (RecordingStateStore.status.value is RecordingStatus.Paused) this@RecordingService.controller.togglePause()
                }

                override fun onPause() {
                    if (RecordingStateStore.status.value is RecordingStatus.Recording) this@RecordingService.controller.togglePause()
                }

                override fun onStop() {
                    stopRecording()
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState(status: RecordingStatus) {
        val session = mediaSession ?: return
        val (state, title) = when (status) {
            is RecordingStatus.Recording -> PlaybackStateCompat.STATE_PLAYING to "正在录音"
            is RecordingStatus.Paused -> PlaybackStateCompat.STATE_PAUSED to "录音已暂停"
            is RecordingStatus.Monitoring -> PlaybackStateCompat.STATE_PAUSED to "监测中·等待活动"
            RecordingStatus.Idle -> PlaybackStateCompat.STATE_NONE to "imaRec"
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "imaRec")
            .build()
        session.setMetadata(metadata)
    }

    private fun releaseMediaSession() {
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
    }

    companion object {
        const val ACTION_START_RECORDING = "site.webbing.audiorec.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "site.webbing.audiorec.action.STOP_RECORDING"
        const val ACTION_TOGGLE_PAUSE = "site.webbing.audiorec.action.TOGGLE_PAUSE"
        const val ACTION_MANUAL_SEGMENT = "site.webbing.audiorec.action.MANUAL_SEGMENT"
        const val ACTION_SWITCH_KB = "site.webbing.audiorec.action.SWITCH_KB"
        /** 地理触发：强制分段并开新录音（文件名带地点备注）。 */
        const val ACTION_GEO_TRIGGER_SEGMENT = "site.webbing.audiorec.action.GEO_TRIGGER_SEGMENT"
        /** 地理触发：离开范围时停止录音。 */
        const val ACTION_GEO_TRIGGER_STOP = "site.webbing.audiorec.action.GEO_TRIGGER_STOP"
        /** Intent extra：地点备注，随 [ACTION_GEO_TRIGGER_SEGMENT] 传递。 */
        const val EXTRA_GEO_LABEL = "geo_label"

        // 锁屏控件周期性刷新间隔：10 分钟。
        // 取 10 分钟（而非历史上放弃的 200ms 声波动画），用户点击撞上重建的概率极低，
        // 且依赖 setOnlyAlertOnce 不会响铃/震动，用户无感知。
        private const val REFRESH_INTERVAL_MS = 10L * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_RECORDING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }

        fun togglePause(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
            context.startService(intent)
        }

        /**
         * 地理触发：强制对当前录音会话分段并开新录音，新段文件名带 [label]。
         *
         * 由 [LocationTriggerService] 在设备进入预设地点偏差范围内时调用。
         * 若当前未在录音（Idle），会启动新的录音会话，文件名带 [label]。
         * 若正在录音（任何活跃状态），强制分段+开新段。
         *
         * 使用 startForegroundService 以保证 RecordingService 在后台被唤起时能升为前台。
         */
        fun triggerGeoSegment(context: Context, label: String) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_GEO_TRIGGER_SEGMENT
                putExtra(EXTRA_GEO_LABEL, label)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 地理触发：离开范围时停止录音。
         *
         * 由 [LocationTriggerService] 在设备离开预设地点偏差范围且
         * [GeoTriggerConfig.leaveToStop] 开启时调用。若当前未在录音则忽略。
         */
        fun triggerGeoStop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_GEO_TRIGGER_STOP
            }
            context.startService(intent)
        }
    }
}
