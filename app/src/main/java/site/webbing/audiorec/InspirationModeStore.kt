package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 灵感记录模式全局状态。
 *
 * 由 [site.webbing.audiorec.segment.SegmentController] 在进入/退出灵感模式时更新，
 * 供 [NotificationHelper] 读取以切换锁屏分段按钮的文案与外观。
 *
 * 会话结束（[site.webbing.audiorec.segment.SegmentController.stopSession]）时重置为 false。
 */
object InspirationModeStore {
    private val mutable = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutable.asStateFlow()

    fun update(active: Boolean) {
        mutable.value = active
    }

    fun reset() {
        mutable.value = false
    }
}
