package site.webbing.audiorec.segment

import kotlin.math.log10

/**
 * 分贝计算工具。
 *
 * Android 无法直接测量绝对 SPL，这里用相对 dBFS 加校准偏移近似 dB SPL：
 * `dbSpl = 20 * log10(amplitude / 32767) + offset`
 *
 * [calibrationOffset] 默认 90（来自设置），不同设备有差异，可在设置页校准。
 * 结果均为非负正值，便于设置项与 UI 展示。
 */
object DbCalculator {
    /** 16bit 音频最大振幅。 */
    const val AMPLITUDE_MAX = 32767

    /** 将 MediaRecorder.getMaxAmplitude() 的原始值（0..32767）转为近似 dB SPL。 */
    fun toDbSpl(maxAmplitude: Int, calibrationOffset: Int): Float {
        if (maxAmplitude <= 0) return 0f
        val dbFs = 20.0 * log10(maxAmplitude.coerceAtMost(AMPLITUDE_MAX).toDouble() / AMPLITUDE_MAX)
        return (dbFs + calibrationOffset).toFloat().coerceAtLeast(0f)
    }

    /** 将 AudioRecord PCM buffer 的 RMS 转为近似 dB SPL。 */
    fun rmsToDbSpl(rms: Double, calibrationOffset: Int): Float {
        if (rms <= 0.0) return 0f
        val dbFs = 20.0 * log10(rms / AMPLITUDE_MAX)
        return (dbFs + calibrationOffset).toFloat().coerceAtLeast(0f)
    }
}
