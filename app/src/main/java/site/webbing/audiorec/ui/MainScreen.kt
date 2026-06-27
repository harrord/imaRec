package site.webbing.audiorec.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.webbing.audiorec.ImaUploadStatus
import site.webbing.audiorec.KnowledgeBaseOption
import site.webbing.audiorec.PlaybackStatus
import site.webbing.audiorec.RecordingFile
import site.webbing.audiorec.RecordingStatus
import site.webbing.audiorec.RecordingUiState
import site.webbing.audiorec.activePath
import site.webbing.audiorec.segment.AudioLevelStore
import site.webbing.audiorec.segment.SegmentInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: RecordingUiState,
    onRecordButtonClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRecordingClick: (RecordingFile) -> Unit,
    onRecordingDelete: (RecordingFile) -> Unit,
    onRecordingSaveAs: (RecordingFile) -> Unit,
    onRecordingReupload: (RecordingFile) -> Unit,
    onRecordingImportIma: (RecordingFile) -> Unit,
    onMessageShown: () -> Unit,
    onTabSelected: (String) -> Unit,
    onTabAdd: (KnowledgeBaseOption) -> Unit,
    onTabRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var menuRecording by remember { mutableStateOf<RecordingFile?>(null) }
    var pendingDelete by remember { mutableStateOf<RecordingFile?>(null) }
    var pendingRemoveTab by remember { mutableStateOf<KnowledgeBaseOption?>(null) }
    var showAddTabDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    // 长按文件卡片后弹出的底部菜单
    val menuSheetState = rememberModalBottomSheetState()
    if (menuRecording != null) {
        val recording = menuRecording!!
        ModalBottomSheet(
            onDismissRequest = { menuRecording = null },
            sheetState = menuSheetState,
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        text = recording.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = { Text("选择操作") },
            )
            HorizontalDivider()
            // 导入 ima：调起系统分享面板，把录音分享给目标 APP（如 IMA）。
            // 用户在分享面板点击任意 APP 图标后，文件卡片右上角会显示黄色对勾（已分享）。
            ListItem(
                headlineContent = { Text("导入 ima") },
                leadingContent = {
                    Icon(Icons.Default.IosShare, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onRecordingImportIma(recording)
                    menuRecording = null
                },
            )
            ListItem(
                headlineContent = { Text("另存为") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onRecordingSaveAs(recording)
                    menuRecording = null
                },
            )
            // 重新上传：仅当文件上传状态为黄色问号（非 Success）时可点击；
            // 已上传成功（绿色对号）时置灰不可点击。
            val uploadStatus = uiState.uploadStatusByFile[recording.name]
            val canReupload = uploadStatus !is ImaUploadStatus.Success
            val reuploadColor = if (canReupload) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
            ListItem(
                headlineContent = {
                    Text(
                        text = "重新上传",
                        color = reuploadColor,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = reuploadColor,
                    )
                },
                modifier = Modifier.clickable(
                    enabled = canReupload,
                    onClick = {
                        onRecordingReupload(recording)
                        menuRecording = null
                    },
                ),
            )
            ListItem(
                headlineContent = { Text("删除") },
                leadingContent = {
                    Icon(Icons.Default.Delete, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    pendingDelete = recording
                    menuRecording = null
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // 删除确认弹窗
    if (pendingDelete != null) {
        val recording = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除录音") },
            text = { Text("确定删除「${recording.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onRecordingDelete(recording)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 长按 Tab 移除确认弹窗（仅从主页移除视图，不删除服务端知识库）
    if (pendingRemoveTab != null) {
        val tab = pendingRemoveTab!!
        AlertDialog(
            onDismissRequest = { pendingRemoveTab = null },
            title = { Text("移除知识库标签") },
            text = {
                Text("从主页移除「${tab.name.ifBlank { tab.id }}」？\n知识库本身不会被删除，可稍后通过「+」重新添加。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onTabRemove(tab.id)
                    pendingRemoveTab = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveTab = null }) { Text("取消") }
            },
        )
    }

    // 「+」号添加 Tab 弹窗：从全量列表中过滤掉已展开的 Tab
    if (showAddTabDialog) {
        AddTabDialog(
            allKnowledgeBases = uiState.allKnowledgeBases,
            activeTabs = uiState.activeTabs,
            onSelected = { kb ->
                Log.d("MainScreen", "AddTabDialog onSelected: kb=${kb.id} name=${kb.name}")
                onTabAdd(kb)
                Log.d("MainScreen", "AddTabDialog onTabAdd returned")
                showAddTabDialog = false
            },
            onDismiss = { showAddTabDialog = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    KnowledgeTabRow(
                        activeTabs = uiState.activeTabs,
                        selectedKbId = uiState.selectedKbId,
                        onTabSelected = onTabSelected,
                        onTabLongPress = { pendingRemoveTab = it },
                        onAddClick = { showAddTabDialog = true },
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            RecordButtonBar(
                status = uiState.recordingStatus,
                segmentInfo = uiState.segmentInfo,
                onClick = onRecordButtonClick,
            )
        },
    ) { innerPadding ->
        RecordingList(
            recordings = uiState.recordings,
            playback = uiState.playback,
            playbackEnabled = !uiState.isRecording,
            uploadStatusByFile = uiState.uploadStatusByFile,
            sharedFiles = uiState.sharedFiles,
            onRecordingClick = onRecordingClick,
            onRecordingLongClick = { menuRecording = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@Composable
private fun RecordingList(
    recordings: List<RecordingFile>,
    playback: PlaybackStatus,
    playbackEnabled: Boolean,
    uploadStatusByFile: Map<String, ImaUploadStatus>,
    sharedFiles: Set<String>,
    onRecordingClick: (RecordingFile) -> Unit,
    onRecordingLongClick: (RecordingFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 录音文件按修改时间降序排列，新文件总是出现在列表顶部。
    // 当列表顶部的文件路径变化时（新文件保存或列表刷新），滚动到顶部以确保新文件立即可见，
    // 避免用户停留在列表中下方位置时看不到新保存的文件。
    LaunchedEffect(recordings.firstOrNull()?.path) {
        if (recordings.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    if (recordings.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "还没有录音\n点击下方按钮开始第一段录音",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recordings, key = { it.path }) { recording ->
                RecordingRow(
                    recording = recording,
                    playback = playback,
                    enabled = playbackEnabled,
                    uploadStatus = uploadStatusByFile[recording.name],
                    isShared = recording.name in sharedFiles,
                    onClick = { onRecordingClick(recording) },
                    onLongClick = { onRecordingLongClick(recording) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    recording: RecordingFile,
    playback: PlaybackStatus,
    enabled: Boolean,
    uploadStatus: ImaUploadStatus?,
    isShared: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val activePath = playback.activePath
    val isActive = activePath == recording.path
    val isPlaying = isActive && playback is PlaybackStatus.Playing
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val hapticFeedback = LocalHapticFeedback.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                val iconTint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    imageVector = icon,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = iconTint,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = recording.lastModifiedText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = recording.sizeText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 卡片右端状态图标（优先级：上传成功 > 已分享 > 未上传）：
                // - 上传成功：绿色对勾
                // - 已分享（未上传成功）：黄色对勾
                // - 其余（失败/上传中/未上传）：黄色问号
                val isUploadSuccess = uploadStatus is ImaUploadStatus.Success
                val showSharedCheck = !isUploadSuccess && isShared
                val statusIcon = when {
                    isUploadSuccess -> Icons.Default.Check
                    showSharedCheck -> Icons.Default.Check
                    else -> Icons.Default.QuestionMark
                }
                val statusContentDescription = when {
                    isUploadSuccess -> "已上传"
                    showSharedCheck -> "已分享"
                    else -> "未上传"
                }
                val statusTint = if (isUploadSuccess) UploadSuccessColor else UploadPendingColor
                Icon(
                    imageVector = statusIcon,
                    contentDescription = statusContentDescription,
                    tint = statusTint,
                )
            }

            if (isActive) {
                val positionMs = when (playback) {
                    is PlaybackStatus.Playing -> playback.positionMs
                    is PlaybackStatus.Paused -> playback.positionMs
                    PlaybackStatus.Idle -> 0
                }
                val durationMs = when (playback) {
                    is PlaybackStatus.Playing -> playback.durationMs
                    is PlaybackStatus.Paused -> playback.durationMs
                    PlaybackStatus.Idle -> 0
                }
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatMillis(positionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatMillis(durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordButtonBar(
    status: RecordingStatus,
    segmentInfo: SegmentInfo?,
    onClick: () -> Unit,
) {
    val isActive = status is RecordingStatus.Recording || status is RecordingStatus.Monitoring
    val label = when (status) {
        is RecordingStatus.Paused -> "继续录音"
        RecordingStatus.Idle -> "开始录音"
        else -> "结束录音"
    }
    val icon = when (status) {
        is RecordingStatus.Paused -> Icons.Default.PlayArrow
        RecordingStatus.Idle -> Icons.Default.Mic
        else -> Icons.Default.Stop
    }
    val db by AudioLevelStore.level.collectAsStateWithLifecycle()

    // 录音中按钮明暗闪烁：在明亮的 error 颜色与暗淡颜色之间循环往复
    val blinkTransition = rememberInfiniteTransition(label = "recordBlink")
    val blinkingColor by blinkTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.error,
        targetValue = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordBlinkColor",
    )
    // 仅在真正录音中（含监测间隔）闪烁；暂停态使用纯色，避免误导为仍在录制
    val colors = if (isActive) {
        ButtonDefaults.buttonColors(
            containerColor = blinkingColor,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val statusText: String? = when (status) {
            is RecordingStatus.Paused -> "录音已暂停，可在通知中继续"
            is RecordingStatus.Monitoring -> {
                val seg = segmentInfo
                if (seg != null) "监测中·等待活动（已录 ${seg.segmentIndex} 段）"
                else "监测中·等待活动"
            }
            is RecordingStatus.Recording -> {
                val seg = segmentInfo
                if (seg != null) "片段 #${seg.segmentIndex} · ${db.toInt()} dB"
                else "${db.toInt()} dB"
            }
            RecordingStatus.Idle -> null
        }
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = colors,
            modifier = Modifier.size(132.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(label)
            }
        }
    }
}

private fun RecordingFile.lastModifiedText(): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    return formatter.format(Date(lastModifiedMillis))
}

private fun RecordingFile.sizeText(): String {
    if (sizeBytes <= 0L) return "0 B"
    val kb = sizeBytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
}

private fun formatMillis(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

/** 上传成功图标颜色（绿色）。 */
private val UploadSuccessColor = Color(0xFF4CAF50)

/** 上传未成功图标颜色（黄色），用于失败、上传中、未上传。 */
private val UploadPendingColor = Color(0xFFFFC107)

/**
 * 主页顶部的知识库选项卡栏。
 *
 * - 未配置任何知识库（[activeTabs] 为空）时，显示占位标题「imaRec」，不渲染 Tab 与「+」
 * - 有 Tab 时使用 [ScrollableTabRow] 水平滑动，右侧常驻「+」入口
 * - 单个 Tab 长按触发 [onTabLongPress]，由上层弹确认框移除
 *
 * 选中态与 [selectedKbId] 双向绑定：点击 Tab 调用 [onTabSelected]，由 ViewModel
 * 同步写入 ImaSettings.knowledgeBaseId，进而驱动列表过滤与录音归属。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KnowledgeTabRow(
    activeTabs: List<KnowledgeBaseOption>,
    selectedKbId: String,
    onTabSelected: (String) -> Unit,
    onTabLongPress: (KnowledgeBaseOption) -> Unit,
    onAddClick: () -> Unit,
) {
    if (activeTabs.isEmpty()) {
        Text("imaRec")
        return
    }
    // selectedIndex 必须严格落在 activeTabs 范围内：
    // ScrollableTabRow 的 SubcomposeLayout 在 tab 数量变化的同一帧，
    // 内部 tabPositions 可能滞后于 selectedTabIndex 参数，若 selectedIndex 越界会触发
    // IndexOutOfBoundsException（M3 默认 indicator 仅检查 isNotEmpty，未检查 size）。
    val selectedIndex = activeTabs.indexOfFirst { it.id == selectedKbId }
        .let { if (it < 0) 0 else it.coerceAtMost(activeTabs.lastIndex) }
    val hapticFeedback = LocalHapticFeedback.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 选中态主色，用于下划线与文字着色；提取到 drawBehind 作用域外以便在绘制闭包中使用
        val selectedColor = MaterialTheme.colorScheme.primary
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 0.dp,
            divider = {},
            modifier = Modifier.weight(1f),
            // 不使用默认 indicator：M3 默认实现会无条件 tabPositions[selectedTabIndex]，
            // 在 tab 数量变化帧 tabPositions 滞后于 selectedTabIndex 时会 IndexOutOfBoundsException。
            // 选中下划线改由每个 Tab 自身的 drawBehind 绘制，无需依赖 indicator API，更稳健。
            indicator = {},
        ) {
            activeTabs.forEachIndexed { index, tab ->
                // 给每个 Tab 稳定 key，避免增删 Tab 时 SubcomposeLayout 复用错位
                key(tab.id) {
                    val selected = index == selectedIndex
                    Tab(
                        selected = selected,
                        onClick = { onTabSelected(tab.id) },
                        text = {
                            Text(
                                text = tab.name.ifBlank { tab.id },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // 选中标签：主色 + 加粗；未选中：次要色 + 常规字重，形成明显区分
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        // 选中标签底部绘制圆角下划线，让用户能看出这是可点击的标签并明确当前选中项；
                        // 未选中时不绘制，仅靠文字颜色/字重区分。
                        // 长按用 pointerInput 检测，避免 combinedClickable 与 Tab 内部 selectable 冲突；
                        // detectTapGestures 只提供 onLongPress 时，普通点击不会被消费，仍由 Tab.onClick 处理
                        modifier = Modifier
                            .drawBehind {
                                if (selected) {
                                    val underlineHeight = 3.dp.toPx()
                                    // 下划线宽度取 Tab 宽度的 60%，居中显示，视觉更精致
                                    val underlineWidth = size.width * 0.6f
                                    val left = (size.width - underlineWidth) / 2f
                                    drawRoundRect(
                                        color = selectedColor,
                                        topLeft = Offset(left, size.height - underlineHeight),
                                        size = Size(underlineWidth, underlineHeight),
                                        cornerRadius = CornerRadius(
                                            underlineHeight,
                                            underlineHeight,
                                        ),
                                    )
                                }
                            }
                            .pointerInput(tab.id) {
                                detectTapGestures(
                                    onLongPress = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onTabLongPress(tab)
                                    },
                                )
                            },
                    )
                }
            }
        }
        IconButton(onClick = onAddClick) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加知识库标签",
            )
        }
    }
}

/**
 * 「+」号弹出的添加 Tab 对话框。
 *
 * 从 [allKnowledgeBases] 中过滤掉已在主页展开的 [activeTabs]，
 * 仅展示剩余可添加的知识库。空列表时给出提示文案。
 */
@Composable
private fun AddTabDialog(
    allKnowledgeBases: List<KnowledgeBaseOption>,
    activeTabs: List<KnowledgeBaseOption>,
    onSelected: (KnowledgeBaseOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val activeIds = activeTabs.map { it.id }.toSet()
    val candidates = allKnowledgeBases.filter { it.id !in activeIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加知识库标签") },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    text = if (allKnowledgeBases.isEmpty()) {
                        "暂无可选知识库，请先在设置中拉取知识库列表。"
                    } else {
                        "所有知识库都已添加到主页。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { kb ->
                        TextButton(
                            onClick = { onSelected(kb) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = kb.name.ifBlank { kb.id },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = kb.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
