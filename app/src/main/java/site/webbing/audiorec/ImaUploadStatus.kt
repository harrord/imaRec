package site.webbing.audiorec

import android.content.Context
import android.content.SharedPreferences
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
 * 同时维护两份状态：
 * - [status]：最近一次上传的状态（用于 Snackbar 提示），仅在内存中。
 * - [statusByFile]：按文件名记录的上传结果（用于文件列表卡片图标）。
 *
 * 其中「已成功上传的文件名集合」会持久化到 SharedPreferences，
 * 确保 App 更新或进程重启后卡片上的绿色对号不会丢失；
 * 失败 / 上传中 / 未上传在重启后均回退为默认的黄色问号，无需持久化。
 */
class ImaUploadStateStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<ImaUploadStatus>(ImaUploadStatus.Idle)
    val status: StateFlow<ImaUploadStatus> = _status.asStateFlow()

    private val _statusByFile: MutableStateFlow<Map<String, ImaUploadStatus>> =
        MutableStateFlow(loadPersistedSuccesses())
    val statusByFile: StateFlow<Map<String, ImaUploadStatus>> = _statusByFile.asStateFlow()

    fun set(status: ImaUploadStatus) {
        _status.value = status
        // 同步按文件名记录：Uploading/Success/Failed 均带 fileName；Idle 无文件名，跳过。
        val fileName = when (status) {
            is ImaUploadStatus.Uploading -> status.fileName
            is ImaUploadStatus.Success -> status.fileName
            is ImaUploadStatus.Failed -> status.fileName
            ImaUploadStatus.Idle -> null
        }
        if (fileName != null) {
            _statusByFile.value = _statusByFile.value + (fileName to status)
        }
        // 仅持久化成功状态：失败/上传中/未上传在重启后均显示为黄色问号，无需保存。
        if (status is ImaUploadStatus.Success && fileName != null) {
            val successSet = prefs.getStringSet(KEY_SUCCESS_FILES, emptySet())
                .orEmpty()
                .toMutableSet()
            if (successSet.add(fileName)) {
                prefs.edit().putStringSet(KEY_SUCCESS_FILES, successSet).apply()
            }
        }
    }

    /** 从磁盘加载已成功上传的文件名，构造初始 statusByFile。 */
    private fun loadPersistedSuccesses(): Map<String, ImaUploadStatus> =
        prefs.getStringSet(KEY_SUCCESS_FILES, emptySet())
            .orEmpty()
            .associateWith { ImaUploadStatus.Success(it) }

    companion object {
        @Volatile
        private var instance: ImaUploadStateStore? = null

        fun get(context: Context): ImaUploadStateStore =
            instance ?: synchronized(this) {
                instance ?: ImaUploadStateStore(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "ima_upload_state"
        private const val KEY_SUCCESS_FILES = "success_files"
    }
}
