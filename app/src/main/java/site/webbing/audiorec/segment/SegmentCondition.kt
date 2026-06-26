package site.webbing.audiorec.segment

/**
 * 录音分片条件的统一契约。
 *
 * 分片条件分为两类（均实现本接口）：
 * - [SegmentEndCondition]：触发"结束当前片段"——保存并上传当前文件，进入监测间隔期
 * - [SegmentStartCondition]：触发"开始新片段"——从间隔期恢复写入新文件
 *
 * 新增条件只需实现对应接口并在 [SegmentRuleEngine] 注册，
 * 不需要修改 [SegmentController] 的核心流转逻辑。
 *
 * 生命周期：
 * 1. 会话开始 / 进入新阶段时，[SegmentRuleEngine] 会调用 [reset] 清空内部状态
 * 2. 采样循环中，[SegmentRuleEngine] 每轮调用 [evaluate]，条件可在内部先更新状态再判断
 */
interface SegmentCondition {
    /** 唯一标识，用于日志与将来持久化。 */
    val id: String

    /** 设置页展示名称。 */
    val displayName: String

    /**
     * 判断当前是否满足条件。条件实现可在内部先依据 [ctx] 更新状态再返回结果，
     * 保证每轮采样只调用一次即可完成"更新 + 判断"。
     */
    fun evaluate(ctx: ConditionContext): Boolean

    /** 进入新阶段时重置内部状态（结束条件在新片段开始时调用，开始条件在进入间隔期时调用）。 */
    fun reset()
}

/** 触发"结束当前片段"的条件，在 Recording 阶段求值。 */
interface SegmentEndCondition : SegmentCondition

/** 触发"开始新片段"的条件，在 Monitoring（间隔期）阶段求值。 */
interface SegmentStartCondition : SegmentCondition
