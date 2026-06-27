package site.webbing.audiorec

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
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
import kotlinx.coroutines.withContext
import site.webbing.audiorec.segment.SegmentController
import site.webbing.audiorec.segment.SegmentSettings
import site.webbing.audiorec.segment.StepSensorProvider

/**
 * 录音前台 Service。
 *
 * 自引入自动分段后，本类只负责：
 * - 前台通知（含锁屏三部分卡片：声波 / 分段 / 暂停继续）与 [MediaSessionCompat] 的生命周期与状态同步
 * - Intent 分发（开始 / 停止 / 切换暂停 / 手动分段）
 * - 持有并驱动 [SegmentController]（录音引擎、分片、采样、上传触发均在 Controller 内）
 *
 * 录音状态变更由 Controller 通过 [onStatusUpdate] 回调上报，本类据此更新通知与 MediaSession；
 * 回到 [RecordingStatus.Idle] 时清理前台并 stopSelf。
 *
 * 声波动画：录音中 / 暂停态以 [NotificationHelper.WAVE_REFRESH_MS]（60ms）为间隔生成波形 Bitmap，
 * 在后台线程生成后切回主线程调用 [NotificationHelper.updateWaveBitmap] 仅刷新 ImageView，
 * 不重建整个通知，从而避免卡顿并保留按钮 ripple 反馈。
 */
class RecordingService : Service() {
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var controller: SegmentController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSessionCompat? = null

    /** 声波动画刷新协程，仅在 Recording / Paused 态运行。 */
    private var waveJob: Job? = null
    /** 声波帧序号，持续递增驱动正弦相位前进。 */
    private var waveFrame = 0

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
                    // 状态切换时重建完整通知（更新按钮文案/颜色/点击意图），并启动声波动画
                    val active = status is RecordingStatus.Recording
                    val bmp = NotificationHelper.generateWaveBitmap(waveFrame, active)
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken, bmp)
                    startWaveAnimation()
                }
                is RecordingStatus.Monitoring, RecordingStatus.Idle -> {
                    stopWaveAnimation()
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken, null)
                }
            }
            if (status is RecordingStatus.Idle) {
                stopForegroundSafely()
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TOGGLE_PAUSE -> controller.togglePause()
            ACTION_MANUAL_SEGMENT -> controller.manualSegment()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 兜底：确保会话资源释放
        stopWaveAnimation()
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

    // ── 声波动画 ──

    /**
     * 启动声波刷新循环。录音中生成跳动帧（active=true），暂停态生成静止帧（active=false）。
     *
     * 每帧的 Bitmap 生成在 [Dispatchers.Default] 后台线程进行，避免在主线程做 Canvas 绘制；
     * 生成完成后切回主线程调用 [NotificationHelper.updateWaveBitmap] 仅刷新 ImageView，
     * 不重建整个通知对象，保留按钮的 ripple 点击反馈状态。
     */
    private fun startWaveAnimation() {
        if (waveJob?.isActive == true) return
        waveJob = scope.launch {
            while (isActive) {
                delay(NotificationHelper.WAVE_REFRESH_MS)
                val status = RecordingStateStore.status.value
                val active = status is RecordingStatus.Recording
                if (status !is RecordingStatus.Recording && status !is RecordingStatus.Paused) {
                    // 状态已离开录音/暂停，交由 onStatusUpdate 处理后续通知
                    break
                }
                // 后台线程生成 Bitmap，避免主线程卡顿
                val bmp: Bitmap = withContext(Dispatchers.Default) {
                    NotificationHelper.generateWaveBitmap(waveFrame, active)
                }
                // 主线程刷新通知 ImageView（不重建整个通知）
                notificationHelper.updateWaveBitmap(bmp, isPaused = !active)
                waveFrame++
            }
        }
    }

    private fun stopWaveAnimation() {
        waveJob?.cancel()
        waveJob = null
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
