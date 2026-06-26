package site.webbing.audiorec.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.webbing.audiorec.ImaUploadStatus
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
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("imaRec") },
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
            onRecordingClick = onRecordingClick,
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
    onRecordingClick: (RecordingFile) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    onClick = { onRecordingClick(recording) },
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingFile,
    playback: PlaybackStatus,
    enabled: Boolean,
    uploadStatus: ImaUploadStatus?,
    onClick: () -> Unit,
) {
    val activePath = playback.activePath
    val isActive = activePath == recording.path
    val isPlaying = isActive && playback is PlaybackStatus.Playing
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        modifier = Modifier.fillMaxWidth(),
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
                // 卡片右端上传状态：成功显示绿色对号，其余（失败/上传中/未上传）显示黄色问号
                val isUploadSuccess = uploadStatus is ImaUploadStatus.Success
                Icon(
                    imageVector = if (isUploadSuccess) Icons.Default.Check else Icons.Default.QuestionMark,
                    contentDescription = if (isUploadSuccess) "已上传" else "未上传",
                    tint = if (isUploadSuccess) UploadSuccessColor else UploadPendingColor,
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
    val isRecording = status !is RecordingStatus.Idle
    val label = if (isRecording) "结束录音" else "开始录音"
    val icon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic
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
    val colors = if (isRecording) {
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
