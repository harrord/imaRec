package site.webbing.audiorec

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.webbing.audiorec.segment.SegmentInfo
import site.webbing.audiorec.segment.SegmentStateStore
import java.io.File

private const val STOP_REFRESH_DELAY_MS = 300L
private const val TAG = "RecordingViewModel"

data class RecordingUiState(
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordings: List<RecordingFile> = emptyList(),
    val playback: PlaybackStatus = PlaybackStatus.Idle,
    val message: String? = null,
    val segmentInfo: SegmentInfo? = null,
    val uploadStatusByFile: Map<String, ImaUploadStatus> = emptyMap(),
    val sharedFiles: Set<String> = emptySet(),
    val activeTabs: List<KnowledgeBaseOption> = emptyList(),
    val selectedKbId: String = "",
    val allKnowledgeBases: List<KnowledgeBaseOption> = emptyList(),
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
        imaUploadStateStore.sharedFiles,
        imaSettings.config,
    ) { values ->
        val recordingFiles = values[1] as List<RecordingFile>
        val uploadStatusByFile = values[5] as Map<String, ImaUploadStatus>
        val sharedFiles = values[6] as Set<String>
        val cfg = values[7] as ImaConfig
        RecordingUiState(
            recordingStatus = values[0] as RecordingStatus,
            recordings = recordingFiles,
            playback = values[2] as PlaybackStatus,
            message = values[3] as String?,
            segmentInfo = values[4] as SegmentInfo?,
            uploadStatusByFile = uploadStatusByFile,
            sharedFiles = sharedFiles,
            activeTabs = cfg.activeTabs,
            selectedKbId = cfg.knowledgeBaseId,
            allKnowledgeBases = cfg.allKnowledgeBases,
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

        // 当默认 KB ID 变化（用户切 Tab / 在设置里换 KB / 增删 Tab）时，
        // 重新按归属过滤录音列表，保证主页列表与当前选中 Tab 一致。
        viewModelScope.launch {
            imaSettings.config.collect { cfg ->
                Log.d(TAG, "config changed: selectedKbId=${cfg.knowledgeBaseId} activeTabs=${cfg.activeTabs.size}")
                refreshRecordingsFor(cfg.knowledgeBaseId, cfg.activeTabs)
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

    /**
     * 刷新录音列表。根据当前是否有 Tab 决定过滤策略：
     * - 有 Tab：按当前选中 KB ID 过滤；选中为空时显示「未分类」
     * - 无 Tab：显示全部录音（兼容旧版无 KB 状态）
     */
    fun refreshRecordings() {
        val cfg = imaSettings.config.value
        refreshRecordingsFor(cfg.knowledgeBaseId, cfg.activeTabs)
    }

    private fun refreshRecordingsFor(selectedKbId: String, activeTabs: List<KnowledgeBaseOption>) {
        val filterKbId: String? = if (activeTabs.isEmpty()) {
            null // 无 Tab：显示全部（兼容旧版与「未分类」）
        } else {
            selectedKbId // 有 Tab：严格按当前选中 KB 过滤（含空串 → 未分类）
        }
        Log.d(TAG, "refreshRecordingsFor: filterKbId=$filterKbId")
        val list = fileManager.listRecordings(filterKbId)
        Log.d(TAG, "refreshRecordingsFor: got ${list.size} files")
        recordings.value = list
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

    /**
     * 重新上传指定录音文件到 IMA 知识库。
     * 用于文件列表长按菜单中的「重新上传」操作。
     */
    fun reuploadRecording(recording: RecordingFile) {
        val file = File(recording.path)
        if (!file.exists()) {
            showMessage("源文件不存在")
            return
        }
        ImaUploader.get(getApplication()).enqueueUpload(file)
    }

    /**
     * 标记某个录音文件已被分享（用户在系统分享面板点击了目标 APP 图标后回调）。
     * 仅更新本地分享状态（黄色对勾），不触发上传。
     */
    fun markAsShared(fileName: String) {
        imaUploadStateStore.markShared(fileName)
    }

    // ── 「导入 ima」确认对话框状态 ──

    /** 当前待确认的分享文件，非空时 UI 显示确认对话框。 */
    private val _shareConfirmFile = MutableStateFlow<RecordingFile?>(null)
    val shareConfirmFile: StateFlow<RecordingFile?> = _shareConfirmFile.asStateFlow()

    /**
     * Activity 在 onResume 时调用：若 [recording] 非空（用户刚从分享面板返回），
     * 推入 Compose 可观察状态触发确认对话框；为空则什么都不做。
     */
    fun triggerShareConfirmIfNeeded(recording: RecordingFile?) {
        if (recording != null) {
            _shareConfirmFile.value = recording
        }
    }

    /** 用户确认已导入成功：标记文件为已分享并关闭对话框。 */
    fun confirmShareImported() {
        _shareConfirmFile.value?.let { markAsShared(it.name) }
        _shareConfirmFile.value = null
    }

    /** 用户取消：直接关闭对话框，不标记。 */
    fun cancelShareConfirm() {
        _shareConfirmFile.value = null
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

    // ── 主页 Tab 操作（委托给 ImaSettings，保证双向绑定与持久化） ──

    /** 主页切换 Tab：同步更新默认 KB ID/Name，列表会随之自动过滤。 */
    fun selectTab(kbId: String) {
        imaSettings.selectTab(kbId)
    }

    /** 主页「+」号添加 Tab 并切换过去。 */
    fun addTabAndSelect(kb: KnowledgeBaseOption) {
        Log.d(TAG, "addTabAndSelect: kb=${kb.id}")
        imaSettings.addTabAndSelect(kb)
        Log.d(TAG, "addTabAndSelect: done")
    }

    /** 主页长按移除 Tab（仅从主页移除视图，不删除知识库）。 */
    fun removeTab(kbId: String) {
        imaSettings.removeTab(kbId)
    }

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }
}
