package site.webbing.audiorec.segment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前分贝电平（近似 dB SPL 正值），供 UI 画电平条。
 *
 * 采样循环高频写入（约 50~100ms 一次），独立于 [SegmentStateStore]，
 * 避免高频更新导致 SegmentInfo 频繁重建与下游重组。
 * 会话结束后应调用 [reset]。
 */
object AudioLevelStore {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun update(db: Float) {
        _level.value = db
    }

    fun reset() {
        _level.value = 0f
    }
}
