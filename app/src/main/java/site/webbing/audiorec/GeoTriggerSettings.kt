package site.webbing.audiorec

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 地理触发录音的预设地点。
 *
 * @param id 唯一标识（UUID），用于触发防抖记录当前触发的地点
 * @param label 用户填写的备注（如「XX公园」「XX教学楼」），必填，
 *              会写入触发录音的文件名中（经清洗）
 * @param latitude 纬度（十进制度）
 * @param longitude 经度（十进制度）
 * @param source 来源标识，仅用于调试展示：「photo」从照片 EXIF 解析 / 「current」使用当前设备定位
 */
data class GeoLocation(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val source: String,
)

/**
 * 地理触发录音配置。
 *
 * @param enabled 总开关，默认关闭。开启后启动 [LocationTriggerService] 前台服务
 * @param scanIntervalMinutes 扫描间隔（分钟），范围 1–60，默认 5
 * @param radiusMeters 偏差范围（米），用于判断是否进入/离开预设地点，默认 200
 * @param leaveToStop 离开范围时是否自动停止录音，默认开启
 * @param locations 预设地点列表
 */
data class GeoTriggerConfig(
    val enabled: Boolean = false,
    val scanIntervalMinutes: Int = 5,
    val radiusMeters: Int = 200,
    val leaveToStop: Boolean = true,
    val locations: List<GeoLocation> = emptyList(),
)

/**
 * 基于 SharedPreferences 的地理触发配置存储，向 UI 暴露 [StateFlow]。
 *
 * 单例模式，与 [ImaSettings] / [site.webbing.audiorec.segment.SegmentSettings] 一致，
 * 供 LocationTriggerService / SettingsScreen / MainScreen 共享。
 * locations 列表用 JSON 序列化持久化。
 */
class GeoTriggerSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<GeoTriggerConfig> = _config.asStateFlow()

    fun update(transform: (GeoTriggerConfig) -> GeoTriggerConfig) {
        Log.d(TAG, "update: start")
        val next = transform(_config.value)
        save(next)
        _config.value = next
        Log.d(TAG, "update: done, enabled=${next.enabled} locations=${next.locations.size}")
    }

    /** 设置总开关。开启时会触发 [LocationTriggerService] 启动。 */
    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
    }

    /** 添加一个预设地点。 */
    fun addLocation(location: GeoLocation) {
        update { it.copy(locations = it.locations + location) }
    }

    /** 删除指定 id 的预设地点。 */
    fun removeLocation(id: String) {
        update { it.copy(locations = it.locations.filterNot { it.id == id }) }
    }

    private fun load(): GeoTriggerConfig = GeoTriggerConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        scanIntervalMinutes = prefs.getInt(KEY_SCAN_INTERVAL, 5).coerceIn(1, 60),
        radiusMeters = prefs.getInt(KEY_RADIUS, 200).coerceIn(50, 5000),
        leaveToStop = prefs.getBoolean(KEY_LEAVE_TO_STOP, true),
        locations = readLocationList(prefs.getString(KEY_LOCATIONS, null)),
    )

    private fun save(config: GeoTriggerConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putInt(KEY_SCAN_INTERVAL, config.scanIntervalMinutes.coerceIn(1, 60))
            putInt(KEY_RADIUS, config.radiusMeters.coerceIn(50, 5000))
            putBoolean(KEY_LEAVE_TO_STOP, config.leaveToStop)
            putString(KEY_LOCATIONS, writeLocationList(config.locations))
            apply()
        }
    }

    private fun readLocationList(json: String?): List<GeoLocation> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val label = obj.optString("label")
                val lat = obj.optDouble("latitude", Double.NaN)
                val lng = obj.optDouble("longitude", Double.NaN)
                if (label.isBlank() || lat.isNaN() || lng.isNaN()) {
                    null
                } else {
                    GeoLocation(
                        id = id,
                        label = label,
                        latitude = lat,
                        longitude = lng,
                        source = obj.optString("source", "manual"),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeLocationList(list: List<GeoLocation>): String {
        val arr = JSONArray()
        list.forEach { loc ->
            arr.put(JSONObject().apply {
                put("id", loc.id)
                put("label", loc.label)
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("source", loc.source)
            })
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "GeoTriggerSettings"

        @Volatile
        private var instance: GeoTriggerSettings? = null

        fun get(context: Context): GeoTriggerSettings =
            instance ?: synchronized(this) {
                instance ?: GeoTriggerSettings(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "geo_trigger_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCAN_INTERVAL = "scan_interval_minutes"
        private const val KEY_RADIUS = "radius_meters"
        private const val KEY_LEAVE_TO_STOP = "leave_to_stop"
        private const val KEY_LOCATIONS = "locations"
    }
}
