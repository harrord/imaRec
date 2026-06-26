package site.webbing.audiorec.segment

/**
 * 条件求值上下文，封装运行时数据，喂给所有 [SegmentCondition]。
 *
 * 新增条件若需要新的运行时数据，扩展本 data class 即可，无需改接口。
 * 条件自身特有的状态（如安静累计时长、步数基准）由条件内部维护，不放在此处。
 *
 * @param clockMs 当前时间戳（System.currentTimeMillis）
 * @param sessionStartMs 整个录音会话开始时间戳
 * @param segmentStartMs 当前片段开始时间戳
 * @param currentDb 当前分贝（近似 dB SPL，正值）
 * @param avgDbLast1s 最近 1 秒平均分贝
 * @param avgDbLast5s 最近 5 秒平均分贝
 */
data class ConditionContext(
    val clockMs: Long,
    val sessionStartMs: Long,
    val segmentStartMs: Long,
    val currentDb: Float,
    val avgDbLast1s: Float,
    val avgDbLast5s: Float,
) {
    val sessionElapsedMs: Long get() = clockMs - sessionStartMs
    val segmentElapsedMs: Long get() = clockMs - segmentStartMs
}
