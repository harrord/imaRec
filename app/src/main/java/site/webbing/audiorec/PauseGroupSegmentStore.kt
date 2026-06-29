package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 暂停态分组按钮是否已执行过分段（方案 B）的全局状态。
 *
 * 由 [site.webbing.audiorec.segment.SegmentController] 在以下时机更新：
 * - 进入暂停态时重置为 false（分组按钮可点击）
 * - 暂停态分组按钮 5 秒倒计时到点分段后置 true（分组按钮变灰）
 * - 进入 PausedInspiration 时置 true（灵感期间分组按钮禁用）
 * - resumeRecording() 恢复录音时重置为 false（分组按钮恢复可用）
 *
 * 供 [NotificationHelper] 读取以决定暂停态下分组按钮是否可点击。
 *
 * 会话结束（[site.webbing.audiorec.segment.SegmentController.stopSession]）时重置为 false。
 */
object PauseGroupSegmentStore {
    private val mutable = MutableStateFlow(false)
    val done: StateFlow<Boolean> = mutable.asStateFlow()

    fun update(done: Boolean) {
        mutable.value = done
    }

    fun reset() {
        mutable.value = false
    }
}
