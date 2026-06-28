package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 地理触发运行时状态，供 UI 观察。
 *
 * 由 [LocationTriggerService] 在扫描时更新：
 * - [triggeredLabel]：当前已触发（在范围内）的地点备注，null 表示未触发
 * - [nearestLabel] / [nearestDistanceMeters]：最近一次扫描中距离最近的地点与距离（可选展示）
 * - [lastScanTimeMs]：最近一次扫描时间戳，用于通知展示
 *
 * 由 [MainScreen] 读取，在底部圆形按钮上方显示「地理触发录音中 · XX」。
 */
data class GeoTriggerRuntimeState(
    val triggeredLabel: String? = null,
    val nearestLabel: String? = null,
    val nearestDistanceMeters: Int? = null,
    val lastScanTimeMs: Long = 0L,
)

object GeoTriggerStateStore {
    private val mutable = MutableStateFlow(GeoTriggerRuntimeState())
    val state: StateFlow<GeoTriggerRuntimeState> = mutable.asStateFlow()

    fun update(transform: (GeoTriggerRuntimeState) -> GeoTriggerRuntimeState) {
        mutable.value = transform(mutable.value)
    }

    /** 标记已触发某地点。 */
    fun setTriggered(label: String) {
        mutable.value = mutable.value.copy(
            triggeredLabel = label,
            nearestLabel = label,
            nearestDistanceMeters = 0,
            lastScanTimeMs = System.currentTimeMillis(),
        )
    }

    /** 更新最近一次扫描结果（未触发时也更新最近距离信息）。 */
    fun updateScan(nearestLabel: String?, nearestDistance: Int?) {
        mutable.value = mutable.value.copy(
            nearestLabel = nearestLabel,
            nearestDistanceMeters = nearestDistance,
            lastScanTimeMs = System.currentTimeMillis(),
        )
    }

    /** 清除触发态（已离开范围）。 */
    fun clearTriggered() {
        mutable.value = mutable.value.copy(triggeredLabel = null)
    }

    /** 服务停止时重置全部运行时状态。 */
    fun reset() {
        mutable.value = GeoTriggerRuntimeState()
    }
}
