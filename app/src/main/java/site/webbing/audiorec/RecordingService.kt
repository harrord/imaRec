package site.webbing.audiorec

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 通知刷新策略：notify() 仅在状态真正变化时触发一次，不做周期性刷新。
 * 原声波动画每 200ms 重建 RemoteViews，会在用户点击过程中替换 View 层级，
 * 导致 ACTION_DOWN / ACTION_UP 落不到同一 View，按钮点击被吞掉。
 * 改为静态状态文案（"人生记录中..." / "人生记录暂停"）后，按钮点击可靠。
 */
class RecordingService : Service() {
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var controller: SegmentController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSessionCompat? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TOGGLE_PAUSE -> controller.togglePause()
            ACTION_MANUAL_SEGMENT -> controller.manualSegment()
            ACTION_SWITCH_KB -> controller.switchKnowledgeBase()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 兜底：确保会话资源释放
        if (controller.isActive) controller.stopSession()
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
    }

    private fun stopRecording() {
        controller.stopSession()
        // Controller 会在 stopSession 末尾 publish Idle，触发 onStatusUpdate 完成 stopForeground/stopSelf
    }

    private fun stopForegroundSafely() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
