package site.webbing.audiorec

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSessionCompat? = null

    // 锁屏控件周期性刷新协程（每 10 分钟 notify 一次，重新参与系统通知排序）。
    // 仅在 Recording / Paused / Monitoring 态运行；回到 Idle 时由 stopForegroundSafely 取消。
    private var lockscreenRefreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createChannel()

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
                controller.manualSegment()
            }
            ACTION_SWITCH_KB -> {
                performHapticFeedback()
                controller.switchFolder()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 兜底：确保会话资源释放
        if (controller.isActive) controller.stopSession()
        stopLockscreenRefresh()
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
    }

    private fun stopRecording() {
        controller.stopSession()
        // Controller 会在 stopSession 末尾 publish Idle，触发 onStatusUpdate 完成 stopForeground/stopSelf
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
    }
}
