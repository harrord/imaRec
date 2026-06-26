package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle

const val RECORDING_NOTIFICATION_ID = 1001
private const val RECORDING_CHANNEL_ID = "recording_channel_v2"
private const val LEGACY_CHANNEL_ID = "recording_channel"

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

    fun buildRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
    ): Notification {
        createChannel()

        val isPaused = status is RecordingStatus.Paused
        val contentText = if (isPaused) "录音已暂停" else "正在录音"
        val actionText = if (isPaused) "继续" else "暂停"
        val actionIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_TOGGLE_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            context,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("imaRec")
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(actionIcon, actionText, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)

        // 绑定 MediaSession 后，系统会在锁屏绘制媒体卡片（跟音乐 App 一样的机制），
        // setShowActionsInCompactView 指定锁屏卡片上显示哪些操作按钮的下标
        if (mediaSessionToken != null) {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(mediaSessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        return builder.build()
    }

    fun updateRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
    ) {
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            buildRecordingNotification(status, mediaSessionToken),
        )
    }
}
