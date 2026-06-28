package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 地理触发录音：后台定时定位扫描前台服务。
 *
 * 用户在设置中开启「地理触发」总开关时启动，关闭时停止。独立于 [RecordingService] 运行，
 * 即使未在录音也会后台扫描。
 *
 * 工作模式：
 * - 启动时立即获取一次位置，之后按 [GeoTriggerConfig.scanIntervalMinutes] 循环
 * - 每次扫描用 [FusedLocationProviderClient.getCurrentLocation] 单次获取位置（省电）
 * - 找到在 [GeoTriggerConfig.radiusMeters] 范围内最近的预设地点：
 *   - 进入范围（且未触发过该地点）：强制分段+开新录音（文件名带地点备注），
 *     震动 + Toast「已到达 XX，开始新录音」，记录触发的地点 id
 *   - 离开范围（且之前触发过）：清除触发态，若 [GeoTriggerConfig.leaveToStop] 开启则停止录音
 * - 防抖：进入触发后必须离开范围（超出偏差距离）才能再次触发
 *
 * 使用 PARTIAL_WAKE_LOCK 保证休眠时仍能定时唤醒检查。
 * 前台通知为低优先级常驻通知，文本显示扫描间隔与最近检查时间。
 */
class LocationTriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var settings: GeoTriggerSettings
    private var wakeLock: PowerManager.WakeLock? = null
    private var scanJob: Job? = null

    /** 当前已触发（在范围内）的地点 id；null 表示未触发。用于防抖。 */
    private var currentTriggeredLocationId: String? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        settings = GeoTriggerSettings.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            LOCATION_NOTIFICATION_ID,
            buildNotification(),
        )
        startScanLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanJob?.cancel()
        scanJob = null
        releaseWakeLock()
        GeoTriggerStateStore.reset()
        scope.cancel()
        super.onDestroy()
    }

    // ── 扫描循环 ──

    private fun startScanLoop() {
        if (scanJob?.isActive == true) return
        acquireWakeLock()
        scanJob = scope.launch {
            Log.d(TAG, "scan loop started")
            // 启动时立即扫描一次
            while (isActive) {
                runCatching { scanOnce() }
                    .onFailure { Log.e(TAG, "scan failed", it) }
                val intervalMin = settings.config.value.scanIntervalMinutes.coerceIn(1, 60)
                delay(intervalMin * 60_000L)
            }
        }
    }

    /**
     * 执行一次位置扫描与触发判断。
     *
     * - 配置禁用或无地点时直接返回
     * - 获取当前单次位置
     * - 在预设地点中找范围内最近的
     * - 进入范围：若未触发该地点则强制分段+新录音（防抖）
     * - 离开范围：若之前触发过则清除，按配置决定是否停止录音
     * - 更新 [GeoTriggerStateStore] 供 UI 展示
     */
    private suspend fun scanOnce() {
        val config = settings.config.value
        if (!config.enabled || config.locations.isEmpty()) {
            GeoTriggerStateStore.update { it.copy(triggeredLabel = null) }
            currentTriggeredLocationId = null
            return
        }

        val location = getCurrentLocation() ?: run {
            Log.d(TAG, "scanOnce: no location")
            return
        }

        val radius = config.radiusMeters.toDouble()
        val nearest = config.locations
            .map { loc ->
                val results = FloatArray(3)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    loc.latitude, loc.longitude,
                    results,
                )
                loc to results[0].toDouble()
            }
            .filter { it.second <= radius }
            .minByOrNull { it.second }

        if (nearest != null) {
            val (loc, dist) = nearest
            GeoTriggerStateStore.updateScan(loc.label, dist.toInt())
            if (currentTriggeredLocationId != loc.id) {
                // 进入范围（防抖：未触发或已离开上一个触发点）
                Log.d(TAG, "geo trigger enter: ${loc.label} dist=${dist}m")
                currentTriggeredLocationId = loc.id
                GeoTriggerStateStore.setTriggered(loc.label)
                RecordingService.triggerGeoSegment(this@LocationTriggerService, loc.label)
                vibrate()
                Toast.makeText(
                    this@LocationTriggerService,
                    "已到达 ${loc.label}，开始新录音",
                    Toast.LENGTH_SHORT,
                ).show()
                updateNotification()
            }
        } else {
            // 计算最近距离供 UI 展示（即使不在范围内）
            val closestAll = config.locations
                .map { loc ->
                    val results = FloatArray(3)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        loc.latitude, loc.longitude,
                        results,
                    )
                    loc to results[0].toDouble()
                }
                .minByOrNull { it.second }
            if (closestAll != null) {
                GeoTriggerStateStore.updateScan(closestAll.first.label, closestAll.second.toInt())
            }
            if (currentTriggeredLocationId != null) {
                // 离开范围
                Log.d(TAG, "geo trigger leave")
                currentTriggeredLocationId = null
                GeoTriggerStateStore.clearTriggered()
                if (config.leaveToStop) {
                    RecordingService.triggerGeoStop(this@LocationTriggerService)
                }
                updateNotification()
            }
        }
    }

    /**
     * 用 [FusedLocationProviderClient.getCurrentLocation] 获取单次位置。
     *
     * 使用 [Priority.PRIORITY_BALANCED_POWER_ACCURACY] 平衡精度与功耗（城市级精度即可，
     * 偏差范围默认 200m）。结果通过 [suspendCancellableCoroutine] 桥接为挂起函数。
     * 若获取失败或被取消返回 null，调用方跳过本次扫描。
     */
    private suspend fun getCurrentLocation(): android.location.Location? {
        return suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(MAX_LOCATION_AGE_MS)
                .build()
            fusedClient.getCurrentLocation(request, cts.token)
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "getCurrentLocation failed", e)
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    // ── 通知 ──

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            LOCATION_CHANNEL_ID,
            "地理触发录音",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "后台定时检查位置，进入预设地点时触发录音"
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
        val triggered = GeoTriggerStateStore.state.value.triggeredLabel
        val text = if (triggered != null) {
            "地理触发录音中 · $triggered（每 ${config.scanIntervalMinutes} 分钟检查）"
        } else {
            "地理触发监听中（每 ${config.scanIntervalMinutes} 分钟检查一次）"
        }
        return NotificationCompat.Builder(this, LOCATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("imaRec 地理触发")
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
            .notify(LOCATION_NOTIFICATION_ID, buildNotification())
    }

    // ── 震动 ──

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    // ── WakeLock ──

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 单次 acquire 上限设为略大于最长扫描间隔（60 分钟 + 余量），
        // 防止协程异常退出时 WakeLock 永久泄漏
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "imaRec:geo_trigger_scan",
        ).apply {
            setReferenceCounted(false)
            acquire(70 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "LocationTriggerService"
        private const val LOCATION_NOTIFICATION_ID = 2001
        private const val LOCATION_CHANNEL_ID = "geo_trigger_channel"
        /** 接受最近一次缓存位置的时效（5 分钟），避免频繁唤醒 GPS。 */
        private const val MAX_LOCATION_AGE_MS = 5 * 60 * 1000L

        /**
         * 启动地理触发扫描服务。仅在 [GeoTriggerConfig.enabled] 开启时调用。
         * 使用 startForegroundService 以保证后台运行（location 类型前台服务）。
         */
        fun start(context: Context) {
            val intent = Intent(context, LocationTriggerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止地理触发扫描服务。仅在 [GeoTriggerConfig.enabled] 关闭时调用。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, LocationTriggerService::class.java))
        }
    }
}
