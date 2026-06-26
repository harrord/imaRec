package site.webbing.audiorec.segment

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动分段功能配置。
 *
 * 对应"录音开始后自动按条件切片保存并上传"的功能：
 * - [autoSegmentEnabled]：总开关
 * - 安静切片（结束条件）：分贝低于 [silenceThresholdDb] 持续 [silenceSustainMinutes] 分钟，触发保存+上传，进入间隔期
 * - 步数继续（开始条件）：进入间隔期后，步数累计变化达 [stepStartThreshold] 步，开始新片段
 *
 * 新增条件时，在此 data class 加对应参数，并在 load/save 补键。
 *
 * @param autoSegmentEnabled 是否开启自动分段
 * @param silenceThresholdDb 安静阈值（近似 dB SPL 正值），低于此值视为安静
 * @param silenceSustainMinutes 安静需持续的分钟数，达到后触发切片
 * @param stepStartEnabled 是否启用"步数变化"作为继续（开始新片段）条件
 * @param stepStartThreshold 步数变化阈值，累计达此值后开始新片段
 * @param dbCalibrationOffset dBFS → dB SPL 的校准偏移量，默认 90
 * @param stopAtEnabled 是否启用定时停止：到达设定的时刻自动结束录音会话
 * @param stopAtHour 定时停止的小时（0~23）
 * @param stopAtMinute 定时停止的分钟（0~59）
 */
data class SegmentConfig(
    val autoSegmentEnabled: Boolean = false,
    val silenceThresholdDb: Int = 50,
    val silenceSustainMinutes: Int = 5,
    val stepStartEnabled: Boolean = true,
    val stepStartThreshold: Int = 20,
    val dbCalibrationOffset: Int = 90,
    val stopAtEnabled: Boolean = false,
    val stopAtHour: Int = 18,
    val stopAtMinute: Int = 0,
)

/**
 * 基于 SharedPreferences 的分段配置存储，向 UI 暴露 [StateFlow]。
 *
 * 与 [site.webbing.audiorec.ImaSettings] 同样的单例模式，
 * 供 RecordingService / SegmentController / SettingsScreen 共享。
 */
class SegmentSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<SegmentConfig> = _config.asStateFlow()

    fun update(transform: (SegmentConfig) -> SegmentConfig) {
        val next = transform(_config.value)
        save(next)
        _config.value = next
    }

    private fun load(): SegmentConfig = SegmentConfig(
        autoSegmentEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
        silenceThresholdDb = prefs.getInt(KEY_SILENCE_THRESHOLD, 50),
        silenceSustainMinutes = prefs.getInt(KEY_SILENCE_SUSTAIN, 5),
        stepStartEnabled = prefs.getBoolean(KEY_STEP_ENABLED, true),
        stepStartThreshold = prefs.getInt(KEY_STEP_THRESHOLD, 20),
        dbCalibrationOffset = prefs.getInt(KEY_DB_OFFSET, 90),
        stopAtEnabled = prefs.getBoolean(KEY_STOP_AT_ENABLED, false),
        stopAtHour = prefs.getInt(KEY_STOP_AT_HOUR, 18),
        stopAtMinute = prefs.getInt(KEY_STOP_AT_MINUTE, 0),
    )

    private fun save(config: SegmentConfig) {
        prefs.edit().apply {
            putBoolean(KEY_AUTO_ENABLED, config.autoSegmentEnabled)
            putInt(KEY_SILENCE_THRESHOLD, config.silenceThresholdDb)
            putInt(KEY_SILENCE_SUSTAIN, config.silenceSustainMinutes)
            putBoolean(KEY_STEP_ENABLED, config.stepStartEnabled)
            putInt(KEY_STEP_THRESHOLD, config.stepStartThreshold)
            putInt(KEY_DB_OFFSET, config.dbCalibrationOffset)
            putBoolean(KEY_STOP_AT_ENABLED, config.stopAtEnabled)
            putInt(KEY_STOP_AT_HOUR, config.stopAtHour)
            putInt(KEY_STOP_AT_MINUTE, config.stopAtMinute)
            apply()
        }
    }

    companion object {
        @Volatile
        private var instance: SegmentSettings? = null

        fun get(context: Context): SegmentSettings =
            instance ?: synchronized(this) {
                instance ?: SegmentSettings(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "segment_settings"
        private const val KEY_AUTO_ENABLED = "auto_segment_enabled"
        private const val KEY_SILENCE_THRESHOLD = "silence_threshold_db"
        private const val KEY_SILENCE_SUSTAIN = "silence_sustain_minutes"
        private const val KEY_STEP_ENABLED = "step_start_enabled"
        private const val KEY_STEP_THRESHOLD = "step_start_threshold"
        private const val KEY_DB_OFFSET = "db_calibration_offset"
        private const val KEY_STOP_AT_ENABLED = "stop_at_enabled"
        private const val KEY_STOP_AT_HOUR = "stop_at_hour"
        private const val KEY_STOP_AT_MINUTE = "stop_at_minute"
    }
}
