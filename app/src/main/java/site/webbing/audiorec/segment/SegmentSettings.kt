package site.webbing.audiorec.segment

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动分段功能配置。
 *
 * 拆分为两个完全独立的开关（卡片化设置）：
 * - [silencePauseEnabled]：安静时暂停开关。分贝低于 [silenceThresholdDb] 持续
 *   [silenceSustainMinutes] 分钟，触发保存+上传并进入间隔期。
 * - [stepStartEnabled]：移动时继续开关。用户手动暂停录音后，定时检测步数累计变化，
 *   达 [stepStartThreshold] 步时自动恢复录音。与 [silencePauseEnabled] 完全独立。
 *
 * 两个开关相互独立：可以只开其中一个，也可以两个都开或都关。
 *
 * 新增条件时，在此 data class 加对应参数，并在 load/save 补键。
 *
 * @param silencePauseEnabled 是否开启"安静时暂停"（安静切片结束条件）
 * @param silenceThresholdDb 安静阈值（近似 dB SPL 正值），低于此值视为安静
 * @param silenceSustainMinutes 安静需持续的分钟数，达到后触发切片
 * @param stepStartEnabled 是否开启"移动时继续"（暂停态下步数变化自动恢复录音）
 * @param stepStartThreshold 步数变化阈值，暂停态下累计达此值后自动恢复录音
 * @param dbCalibrationOffset dBFS → dB SPL 的校准偏移量，默认 90
 * @param stopAtEnabled 是否启用定时停止：到达设定的时刻自动结束录音会话
 * @param stopAtHour 定时停止的小时（0~23）
 * @param stopAtMinute 定时停止的分钟（0~59）
 * @param pauseMinutesX 暂停按钮连续点击循环的第 2 档时长（分钟）
 * @param pauseMinutesY 暂停按钮连续点击循环的第 3 档时长（分钟）
 * @param pauseMinutesZ 暂停按钮连续点击循环的第 4 档时长（分钟）
 *
 * 暂停按钮连续点击循环（5 秒选择窗口内）：
 * - 第 1 下 → 一直暂停
 * - 第 2 下 → 暂停 X 分钟
 * - 第 3 下 → 暂停 Y 分钟
 * - 第 4 下 → 暂停 Z 分钟
 * - 第 5 下 → 一直暂停（回到第 1 档，循环）
 */
data class SegmentConfig(
    val silencePauseEnabled: Boolean = false,
    val silenceThresholdDb: Int = 50,
    val silenceSustainMinutes: Int = 5,
    val stepStartEnabled: Boolean = true,
    val stepStartThreshold: Int = 20,
    val dbCalibrationOffset: Int = 90,
    val stopAtEnabled: Boolean = false,
    val stopAtHour: Int = 18,
    val stopAtMinute: Int = 0,
    val pauseMinutesX: Int = 5,
    val pauseMinutesY: Int = 15,
    val pauseMinutesZ: Int = 30,
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
        // 复用旧键 auto_segment_enabled 作为 silencePauseEnabled 的存储键，
        // 老用户升级后原总开关状态自动迁移为"安静时暂停"开关状态
        silencePauseEnabled = prefs.getBoolean(KEY_SILENCE_PAUSE_ENABLED, false),
        silenceThresholdDb = prefs.getInt(KEY_SILENCE_THRESHOLD, 50),
        silenceSustainMinutes = prefs.getInt(KEY_SILENCE_SUSTAIN, 5),
        stepStartEnabled = prefs.getBoolean(KEY_STEP_ENABLED, true),
        stepStartThreshold = prefs.getInt(KEY_STEP_THRESHOLD, 20),
        dbCalibrationOffset = prefs.getInt(KEY_DB_OFFSET, 90),
        stopAtEnabled = prefs.getBoolean(KEY_STOP_AT_ENABLED, false),
        stopAtHour = prefs.getInt(KEY_STOP_AT_HOUR, 18),
        stopAtMinute = prefs.getInt(KEY_STOP_AT_MINUTE, 0),
        pauseMinutesX = prefs.getInt(KEY_PAUSE_MIN_X, 5),
        pauseMinutesY = prefs.getInt(KEY_PAUSE_MIN_Y, 15),
        pauseMinutesZ = prefs.getInt(KEY_PAUSE_MIN_Z, 30),
    )

    private fun save(config: SegmentConfig) {
        prefs.edit().apply {
            putBoolean(KEY_SILENCE_PAUSE_ENABLED, config.silencePauseEnabled)
            putInt(KEY_SILENCE_THRESHOLD, config.silenceThresholdDb)
            putInt(KEY_SILENCE_SUSTAIN, config.silenceSustainMinutes)
            putBoolean(KEY_STEP_ENABLED, config.stepStartEnabled)
            putInt(KEY_STEP_THRESHOLD, config.stepStartThreshold)
            putInt(KEY_DB_OFFSET, config.dbCalibrationOffset)
            putBoolean(KEY_STOP_AT_ENABLED, config.stopAtEnabled)
            putInt(KEY_STOP_AT_HOUR, config.stopAtHour)
            putInt(KEY_STOP_AT_MINUTE, config.stopAtMinute)
            putInt(KEY_PAUSE_MIN_X, config.pauseMinutesX)
            putInt(KEY_PAUSE_MIN_Y, config.pauseMinutesY)
            putInt(KEY_PAUSE_MIN_Z, config.pauseMinutesZ)
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
        // 复用旧键名 auto_segment_enabled 作为 silencePauseEnabled 的存储键，
        // 保证老用户升级后原"自动分段总开关"状态平滑迁移为"安静时暂停"开关状态
        private const val KEY_SILENCE_PAUSE_ENABLED = "auto_segment_enabled"
        private const val KEY_SILENCE_THRESHOLD = "silence_threshold_db"
        private const val KEY_SILENCE_SUSTAIN = "silence_sustain_minutes"
        private const val KEY_STEP_ENABLED = "step_start_enabled"
        private const val KEY_STEP_THRESHOLD = "step_start_threshold"
        private const val KEY_DB_OFFSET = "db_calibration_offset"
        private const val KEY_STOP_AT_ENABLED = "stop_at_enabled"
        private const val KEY_STOP_AT_HOUR = "stop_at_hour"
        private const val KEY_STOP_AT_MINUTE = "stop_at_minute"
        private const val KEY_PAUSE_MIN_X = "pause_minutes_x"
        private const val KEY_PAUSE_MIN_Y = "pause_minutes_y"
        private const val KEY_PAUSE_MIN_Z = "pause_minutes_z"
    }
}
