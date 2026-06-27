package site.webbing.audiorec

import java.io.File

sealed interface RecordingStatus {
    data object Idle : RecordingStatus

    /** 正在录制某个片段文件。 */
    data class Recording(val file: File) : RecordingStatus

    /**
     * 用户手动暂停（锁屏/通知暂停按钮），录音挂起。
     *
     * @param remainingMinutes 剩余暂停分钟数。
     *   - null：一直暂停，需用户点继续恢复
     *   - 正数：定时暂停，每分钟递减；到 0 自动恢复录音
     */
    data class Paused(val file: File, val remainingMinutes: Int? = null) : RecordingStatus

    /**
     * 自动分段的间隔期：MediaRecorder 已停止，但会话未结束，
     * 用 AudioRecord 持续监测环境声音但不写文件，等待"开始条件"满足后开启新片段。
     * [sinceMs] 为进入间隔期的时间戳，[segmentIndex] 为已完成的片段数。
     */
    data class Monitoring(val sinceMs: Long, val segmentIndex: Int) : RecordingStatus
}
