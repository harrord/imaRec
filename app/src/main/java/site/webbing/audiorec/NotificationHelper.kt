package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
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
 *   [分组 Button] —— [分段 Button] —— [暂停/继续 Button]
 *
 * 状态文案已移除，录音/暂停状态仅靠按钮体现（分段置灰=暂停，暂停↔继续文字切换）。
 * 不再做周期性 notify() 刷新（原声波动画每 200ms 重建 RemoteViews，
 * 会在用户点击过程中替换 View 层级，导致 ACTION_DOWN/UP 落不到同一 View，
 * 按钮点击被吞掉）。notify() 仅在状态变化、分组点击反馈、5 秒倒计时结束时触发，均为事件驱动。
 *
 * 分组按钮：切换知识库并启动 5 秒后台倒计时，结束后执行分段。点击反馈通过
 * [feedbackText] 参数在按钮行下方的固定提示行显示（高度恒定，避免触发锁屏卡片展开/收缩）。
 * 提示行始终可见：无反馈时显示默认文案（录音中/已暂停），有反馈时显示反馈文案，
 * 倒计时结束后由调用方传入 null 恢复默认文案。
 *
 * 暂停按钮连续点击循环（5 秒选择窗口）：
 * - Recording 态点击 → 进入选择窗口，按钮文本保持"暂停"、提示"5 秒后暂停"
 * - 5 秒内连续点击 → 按 4 档循环（一直暂停 / X 分钟 / Y 分钟 / Z 分钟）切换提示
 * - 5 秒到点 → 真正暂停，进入 PausedForever（按钮"继续"、提示"人生记录已暂停"）
 *   或 PausedTimed（按钮"继续"、提示"暂停剩余 N 分钟"，每分钟递减）
 * - 暂停态点击 → 立即恢复录音
 *
 * 按钮外观：
 * - 录音态：分组/分段绿色、暂停绿色（"暂停"文案）
 * - 选择窗口态：分组/分段灰色禁用、暂停绿色（"暂停"文案）
 * - 暂停态：分组/分段灰色禁用、继续琥珀色（"继续"文案）
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
     * 构建录音通知。仅在状态变化或分组反馈更新时调用；notify() 不再周期性触发，
     * 避免按钮点击事件被 View 重建吞掉。
     *
     * @param feedbackText 分组反馈行文本，null 或空串表示隐藏反馈行
     */
    fun buildRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
        feedbackText: String? = null,
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
                val views = buildCardViews(isPaused, feedbackText, status)
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
     * 状态变化时重建完整通知。这是唯一的 notify() 调用入口（分组反馈更新除外），
     * 不再有周期性刷新，因此按钮点击事件不会被 View 重建打断。
     */
    fun updateRecordingNotification(
        status: RecordingStatus,
        mediaSessionToken: MediaSessionCompat.Token? = null,
    ) {
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            buildRecordingNotification(status, mediaSessionToken, null),
        )
    }

    /**
     * 更新分组反馈行：显示反馈文本。用于点击分组按钮后展示当前选中知识库。
     * 仅在 Recording / Paused 态有效，其他态忽略。
     */
    fun showGroupFeedback(status: RecordingStatus, text: String, mediaSessionToken: MediaSessionCompat.Token? = null) {
        if (status !is RecordingStatus.Recording && status !is RecordingStatus.Paused) return
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            buildRecordingNotification(status, mediaSessionToken, text),
        )
    }

    /**
     * 清除分组反馈行。用于 5 秒倒计时结束或被取消后隐藏反馈文本。
     */
    fun clearGroupFeedback(status: RecordingStatus, mediaSessionToken: MediaSessionCompat.Token? = null) {
        if (status !is RecordingStatus.Recording && status !is RecordingStatus.Paused) return
        NotificationManagerCompat.from(context).notify(
            RECORDING_NOTIFICATION_ID,
            buildRecordingNotification(status, mediaSessionToken, null),
        )
    }

    /**
     * 暂停选择窗口 / 定时暂停倒计时期间的反馈刷新。
     *
     * 用于 [SegmentController.onPauseFeedback] 回调，按当前阶段更新：
     * - 暂停按钮文本：选择窗口期间"暂停"，暂停态"继续"
     * - 提示行文案：选择窗口期间显示当前档位（"5 秒后暂停"/"暂停 X 分钟"…），
     *   定时暂停期间显示"暂停剩余 N 分钟"
     *
     * 通知状态由调用方传入（Recording 或 Paused），决定按钮颜色与分组/分段是否可用。
     * 提示行文案由 [hintText] 覆盖默认文案。
     */
    fun updatePauseFeedback(
        status: RecordingStatus,
        toggleText: String,
        hintText: String,
        mediaSessionToken: MediaSessionCompat.Token? = null,
    ) {
        if (status !is RecordingStatus.Recording && status !is RecordingStatus.Paused) return
        val isPaused = status is RecordingStatus.Paused
        val views = RemoteViews(context.packageName, R.layout.notification_recording).apply {
            val groupEnabled = !isPaused && imaSettings.config.value.activeFolders.size >= 2
            // 灵感模式下分段按钮文案保持"灵感.."，即使进入暂停选择窗口/暂停态也维持提示
            val segmentText = if (InspirationModeStore.active.value) "灵感.." else "分段"
            setTextViewText(R.id.segment_button, segmentText)
            setTextViewText(R.id.toggle_button, toggleText)
            setInt(
                R.id.toggle_button,
                "setBackgroundResource",
                if (isPaused) R.drawable.btn_toggle_paused else R.drawable.btn_toggle_recording,
            )
            // 灵感模式下分段按钮保持呼吸动画，即使进入暂停选择窗口/暂停态
            val inspirationActive = InspirationModeStore.active.value
            setInt(
                R.id.segment_button,
                "setBackgroundResource",
                when {
                    isPaused -> R.drawable.btn_segment_disabled
                    inspirationActive -> R.drawable.btn_segment_inspiration
                    else -> R.drawable.btn_segment_recording
                },
            )
            setInt(
                R.id.group_button,
                "setBackgroundResource",
                if (groupEnabled) R.drawable.btn_segment_recording else R.drawable.btn_segment_disabled,
            )
            // 选择窗口与暂停态均禁用分组/分段点击，避免打断暂停流程
            if (isPaused || toggleText == "暂停") {
                setOnClickPendingIntent(R.id.segment_button, null)
                setOnClickPendingIntent(R.id.group_button, null)
            } else {
                setOnClickPendingIntent(R.id.segment_button, segmentPendingIntent())
                if (groupEnabled) {
                    setOnClickPendingIntent(R.id.group_button, groupPendingIntent())
                } else {
                    setOnClickPendingIntent(R.id.group_button, null)
                }
            }
            setOnClickPendingIntent(R.id.toggle_button, togglePendingIntent())
            setTextViewText(R.id.feedback_text, hintText)
            val isDarkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            setInt(
                R.id.feedback_text,
                "setTextColor",
                if (isDarkTheme) Color.parseColor("#E6FFFFFF") else Color.parseColor("#CC000000"),
            )
            setViewVisibility(R.id.feedback_text, android.view.View.VISIBLE)
        }
        val builder = NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("imaRec")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentText(if (isPaused) "录音已暂停" else "正在录音")
            .setCustomContentView(views)
            .setCustomBigContentView(views)
        NotificationManagerCompat.from(context).notify(RECORDING_NOTIFICATION_ID, builder.build())
    }

    // ── RemoteViews 构建 ──

    private fun buildCardViews(
        isPaused: Boolean,
        feedbackText: String? = null,
        status: RecordingStatus? = null,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_recording).apply {
            // 分组按钮：仅在录音态且主页 Tab ≥ 2 时可用；选择窗口/暂停态均置灰禁用
            val groupEnabled = !isPaused && imaSettings.config.value.activeFolders.size >= 2
            // 灵感模式下分段按钮文案改为"灵感.."，提示用户当前为灵感记录态
            val inspirationActive = InspirationModeStore.active.value
            val segmentText = if (inspirationActive) "灵感.." else "分段"
            // 暂停按钮文本：
            // - Recording：暂停
            // - Paused（一直/定时）：继续
            // 提示行默认文案：
            // - Recording：人生记录中...
            // - Paused(remainingMinutes=null)：人生记录已暂停
            // - Paused(remainingMinutes=N)：暂停剩余 N 分钟（每分钟递减）
            val toggleText = if (isPaused) "继续" else "暂停"
            val defaultHint = when {
                !isPaused -> "人生记录中..."
                status is RecordingStatus.Paused && status.remainingMinutes != null ->
                    "暂停剩余 ${status.remainingMinutes} 分钟"
                else -> "人生记录已暂停"
            }
            setTextViewText(R.id.segment_button, segmentText)
            setTextViewText(R.id.toggle_button, toggleText)
            // 暂停态：继续按钮用琥珀色，分段 + 分组按钮置灰禁用
            // 录音态：暂停按钮用绿色，分段 + 分组按钮可用绿色
            // 灵感模式下分段按钮用帧动画（呼吸效果），非灵感态用静态绿色
            setInt(
                R.id.toggle_button,
                "setBackgroundResource",
                if (isPaused) R.drawable.btn_toggle_paused else R.drawable.btn_toggle_recording,
            )
            setInt(
                R.id.segment_button,
                "setBackgroundResource",
                when {
                    isPaused -> R.drawable.btn_segment_disabled
                    inspirationActive -> R.drawable.btn_segment_inspiration
                    else -> R.drawable.btn_segment_recording
                },
            )
            setInt(
                R.id.group_button,
                "setBackgroundResource",
                if (groupEnabled) R.drawable.btn_segment_recording else R.drawable.btn_segment_disabled,
            )
            // 暂停态或无可切换知识库时，禁用分段 + 分组按钮的点击（仍显示文案，但不响应）
            if (isPaused) {
                setOnClickPendingIntent(R.id.segment_button, null)
                setOnClickPendingIntent(R.id.group_button, null)
            } else {
                setOnClickPendingIntent(R.id.segment_button, segmentPendingIntent())
                if (groupEnabled) {
                    setOnClickPendingIntent(R.id.group_button, groupPendingIntent())
                } else {
                    setOnClickPendingIntent(R.id.group_button, null)
                }
            }
            setOnClickPendingIntent(R.id.toggle_button, togglePendingIntent())
            // 提示行始终可见：有反馈时显示反馈文案，无反馈时显示默认文案（录音中/已暂停/暂停剩余 N 分钟）。
            // 固定高度保证卡片高度恒定，避免触发锁屏卡片展开/收缩。
            // 文字颜色根据系统深色/浅色模式动态设置：深色背景用浅色文字，浅色背景用深色文字，
            // 保证在各厂商通知背景下均可识别。
            setTextViewText(R.id.feedback_text, if (feedbackText.isNullOrBlank()) defaultHint else feedbackText)
            val isDarkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            setInt(
                R.id.feedback_text,
                "setTextColor",
                if (isDarkTheme) Color.parseColor("#E6FFFFFF") else Color.parseColor("#CC000000"),
            )
            setViewVisibility(R.id.feedback_text, android.view.View.VISIBLE)
        }

    private val imaSettings: ImaSettings get() = ImaSettings.get(context)

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

    private fun groupPendingIntent(): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_SWITCH_KB
        }
        return PendingIntent.getService(
            context,
            4,
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
