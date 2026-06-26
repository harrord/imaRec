package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单次录音文件上传到 IMA 知识库的状态。
 */
sealed interface ImaUploadStatus {
    /** 空闲，没有上传任务在进行。 */
    data object Idle : ImaUploadStatus

    /** 正在上传，[fileName] 为录音文件名，[progress] 为 0~1 之间进度（未知时为 0）。 */
    data class Uploading(
        val fileName: String,
        val progress: Float = 0f,
    ) : ImaUploadStatus

    /** 上传成功。 */
    data class Success(val fileName: String) : ImaUploadStatus

    /** 上传失败，[message] 为可直接展示给用户的说明。 */
    data class Failed(
        val fileName: String,
        val message: String,
    ) : ImaUploadStatus
}

/**
 * 全局上传状态存储，Service / Uploader 写入，UI（ViewModel）读取。
 *
 * 使用单例 [MutableStateFlow]，确保上传状态在 Activity 重建后仍可恢复。
 */
object ImaUploadStateStore {
    private val _status = MutableStateFlow<ImaUploadStatus>(ImaUploadStatus.Idle)
    val status: StateFlow<ImaUploadStatus> = _status.asStateFlow()

    fun set(status: ImaUploadStatus) {
        _status.value = status
    }
}
