package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.sin

const val RECORDING_NOTIFICATION_ID = 1001
private const val RECORDING_CHANNEL_ID = "recording_channel_v2"
private const val LEGACY_CHANNEL_ID = "recording_channel"

/**
 * 录音通知构建器。
 *
 * 录音中 / 暂停态使用自定义 RemoteViews 三部分卡片：
 *   [声波 ImageView] —— [分段 Button] —— [暂停/继续 Button]
 *
 * 声波为虚假动画：由 [RecordingService] 每 [WAVE_REFRESH_MS] 生成一帧波形 Bitmap，
 * 通过 [updateWaveBitmap] 仅刷新 ImageView（不重建整个通知），避免通知频繁重建导致的卡顿
 * 和按钮 ripple 反馈被打断。
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
     * 构建录音通知。仅在状态变化时调用；声波动画期间用 [updateWaveBitmap] 增量刷新。
     */
    fun buildRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
        waveBitmap: Bitmap? = null,
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
                val views = buildCardViews(isPaused, waveBitmap)
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
     * 状态变化时重建完整通知。
     */
    fun updateRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
        waveBitmap: Bitmap? = null,
    ) {
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            buildRecordingNotification(status, mediaSessionToken, waveBitmap),
        )
    }

    /**
     * 仅刷新声波 ImageView 的 Bitmap，不重建整个通知。
     *
     * 通过构造一个只含 ImageView 更新的 RemoteViews 应用到已显示的通知上，
     * 系统只会重绘 ImageView 区域，按钮等其他 view 保持不变（包括 ripple 状态）。
     * 这是避免声波动画期间通知频繁重建导致卡顿的关键。
     */
    fun updateWaveBitmap(bitmap: Bitmap) {
        val views = RemoteViews(context.packageName, R.layout.notification_recording)
        views.setImageViewBitmap(R.id.wave_image, bitmap)
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCustomContentView(views)
                .setCustomBigContentView(views)
                .build(),
        )
    }

    // ── RemoteViews 构建 ──

    private fun buildCardViews(isPaused: Boolean, waveBitmap: Bitmap?): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_recording).apply {
            setImageViewBitmap(R.id.wave_image, waveBitmap)
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

    companion object {
        /** 声波动画刷新间隔（毫秒）。60ms ≈ 17fps，视觉连续且不会过载通知系统。 */
        const val WAVE_REFRESH_MS = 60L

        /**
         * 生成第 [frameIndex] 帧的声波 Bitmap。
         *
         * 7 条圆角竖线，高度按正弦波动叠加随机扰动，[frameIndex] 递增形成连续跳动。
         * [active] 为 false（暂停态）时绘制灰色静止条。
         * 比 12 帧版本更密集的竖线 + 更短帧间隔，视觉更流畅。
         */
        fun generateWaveBitmap(frameIndex: Int, active: Boolean): Bitmap {
            val width = 240
            val height = 80
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val color = if (active) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }

            val bars = 7
            val barWidth = 16
            val gap = 16
            val totalWidth = bars * barWidth + (bars - 1) * gap
            val startX = (width - totalWidth) / 2
            val radius = (barWidth / 2f)

            for (i in 0 until bars) {
                val hRatio = if (active) {
                    // 主正弦 + 次谐波 + 与帧/位置相关的扰动，模拟自然声波起伏
                    val phase = 2.0 * Math.PI * (frameIndex / 12.0 + i * 0.15)
                    val secondary = 2.0 * Math.PI * (frameIndex / 20.0 - i * 0.1)
                    0.2 + 0.5 * (0.5 + 0.5 * sin(phase)) + 0.25 * (0.5 + 0.5 * sin(secondary))
                } else {
                    0.15
                }
                val safeRatio = hRatio.coerceIn(0.1, 0.95)
                val barHeight = (height * safeRatio).toInt().coerceAtLeast(barWidth)
                val left = startX + i * (barWidth + gap)
                val top = (height - barHeight) / 2
                val right = left + barWidth
                val bottom = top + barHeight
                canvas.drawRoundRect(
                    left.toFloat(),
                    top.toFloat(),
                    right.toFloat(),
                    bottom.toFloat(),
                    radius,
                    radius,
                    paint,
                )
            }
            return bmp
        }
    }
}
