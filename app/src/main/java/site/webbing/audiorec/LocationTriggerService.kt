package site.webbing.audiorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 地理触发录音：后台定时定位扫描前台服务。
 *
 * 用户在设置中开启「地理触发」总开关时启动，关闭时停止。独立于 [RecordingService] 运行，
 * 即使未在录音也会后台扫描。
 *
 * 工作模式：
 * - 启动时立即获取一次位置，之后按 [GeoTriggerConfig.scanIntervalMinutes] 循环
 * - 每次扫描用 [LocationHelper.requestFreshLocation] 单次获取位置（原生 LocationManager，不依赖 GMS）
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
    // 使用 Default 调度器：GPS 请求回调、距离计算、通知更新都不应在主线程执行，
    // 否则地点数量多时会阻塞主线程导致 ANR。Toast 需单独切回 Main 线程。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: GeoTriggerSettings
    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private var scanJob: Job? = null

    /**
     * 当前已触发（在范围内）的地点 id；null 表示未触发。用于防抖。
     *
     * 持久化到 SharedPreferences，服务被系统杀掉重启后从磁盘恢复，
     * 避免 leave 检测条件永远为 false 导致无法停止录音。
     */
    private var currentTriggeredLocationId: String?
        get() = prefs.getString(KEY_TRIGGERED_ID, null)
        set(value) {
            prefs.edit().putString(KEY_TRIGGERED_ID, value).apply()
        }

    override fun onCreate() {
        super.onCreate()
        settings = GeoTriggerSettings.get(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createChannel()
        Log.i(DEBUG_TAG, "onCreate: restored triggeredId=${currentTriggeredLocationId}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(DEBUG_TAG, "onStartCommand: action=${intent?.action}")
        startForeground(
            LOCATION_NOTIFICATION_ID,
            buildNotification(),
        )
        startScanLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(DEBUG_TAG, "onDestroy: service being destroyed (may be killed by system)")
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
        scanJob = scope.launch {
            Log.i(DEBUG_TAG, "scan loop started")
            // 启动时立即扫描一次
            while (isActive) {
                // 每次扫描前短时持有 WakeLock 保证 CPU 清醒（GPS 获取可能需要数秒），
                // 扫描完立即释放，delay 等待期间不持有 WakeLock，让 CPU 能深度睡眠以省电
                acquireWakeLock()
                try {
                    Log.i(DEBUG_TAG, "scan loop: starting scanOnce")
                    runCatching { scanOnce() }
                        .onFailure { Log.e(DEBUG_TAG, "scan loop: scanOnce failed", it) }
                } finally {
                    releaseWakeLock()
                }
                val intervalMin = settings.config.value.scanIntervalMinutes.coerceIn(1, 60)
                Log.i(DEBUG_TAG, "scan loop: next scan in ${intervalMin} minutes")
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
        val triggeredBefore = currentTriggeredLocationId
        Log.i(
            DEBUG_TAG,
            "scanOnce: start, enabled=${config.enabled}, locations=${config.locations.size}, " +
                "radius=${config.radiusMeters}m, leaveToStop=${config.leaveToStop}, " +
                "triggeredId=$triggeredBefore",
        )
        if (!config.enabled || config.locations.isEmpty()) {
            Log.i(DEBUG_TAG, "scanOnce: skip (disabled or no locations)")
            GeoTriggerStateStore.update { it.copy(triggeredLabel = null) }
            currentTriggeredLocationId = null
            return
        }

        val location = getCurrentLocation() ?: run {
            Log.w(DEBUG_TAG, "scanOnce: no location returned, skip this scan")
            return
        }
        Log.i(
            DEBUG_TAG,
            "scanOnce: location got, lat=${location.latitude}, lng=${location.longitude}, " +
                "accuracy=${location.accuracy}m, age=${System.currentTimeMillis() - location.time}ms",
        )

        val radius = config.radiusMeters.toDouble()
        // 计算所有地点的距离（用于诊断）
        val allDistances = config.locations.map { loc ->
            val results = FloatArray(3)
            Location.distanceBetween(
                location.latitude, location.longitude,
                loc.latitude, loc.longitude,
                results,
            )
            loc to results[0].toDouble()
        }
        allDistances.forEach { (loc, dist) ->
            Log.i(DEBUG_TAG, "scanOnce: ${loc.label}(id=${loc.id}) dist=${dist}m inRadius=${dist <= radius}")
        }

        val nearest = allDistances
            .filter { it.second <= radius }
            .minByOrNull { it.second }

        if (nearest != null) {
            val (loc, dist) = nearest
            Log.i(DEBUG_TAG, "scanOnce: nearest in range = ${loc.label}(${dist}m), triggeredId=$triggeredBefore")
            GeoTriggerStateStore.updateScan(loc.label, dist.toInt())
            if (triggeredBefore != loc.id) {
                // 进入范围（防抖：未触发或已离开上一个触发点）
                Log.i(DEBUG_TAG, ">>> ENTER: ${loc.label} dist=${dist}m, starting new recording")
                currentTriggeredLocationId = loc.id
                GeoTriggerStateStore.setTriggered(loc.label)
                RecordingService.triggerGeoSegment(this@LocationTriggerService, loc.label)
                vibrate()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LocationTriggerService,
                        "已到达 ${loc.label}，开始新录音",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                updateNotification()
            } else {
                Log.i(DEBUG_TAG, "scanOnce: already triggered this location, no action")
            }
        } else {
            // 计算最近距离供 UI 展示（即使不在范围内）
            val closestAll = allDistances.minByOrNull { it.second }
            if (closestAll != null) {
                Log.i(
                    DEBUG_TAG,
                    "scanOnce: out of range, closest=${closestAll.first.label}(${closestAll.second}m), " +
                        "triggeredId=$triggeredBefore",
                )
                GeoTriggerStateStore.updateScan(closestAll.first.label, closestAll.second.toInt())
            }
            if (triggeredBefore != null) {
                // 离开范围
                Log.i(DEBUG_TAG, ">>> LEAVE: triggeredId=$triggeredBefore, leaveToStop=${config.leaveToStop}")
                currentTriggeredLocationId = null
                GeoTriggerStateStore.clearTriggered()
                if (config.leaveToStop) {
                    Log.i(DEBUG_TAG, ">>> LEAVE: calling RecordingService.triggerGeoStop")
                    RecordingService.triggerGeoStop(this@LocationTriggerService)
                } else {
                    Log.i(DEBUG_TAG, ">>> LEAVE: leaveToStop disabled, not stopping recording")
                }
                updateNotification()
            } else {
                Log.i(DEBUG_TAG, "scanOnce: out of range but was not triggered, no leave action")
            }
        }
    }

    /**
     * 用 [LocationHelper.requestFreshLocation] 获取单次位置。
     *
     * 原生 LocationManager 实现，不依赖 GMS（国产 ROM 普遍缺失）。
     * 并行请求 GPS + Network，10 秒超时，5 秒后用缓存兜底。
     * 失败、超时或无可用 provider 时返回 null，调用方跳过本次扫描。
     */
    private suspend fun getCurrentLocation(): Location? =
        LocationHelper.requestFreshLocation(this)

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

    /**
     * 获取 PARTIAL_WAKE_LOCK，仅在单次扫描期间持有。
     *
     * 60 秒超时：GPS 获取最多 10 秒 + 距离计算和通知更新处理时间。
     * 扫描完成后由 [releaseWakeLock] 立即释放，delay 等待期间不持有，
     * 让 CPU 能进入深度睡眠以省电。超时兜底防止异常路径下 WakeLock 泄漏。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "imaRec:geo_trigger_scan",
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 1000L)
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
        /** 诊断日志统一 TAG，用 `adb logcat -s GeoTriggerDebug` 过滤。 */
        private const val DEBUG_TAG = "GeoTriggerDebug"
        private const val LOCATION_NOTIFICATION_ID = 2001
        private const val LOCATION_CHANNEL_ID = "geo_trigger_channel"

        /** 持久化触发态用的 SharedPreferences 文件名与 key。 */
        private const val PREFS_NAME = "geo_trigger_runtime"
        private const val KEY_TRIGGERED_ID = "current_triggered_id"

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
