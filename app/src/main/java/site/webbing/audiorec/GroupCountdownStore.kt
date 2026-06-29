package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分组按钮 5 秒倒计时是否进行中的全局状态。
 *
 * 由 [site.webbing.audiorec.segment.SegmentController.switchFolder] 在启动/取消/到点时更新：
 * - 启动倒计时（点击分组按钮）：置 true
 * - 倒计时到点 / 被取消（暂停/停止/灵感等打断）：置 false
 *
 * 供 [NotificationHelper] 读取以在分组倒计时期间禁用灵感按钮（互斥逻辑）。
 *
 * 会话结束（[site.webbing.audiorec.segment.SegmentController.stopSession]）时重置为 false。
 */
object GroupCountdownStore {
    private val mutable = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutable.asStateFlow()

    fun update(active: Boolean) {
        mutable.value = active
    }

    fun reset() {
        mutable.value = false
    }
}
