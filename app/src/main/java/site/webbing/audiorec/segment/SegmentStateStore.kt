package site.webbing.audiorec.segment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前分段会话的元数据，供 UI 展示片段序号 / 上次切片原因。
 * 仅在自动分段开启时有意义；未开启或会话结束时为 null。
 *
 * 注意：高频变化的当前分贝请订阅 [AudioLevelStore]，避免本 Flow 频繁重建。
 *
 * @param segmentIndex 当前片段序号，从 1 开始
 * @param lastEndReason 上次切片结束原因（展示/日志），首个片段为 null
 * @param inMonitoring 当前是否处于间隔期（Monitoring）
 * @param monitoringSinceMs 间隔期开始时间戳，非间隔期为 0
 */
data class SegmentInfo(
    val segmentIndex: Int = 0,
    val lastEndReason: String? = null,
    val inMonitoring: Boolean = false,
    val monitoringSinceMs: Long = 0L,
)

object SegmentStateStore {
    private val _info = MutableStateFlow<SegmentInfo?>(null)
    val info: StateFlow<SegmentInfo?> = _info.asStateFlow()

    fun update(info: SegmentInfo?) {
        _info.value = info
    }
}
