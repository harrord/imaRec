package site.webbing.audiorec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.webbing.audiorec.segment.SegmentSettings
import site.webbing.audiorec.ui.MainScreen
import site.webbing.audiorec.ui.SettingsScreen
import site.webbing.audiorec.ui.theme.ImaRecTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: RecordingViewModel by viewModels()

    // "另存为"目标文件名，在用户选择保存路径前记录待保存的录音
    private var pendingSaveAsRecording: RecordingFile? = null

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4"),
    ) { uri ->
        val recording = pendingSaveAsRecording
        pendingSaveAsRecording = null
        if (uri != null && recording != null) {
            viewModel.saveRecordingAs(recording, uri)
        }
    }

    // 用户点击「导入 ima」后记录的待确认文件。
    // 启动系统分享面板后，用户返回本 APP 时 onResume 会取出它，触发确认对话框。
    private var pendingImportImaRecording: RecordingFile? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO]
            ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS]
                ?: hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        // 步数继续为可选功能：未授权不阻塞录音，仅提示后继续
        val hasStepPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACTIVITY_RECOGNITION]
                ?: hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }

        when {
            !hasAudioPermission -> viewModel.showMessage("需要麦克风权限才能录音")
            !hasNotificationPermission -> viewModel.showMessage("需要通知权限才能在通知栏和锁屏显示录音状态")
            else -> {
                if (!hasStepPermission &&
                    SegmentSettings.get(this).config.value.stepStartEnabled
                ) {
                    viewModel.showMessage("未授予活动识别权限，步数继续功能暂不可用")
                }
                viewModel.startRecording()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper(this).createChannel()

        setContent {
            ImaRecTheme {
                ImaRecApp(
                    viewModel = viewModel,
                    onStartRecordingRequest = ::startRecordingWithPermissions,
                    onSaveAsRequest = ::launchSaveAsPicker,
                    onImportImaRequest = ::launchImportImaShare,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRecordings()
        // 用户从系统分享面板返回本 APP：取出待确认文件，触发确认对话框。
        // 仅在确实发起了「导入 ima」分享后才会非空，正常进入/返回 APP 不受影响。
        val pending = pendingImportImaRecording
        pendingImportImaRecording = null
        viewModel.triggerShareConfirmIfNeeded(pending)
    }

    private fun startRecordingWithPermissions() {
        val missingPermissions = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // 仅在用户启用了"步数继续"时才申请活动识别权限，避免给不需要分段的用户造成弹窗
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                SegmentSettings.get(this@MainActivity).config.value.stepStartEnabled &&
                !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            ) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (missingPermissions.isEmpty()) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun launchSaveAsPicker(recording: RecordingFile) {
        pendingSaveAsRecording = recording
        saveAsLauncher.launch(recording.name)
    }

    /**
     * 调起系统分享面板，把录音文件分享给目标 APP（如 IMA）。
     *
     * 启动分享面板前先记录待确认文件到 [pendingImportImaRecording]。
     * 用户从分享面板返回本 APP 时 [onResume] 会取出它，弹出确认对话框：
     * 用户确认已导入成功则把文件标记为「已分享」（卡片右上角黄色对勾）。
     */
    private fun launchImportImaShare(recording: RecordingFile) {
        val file = File(recording.path)
        if (!file.exists() || !file.isFile) {
            viewModel.showMessage("源文件不存在")
            return
        }
        val authority = "$packageName.fileprovider"
        val contentUri = FileProvider.getUriForFile(this, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // 记录待确认文件，等用户返回 APP 后弹框确认是否导入成功
        pendingImportImaRecording = recording

        val chooser = Intent.createChooser(shareIntent, "导入 ima")
        startActivity(chooser)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun ImaRecApp(
    viewModel: RecordingViewModel,
    onStartRecordingRequest: () -> Unit,
    onSaveAsRequest: (RecordingFile) -> Unit,
    onImportImaRequest: (RecordingFile) -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shareConfirmFile by viewModel.shareConfirmFile.collectAsStateWithLifecycle()

    // 「导入 ima」返回确认对话框：用户从系统分享面板回到本 APP 后弹出，
    // 确认已导入成功则把文件标记为已分享（黄色对勾）。
    shareConfirmFile?.let { recording ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelShareConfirm() },
            title = { Text("确认导入") },
            text = {
                Text("请确认您是否已将录音文件导入 ima。如果是，我将重新标记该文件的状态。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmShareImported() }) { Text("是") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelShareConfirm() }) { Text("否") }
            },
        )
    }

    LaunchedEffect(uiState.recordingStatus) {
        // 录音状态变化时刷新文件列表：
        // - Monitoring（分段结束进入间隔期）：刚完成的片段已落盘，需立即显示
        // - Idle（会话结束）：最后一个片段已落盘，需立即显示
        when (uiState.recordingStatus) {
            is RecordingStatus.Monitoring, RecordingStatus.Idle -> viewModel.refreshRecordings()
            else -> Unit
        }
    }

    if (showSettings) {
        SettingsScreen(onBackClick = { showSettings = false })
    } else {
        MainScreen(
            uiState = uiState,
            onRecordButtonClick = {
                when (uiState.recordingStatus) {
                    is RecordingStatus.Paused -> viewModel.togglePause()
                    RecordingStatus.Idle -> onStartRecordingRequest()
                    else -> viewModel.stopRecording()
                }
            },
            onSettingsClick = { showSettings = true },
            onRecordingClick = viewModel::onRecordingClick,
            onRecordingDelete = viewModel::deleteRecording,
            onRecordingSaveAs = onSaveAsRequest,
            onRecordingReupload = viewModel::reuploadRecording,
            onRecordingImportIma = onImportImaRequest,
            onMessageShown = viewModel::messageShown,
            onTabSelected = viewModel::selectTab,
            onTabAdd = viewModel::addTabAndSelect,
            onTabRemove = viewModel::removeTab,
        )
    }
}
