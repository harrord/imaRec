package site.webbing.audiorec

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_REFRESH_DELAY_MS = 300L

data class RecordingUiState(
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordings: List<RecordingFile> = emptyList(),
    val playback: PlaybackStatus = PlaybackStatus.Idle,
    val message: String? = null,
) {
    val isRecording: Boolean
        get() = recordingStatus !is RecordingStatus.Idle
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

    val uiState: StateFlow<RecordingUiState> = combine(
        RecordingStateStore.status,
        recordings,
        PlaybackStateStore.status,
        message,
    ) { status, recordingFiles, playback, currentMessage ->
        RecordingUiState(
            recordingStatus = status,
            recordings = recordingFiles,
            playback = playback,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordingUiState(recordings = recordings.value),
    )

    init {
        // 观察 IMA 上传状态，将结果转换为用户可见的消息
        viewModelScope.launch {
            ImaUploadStateStore.status.collect { status ->
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
