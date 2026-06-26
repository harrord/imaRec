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

        // 不同状态：文案、是否显示暂停/继续按钮不同
        val contentText: String
        val toggleAction: Pair<String, Int>? // (文案, 图标) ，null 表示该状态不显示切换按钮
        when (status) {
            is RecordingStatus.Recording -> {
                contentText = "正在录音"
                toggleAction = "暂停" to android.R.drawable.ic_media_pause
            }
            is RecordingStatus.Paused -> {
                contentText = "录音已暂停"
                toggleAction = "继续" to android.R.drawable.ic_media_play
            }
            is RecordingStatus.Monitoring -> {
                // 间隔期：录音未停止，但不写文件，等待"继续条件"满足。不提供暂停按钮。
                contentText = "监测中·等待活动"
                toggleAction = null
            }
            RecordingStatus.Idle -> {
                contentText = "imaRec"
                toggleAction = null
            }
        }

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

        // 切换按钮（暂停/继续）按状态添加，监测期不显示
        toggleAction?.let { (text, icon) ->
            builder.addAction(icon, text, togglePendingIntent)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)

        // 绑定 MediaSession 后，系统会在锁屏绘制媒体卡片（跟音乐 App 一样的机制），
        // setShowActionsInCompactView 指定锁屏卡片上显示哪些操作按钮的下标。
        // 有切换按钮时显示 [切换, 停止] 两个；否则只显示 [停止]。
        if (mediaSessionToken != null) {
            val compactIndices = if (toggleAction != null) intArrayOf(0, 1) else intArrayOf(0)
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(mediaSessionToken)
                    .setShowActionsInCompactView(*compactIndices)
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
