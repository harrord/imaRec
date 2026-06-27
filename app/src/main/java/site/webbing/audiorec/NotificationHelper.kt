package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

const val RECORDING_NOTIFICATION_ID = 1001
private const val RECORDING_CHANNEL_ID = "recording_channel_v2"
private const val LEGACY_CHANNEL_ID = "recording_channel"

/**
 * 录音通知构建器。
 *
 * 录音中 / 暂停态使用自定义 RemoteViews 三部分卡片：
 *   [状态 TextView] —— [分段 Button] —— [暂停/继续 Button]
 *
 * 状态文案仅在状态切换时通过 [updateRecordingNotification] 更新：
 *   - 录音态显示 "人生记录中..."
 *   - 暂停态显示 "人生记录暂停"
 * 不再做周期性 notify() 刷新（原声波动画每 200ms 重建 RemoteViews，
 * 会在用户点击过程中替换 View 层级，导致 ACTION_DOWN/UP 落不到同一 View，
 * 按钮点击被吞掉）。现在 notify() 仅在状态真正变化时触发一次，按钮点击可靠。
 *
 * 按钮外观：
 * - 录音态：分段绿色、暂停绿色（"暂停"文案）
 * - 暂停态：分段灰色禁用、继续琥珀色（"继续"文案）
 * 尺寸固定 72x36dp，13sp 加粗白字，8dp 圆角实心填充。
 */
class NotificationHelper(private val context: Context) {
    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)

        // Android O+ 通道设置一旦创建即冻结，只能换 ID 重建才能改重要级/可见性
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val channel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            "录音状态",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "显示当前录音状态和暂停控制"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannel(channel)
    }

    /**
     * 构建录音通知。仅在状态变化时调用；notify() 不再周期性触发，
     * 避免按钮点击事件被 View 重建吞掉。
     */
    fun buildRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
    ): Notification {
        createChannel()

        val builder = NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("imaRec")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (status) {
            is RecordingStatus.Recording, is RecordingStatus.Paused -> {
                val isPaused = status is RecordingStatus.Paused
                val views = buildCardViews(isPaused)
                builder.setContentText(if (isPaused) "录音已暂停" else "正在录音")
                builder.setCustomContentView(views)
                builder.setCustomBigContentView(views)
            }
            is RecordingStatus.Monitoring -> {
                builder.setContentText("监测中·等待活动")
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    stopPendingIntent(),
                )
            }
            RecordingStatus.Idle -> {
                builder.setContentText("imaRec")
            }
        }

        // MediaSession 仅用于媒体按钮回调（耳机线控等），不再绑定 MediaStyle：
        // 三部分卡片由自定义 RemoteViews 接管锁屏渲染。
        @Suppress("UNUSED_PARAMETER")
        val ignoredToken = mediaSessionToken
        return builder.build()
    }

    /**
     * 状态变化时重建完整通知。这是唯一的 notify() 调用入口，
     * 不再有周期性刷新，因此按钮点击事件不会被 View 重建打断。
     */
    fun updateRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
    ) {
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            buildRecordingNotification(status, mediaSessionToken),
        )
    }

    // ── RemoteViews 构建 ──

    private fun buildCardViews(isPaused: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_recording).apply {
            // 状态文案：录音态显示"人生记录中..."，暂停态显示"人生记录暂停"
            setTextViewText(R.id.status_text, if (isPaused) "人生记录暂停" else "人生记录中...")
            setTextViewText(R.id.toggle_button, if (isPaused) "继续" else "暂停")
            // 暂停态：继续按钮用琥珀色，分段按钮置灰禁用
            // 录音态：暂停按钮用绿色，分段按钮可用绿色
            setInt(
                R.id.toggle_button,
                "setBackgroundResource",
                if (isPaused) R.drawable.btn_toggle_paused else R.drawable.btn_toggle_recording,
            )
            setInt(
                R.id.segment_button,
                "setBackgroundResource",
                if (isPaused) R.drawable.btn_segment_disabled else R.drawable.btn_segment_recording,
            )
            // 暂停态禁用分段按钮的点击（仍显示文案，但不响应）
            if (isPaused) {
                setOnClickPendingIntent(R.id.segment_button, null)
            } else {
                setOnClickPendingIntent(R.id.segment_button, segmentPendingIntent())
            }
            setOnClickPendingIntent(R.id.toggle_button, togglePendingIntent())
        }

    // ── PendingIntent ──

    private fun togglePendingIntent(): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_TOGGLE_PAUSE
        }
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun segmentPendingIntent(): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_MANUAL_SEGMENT
        }
        return PendingIntent.getService(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        return PendingIntent.getService(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
