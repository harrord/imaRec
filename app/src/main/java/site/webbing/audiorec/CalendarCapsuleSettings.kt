package site.webbing.audiorec

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 闪念胶囊配置。
 *
 * @param enabled 总开关，默认关闭。开启后启动 [CalendarScanService] 前台服务
 * @param targetFolderId 笔记上传目标文件夹 ID（知识库文件夹 ID，非笔记本 ID）
 * @param targetFolderName 目标文件夹名称，仅用于界面展示
 * @param anchorHour 日历时间锚点小时（0-23），只处理 DTSTART 本地小时等于此值的日程。
 *                   例如设为 3，则只处理 3:00-3:59 开始的日程。
 * @param scanIntervalMinutes 扫描间隔（分钟），范围 1–5，默认 3。必须 ≤ 5 分钟否则会漏扫
 * @param processedEventIds 已处理过的日历事件 ID 与处理时间戳的映射，用于去重
 */
data class CalendarCapsuleConfig(
    val enabled: Boolean = false,
    val targetFolderId: String = "",
    val targetFolderName: String = "",
    val anchorHour: Int = 3,
    val scanIntervalMinutes: Int = 3,
    val processedEventIds: Map<String, Long> = emptyMap(),
    /**
     * 基线快照是否已完成。用户首次开启闪念胶囊时，把当时所有未来 7 天内的事件
     * 全部记入 processedEventIds（不区分锚点小时），防止历史日程被误上传。
     * 之后只有快照后新创建的事件才会被处理。一旦 true 永远 true。
     */
    val baselineSnapshotDone: Boolean = false,
)

/**
 * 基于 SharedPreferences 的闪念胶囊配置存储，向 UI 暴露 [StateFlow]。
 *
 * 单例模式，与 [GeoTriggerSettings] / [ImaSettings] 一致，
 * 供 CalendarScanService / SettingsScreen 共享。
 * processedEventIds 用 JSON 序列化持久化。
 */
class CalendarCapsuleSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<CalendarCapsuleConfig> = _config.asStateFlow()

    fun update(transform: (CalendarCapsuleConfig) -> CalendarCapsuleConfig) {
        Log.d(TAG, "update: start")
        val next = transform(_config.value)
        save(next)
        _config.value = next
        Log.d(TAG, "update: done, enabled=${next.enabled} anchorHour=${next.anchorHour}")
    }

    /** 设置总开关。开启时会触发 [CalendarScanService] 启动。 */
    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
    }

    /** 设置笔记上传目标文件夹。 */
    fun setTargetFolder(id: String, name: String) {
        update { it.copy(targetFolderId = id, targetFolderName = name) }
    }

    /** 设置日历时间锚点小时（0-23）。 */
    fun setAnchorHour(hour: Int) {
        update { it.copy(anchorHour = hour.coerceIn(0, 23)) }
    }

    /** 设置扫描间隔（1-5 分钟）。 */
    fun setScanInterval(minutes: Int) {
        update { it.copy(scanIntervalMinutes = minutes.coerceIn(1, 5)) }
    }

    /**
     * 标记事件已处理，写入时自动清理 7 天前的条目防止膨胀（与查询窗口对齐）。
     * 笔记创建失败不调用此方法，下次扫描会重试。
     */
    fun markEventProcessed(eventId: String) {
        update { cfg ->
            val now = System.currentTimeMillis()
            val weekAgo = now - SEVEN_DAYS_MS
            // 清理 7 天前的条目（与查询窗口对齐，保证基线快照事件不被过早清理）
            val cleaned = cfg.processedEventIds.filter { it.value >= weekAgo }
            cfg.copy(processedEventIds = cleaned + (eventId to now))
        }
    }

    /**
     * 批量标记事件已处理（用于基线快照），一次 update 完成，避免多次写 SharedPreferences。
     * 清理规则与 [markEventProcessed] 一致。
     */
    fun markEventsProcessed(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        update { cfg ->
            val now = System.currentTimeMillis()
            val weekAgo = now - SEVEN_DAYS_MS
            val cleaned = cfg.processedEventIds.filter { it.value >= weekAgo }
            val added = eventIds.associateWith { now }
            cfg.copy(processedEventIds = cleaned + added)
        }
    }

    /** 标记基线快照已完成（用户首次开启闪念胶囊后调用，一旦 true 永远 true）。 */
    fun markBaselineDone() {
        update { it.copy(baselineSnapshotDone = true) }
    }

    private fun load(): CalendarCapsuleConfig = CalendarCapsuleConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        targetFolderId = prefs.getString(KEY_TARGET_FOLDER_ID, "").orEmpty(),
        targetFolderName = prefs.getString(KEY_TARGET_FOLDER_NAME, "").orEmpty(),
        anchorHour = prefs.getInt(KEY_ANCHOR_HOUR, 3).coerceIn(0, 23),
        scanIntervalMinutes = prefs.getInt(KEY_SCAN_INTERVAL, 3).coerceIn(1, 5),
        processedEventIds = readProcessedMap(prefs.getString(KEY_PROCESSED, null)),
        baselineSnapshotDone = prefs.getBoolean(KEY_BASELINE_DONE, false),
    )

    private fun save(config: CalendarCapsuleConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_TARGET_FOLDER_ID, config.targetFolderId)
            putString(KEY_TARGET_FOLDER_NAME, config.targetFolderName)
            putInt(KEY_ANCHOR_HOUR, config.anchorHour.coerceIn(0, 23))
            putInt(KEY_SCAN_INTERVAL, config.scanIntervalMinutes.coerceIn(1, 5))
            putString(KEY_PROCESSED, writeProcessedMap(config.processedEventIds))
            putBoolean(KEY_BASELINE_DONE, config.baselineSnapshotDone)
            apply()
        }
    }

    private fun readProcessedMap(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, Long>()
            obj.keys().forEach { key ->
                result[key] = obj.optLong(key)
            }
            result
        }.getOrElse { emptyMap() }
    }

    private fun writeProcessedMap(map: Map<String, Long>): String {
        val obj = JSONObject()
        map.forEach { (id, ts) -> obj.put(id, ts) }
        return obj.toString()
    }

    companion object {
        private const val TAG = "CalendarCapsuleSettings"
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: CalendarCapsuleSettings? = null

        fun get(context: Context): CalendarCapsuleSettings =
            instance ?: synchronized(this) {
                instance ?: CalendarCapsuleSettings(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "calendar_capsule_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TARGET_FOLDER_ID = "target_folder_id"
        private const val KEY_TARGET_FOLDER_NAME = "target_folder_name"
        private const val KEY_ANCHOR_HOUR = "anchor_hour"
        private const val KEY_SCAN_INTERVAL = "scan_interval_minutes"
        private const val KEY_PROCESSED = "processed_event_ids"
        private const val KEY_BASELINE_DONE = "baseline_snapshot_done"
    }
}
