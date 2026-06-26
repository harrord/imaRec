package site.webbing.audiorec.segment

/**
 * 滑动窗口分贝统计，保留最近一段时长内的采样，提供平均值。
 *
 * 供 [SegmentController] 计算 ConditionContext 中的 avgDbLast1s / avgDbLast5s。
 * 内部用 [ArrayDeque] 按时间戳淘汰过期样本，每次 [add] 摊销 O(1)。
 */
class DbWindowBuffer {
    private data class Sample(val timestampMs: Long, val db: Float)

    private val samples = ArrayDeque<Sample>()

    /** 添加一次采样，并清理早于 (timestampMs - windowMs) 的样本。 */
    fun add(timestampMs: Long, db: Float, windowMs: Long) {
        samples.addLast(Sample(timestampMs, db))
        val cutoff = timestampMs - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }

    /** 计算窗口内平均分贝，无数据时返回 0f。 */
    fun average(): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.db
        return (sum / samples.size).toFloat()
    }

    fun clear() = samples.clear()
}
