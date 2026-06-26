package site.webbing.audiorec

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.webbing.audiorec.ui.MainScreen
import site.webbing.audiorec.ui.SettingsScreen
import site.webbing.audiorec.ui.theme.ImaRecTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RecordingViewModel by viewModels()

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

        when {
            !hasAudioPermission -> viewModel.showMessage("需要麦克风权限才能录音")
            !hasNotificationPermission -> viewModel.showMessage("需要通知权限才能在通知栏和锁屏显示录音状态")
            else -> viewModel.startRecording()
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
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRecordings()
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
        }

        if (missingPermissions.isEmpty()) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun ImaRecApp(
    viewModel: RecordingViewModel,
    onStartRecordingRequest: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.recordingStatus) {
        if (uiState.recordingStatus is RecordingStatus.Idle) {
            viewModel.refreshRecordings()
        }
    }

    if (showSettings) {
        SettingsScreen(onBackClick = { showSettings = false })
    } else {
        MainScreen(
            uiState = uiState,
            onRecordButtonClick = {
                if (uiState.isRecording) {
                    viewModel.stopRecording()
                } else {
                    onStartRecordingRequest()
                }
            },
            onSettingsClick = { showSettings = true },
            onMessageShown = viewModel::messageShown,
        )
    }
}
