package site.webbing.audiorec.segment

/**
 * 分片规则引擎，按当前阶段求值对应条件集合。
 *
 * - Recording 阶段：求值 [endConditions]，任一满足返回 [SegmentAction.EndCurrent]
 * - Monitoring 阶段：求值 [startConditions]，任一满足返回 [SegmentAction.StartNew]
 *
 * 条件集合在会话开始时根据设置构造，会话期间不变。
 * 求值采用"短路"策略：任一条件满足即返回，不再求值后续条件。
 */
class SegmentRuleEngine(
    private val endConditions: List<SegmentEndCondition>,
    private val startConditions: List<SegmentStartCondition>,
) {
    /** 在 Recording 阶段每次采样后调用。 */
    fun evaluateEnd(ctx: ConditionContext): SegmentAction {
        for (cond in endConditions) {
            if (cond.evaluate(ctx)) return SegmentAction.EndCurrent
        }
        return SegmentAction.None
    }

    /** 在 Monitoring 阶段每次采样后调用。 */
    fun evaluateStart(ctx: ConditionContext): SegmentAction {
        for (cond in startConditions) {
            if (cond.evaluate(ctx)) return SegmentAction.StartNew
        }
        return SegmentAction.None
    }

    /** 新片段开始时调用，重置结束条件内部状态。 */
    fun onSegmentStart() {
        endConditions.forEach { it.reset() }
    }

    /** 进入监测间隔期时调用，重置开始条件内部状态。 */
    fun onMonitoringStart() {
        startConditions.forEach { it.reset() }
    }
}
