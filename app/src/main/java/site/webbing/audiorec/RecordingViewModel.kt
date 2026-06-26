package site.webbing.audiorec

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.webbing.audiorec.segment.SegmentInfo
import site.webbing.audiorec.segment.SegmentStateStore
import java.io.File

private const val STOP_REFRESH_DELAY_MS = 300L

data class RecordingUiState(
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordings: List<RecordingFile> = emptyList(),
    val playback: PlaybackStatus = PlaybackStatus.Idle,
    val message: String? = null,
    val segmentInfo: SegmentInfo? = null,
    val uploadStatusByFile: Map<String, ImaUploadStatus> = emptyMap(),
) {
    val isRecording: Boolean
        get() = recordingStatus !is RecordingStatus.Idle

    /** 当前是否处于自动分段的间隔期。 */
    val isMonitoring: Boolean
        get() = recordingStatus is RecordingStatus.Monitoring
}

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val fileManager = RecordingFileManager(application)
    private val recordings = MutableStateFlow(fileManager.listRecordings())
    private val message = MutableStateFlow<String?>(null)
    private val audioPlayer = AudioPlayerController(
        scope = viewModelScope,
        onError = { showMessage(it) },
    )
    private val imaSettings = ImaSettings.get(application)
    private val imaUploadStateStore = ImaUploadStateStore.get(application)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<RecordingUiState> = combine(
        RecordingStateStore.status,
        recordings,
        PlaybackStateStore.status,
        message,
        SegmentStateStore.info,
        imaUploadStateStore.statusByFile,
    ) { values ->
        val recordingFiles = values[1] as List<RecordingFile>
        val uploadStatusByFile = values[5] as Map<String, ImaUploadStatus>
        RecordingUiState(
            recordingStatus = values[0] as RecordingStatus,
            recordings = recordingFiles,
            playback = values[2] as PlaybackStatus,
            message = values[3] as String?,
            segmentInfo = values[4] as SegmentInfo?,
            uploadStatusByFile = uploadStatusByFile,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordingUiState(recordings = recordings.value),
    )

    init {
        // 观察 IMA 上传状态，将结果转换为用户可见的消息
        viewModelScope.launch {
            imaUploadStateStore.status.collect { status ->
                when (status) {
                    is ImaUploadStatus.Uploading -> showMessage("正在上传录音到 IMA 知识库…")
                    is ImaUploadStatus.Success -> {
                        val name = imaSettings.config.value.knowledgeBaseName
                            .ifBlank { "知识库" }
                        showMessage("录音已上传到「$name」")
                    }
                    is ImaUploadStatus.Failed -> showMessage("录音上传失败：${status.message}")
                    ImaUploadStatus.Idle -> Unit
                }
            }
        }
    }

    fun startRecording() {
        audioPlayer.stop()
        RecordingService.start(getApplication())
    }

    fun stopRecording() {
        RecordingService.stop(getApplication())
        viewModelScope.launch {
            kotlinx.coroutines.delay(STOP_REFRESH_DELAY_MS)
            refreshRecordings()
        }
    }

    /** 暂停/继续录音（仅在 Recording ↔ Paused 间切换，Monitoring 忽略）。 */
    fun togglePause() {
        RecordingService.togglePause(getApplication())
    }

    fun refreshRecordings() {
        recordings.value = fileManager.listRecordings()
    }

    fun onRecordingClick(recording: RecordingFile) {
        if (uiState.value.isRecording) {
            showMessage("录音中无法播放，请先结束录音")
            return
        }
        audioPlayer.toggle(recording.path)
    }

    /**
     * 删除本地录音文件。若该文件正在播放，会先停止播放。
     */
    fun deleteRecording(recording: RecordingFile) {
        if (PlaybackStateStore.status.value.activePath == recording.path) {
            audioPlayer.stop()
        }
        val file = File(recording.path)
        val deleted = file.exists() && file.delete()
        refreshRecordings()
        showMessage(if (deleted) "已删除「${recording.name}」" else "删除失败")
    }

    /**
     * 将录音文件另存到用户选择的目标位置（通过 SAF 返回的 content Uri）。
     */
    fun saveRecordingAs(recording: RecordingFile, destinationUri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                val source = File(recording.path)
                if (!source.exists()) return@withContext "源文件不存在"
                runCatching {
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: return@withContext "保存失败"
                    null
                }.getOrElse { it.localizedMessage ?: "未知错误" }
            }
            showMessage(result ?: "已另存为「${recording.name}」")
        }
    }

    fun pausePlayback() {
        audioPlayer.stop()
    }

    fun showMessage(text: String) {
        message.value = text
    }

    fun messageShown() {
        message.value = null
    }

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }
}
