package site.webbing.audiorec.segment.conditions

import site.webbing.audiorec.segment.ConditionContext
import site.webbing.audiorec.segment.SegmentStartCondition
import site.webbing.audiorec.segment.StepSensorProvider

/**
 * "步数变化继续"开始条件：
 * 进入间隔期后，以当时的累计步数为基准，当步数累计变化达 [threshold] 步时，触发开始新片段。
 *
 * 依赖 [StepSensorProvider] 读取 Sensor.TYPE_STEP_COUNTER。
 * 若设备不支持步数传感器或尚未收到数据（[StepSensorProvider.currentSteps] < 0），
 * 条件永远不满足——此时用户需手动停止/重启录音，或设备恢复步数上报。
 *
 * @param stepProvider 步数传感器封装
 * @param threshold 步数变化阈值
 */
class StepCountStartCondition(
    private val stepProvider: StepSensorProvider,
    private val threshold: Int,
) : SegmentStartCondition {
    override val id: String = "step_count"
    override val displayName: String = "步数变化继续"

    /** 进入间隔期时的基准步数，-1 表示尚未初始化（首次 evaluate 时取最新值）。 */
    private var baselineSteps = -1L

    override fun evaluate(ctx: ConditionContext): Boolean {
        val current = stepProvider.currentSteps()
        if (current < 0) return false // 传感器不可用或尚未收到数据
        if (baselineSteps < 0) {
            baselineSteps = current
            return false
        }
        return current - baselineSteps >= threshold
    }

    override fun reset() {
        // 进入间隔期时清空基准，下次 evaluate 会用最新步数作为基准
        baselineSteps = -1L
    }
}
