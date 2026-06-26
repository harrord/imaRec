package site.webbing.audiorec.segment.conditions

import site.webbing.audiorec.segment.ConditionContext
import site.webbing.audiorec.segment.SegmentEndCondition

/**
 * "安静持续切片"结束条件：
 * 当分贝低于 [thresholdDb] 的状态持续 [sustainMinutes] 分钟，触发结束当前片段。
 *
 * 内置防抖：一旦分贝回升到阈值及以上，安静计时清零，需重新累计满 [sustainMinutes]。
 *
 * @param thresholdDb 安静阈值（近似 dB SPL 正值）
 * @param sustainMinutes 安静需持续的分钟数
 */
class SilenceSustainCondition(
    private val thresholdDb: Int,
    private val sustainMinutes: Int,
) : SegmentEndCondition {
    override val id: String = "silence_sustain"
    override val displayName: String = "安静持续切片"

    /** 当前安静段开始时间戳，0 表示当前不安静。 */
    private var quietSinceMs = 0L

    override fun evaluate(ctx: ConditionContext): Boolean {
        if (ctx.currentDb < thresholdDb) {
            if (quietSinceMs == 0L) quietSinceMs = ctx.clockMs
        } else {
            quietSinceMs = 0L
        }
        if (quietSinceMs == 0L) return false
        val sustainMs = sustainMinutes * 60_000L
        return ctx.clockMs - quietSinceMs >= sustainMs
    }

    override fun reset() {
        quietSinceMs = 0L
    }
}
