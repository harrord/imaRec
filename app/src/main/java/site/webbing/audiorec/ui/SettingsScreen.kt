package site.webbing.audiorec.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.webbing.audiorec.CalendarCapsuleConfig
import site.webbing.audiorec.CalendarCapsuleSettings
import site.webbing.audiorec.CalendarScanService
import site.webbing.audiorec.ExifGpsReader
import site.webbing.audiorec.FolderOption
import site.webbing.audiorec.GeoLocation
import site.webbing.audiorec.GeoTriggerConfig
import site.webbing.audiorec.GeoTriggerSettings
import site.webbing.audiorec.ImaSettings
import site.webbing.audiorec.ImaUploader
import site.webbing.audiorec.LocationTriggerService
import site.webbing.audiorec.RecordingFile
import site.webbing.audiorec.RecordingFileManager
import site.webbing.audiorec.segment.SegmentConfig
import site.webbing.audiorec.segment.SegmentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { ImaSettings.get(context) }
    val config by settings.config.collectAsStateWithLifecycle()
    val segmentSettings = remember { SegmentSettings.get(context) }
    val segmentConfig by segmentSettings.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fileManager = remember { RecordingFileManager(context) }
    val geoSettings = remember { GeoTriggerSettings.get(context) }
    val geoConfig by geoSettings.config.collectAsStateWithLifecycle()
    val capsuleSettings = remember { CalendarCapsuleSettings.get(context) }
    val capsuleConfig by capsuleSettings.config.collectAsStateWithLifecycle()

    // 拦截系统右滑返回手势：让其回到主界面而不是退出 APP（回到桌面）
    BackHandler(enabled = true) { onBackClick() }

    // 删除文件夹确认弹窗的状态：待删除的文件夹 + 该文件夹下将被删除的录音列表 + 加载标记
    var pendingDeleteFolder by remember { mutableStateOf<FolderOption?>(null) }
    var pendingDeleteRecordings by remember { mutableStateOf<List<RecordingFile>>(emptyList()) }
    var pendingDeleteLoading by remember { mutableStateOf(false) }

    // 用户点击删除按钮时：异步加载该文件夹下的录音文件列表用于在确认弹窗中展示
    LaunchedEffect(pendingDeleteFolder) {
        val folder = pendingDeleteFolder ?: return@LaunchedEffect
        pendingDeleteLoading = true
        pendingDeleteRecordings = withContext(Dispatchers.IO) {
            fileManager.listRecordings(folder.id)
        }
        pendingDeleteLoading = false
    }

    if (pendingDeleteFolder != null) {
        DeleteFolderDialog(
            folder = pendingDeleteFolder!!,
            recordings = pendingDeleteRecordings,
            loading = pendingDeleteLoading,
            onConfirm = {
                val folder = pendingDeleteFolder!!
                scope.launch {
                    // 1. 删除该文件夹下的全部本地录音文件
                    withContext(Dispatchers.IO) {
                        fileManager.deleteRecordings(folder.id)
                    }
                    // 2. 从主页移除对应 Tab（同步 currentFolderId），持久化由 ImaSettings 负责
                    settings.removeFolder(folder.id)
                }
                pendingDeleteFolder = null
                pendingDeleteRecordings = emptyList()
            },
            onDismiss = {
                pendingDeleteFolder = null
                pendingDeleteRecordings = emptyList()
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 卡片 1：IMA 账号与上传凭证 ──
            SettingsCard(
                title = "IMA 账号与上传凭证",
                description = "配置 IMA 知识库的访问凭证与上传目标，开启后录音结束自动上传。",
            ) {
                AutoUploadToggle(
                    enabled = config.enabled,
                    onToggle = { enabled ->
                        settings.update { it.copy(enabled = enabled) }
                    },
                )
                ConfigField(
                    label = "Client ID",
                    value = config.clientId,
                    onValueChange = { v -> settings.update { it.copy(clientId = v) } },
                )
                ApiKeyField(
                    apiKey = config.apiKey,
                    onValueChange = { v -> settings.update { it.copy(apiKey = v) } },
                )
                KnowledgeBasePicker(
                    selectedId = config.knowledgeBaseId,
                    selectedName = config.knowledgeBaseName,
                    onSelected = { id, name ->
                        // 重构后知识库固定为一个：选中后写入 KB 配置，并清空旧 KB 的文件夹状态
                        settings.setKnowledgeBase(id, name)
                    },
                )
                if (!config.isConfigured) {
                    Text(
                        text = "提示：需填写 Client ID、API Key 和知识库 ID 后才能自动上传录音。" +
                            "凭证请在 https://ima.qq.com/agent-interface 获取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (config.enabled) {
                    Text(
                        text = "已开启自动上传：录音结束后将自动上传到「${config.knowledgeBaseName.ifBlank { config.knowledgeBaseId }}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── 卡片 2：默认上传文件夹 ──
            SettingsCard(
                title = "默认上传文件夹",
                description = "录音会上传到所选知识库的此文件夹中。主页 Tab 栏展示已添加的文件夹，可在录音中切换。删除文件夹会同步移除主页标签及该文件夹下的所有本地录音。",
            ) {
                FolderPicker(
                    selectedId = "",
                    selectedName = "",
                    knowledgeBaseId = config.knowledgeBaseId,
                    buttonText = "点击搜索并添加文件夹",
                    onSelected = { id, name ->
                        // 选中文件夹后加入主页 Tab 并切换为当前选中，持久化由 ImaSettings 负责
                        settings.addFolderAndSelect(FolderOption(id = id, name = name))
                    },
                )
                ActiveFolderList(
                    folders = config.activeFolders,
                    onDeleteClick = { folder -> pendingDeleteFolder = folder },
                )
            }

            // ── 卡片 3：灵感目标文件夹 ──
            SettingsCard(
                title = "灵感目标文件夹",
                description = "锁屏分段按钮双击进入灵感模式，灵感期间的录音会保存并上传到此文件夹（不受 10 秒限制）。未配置时双击等同于单击，不进入灵感模式。",
            ) {
                FolderPicker(
                    selectedId = config.inspirationFolderId,
                    selectedName = config.inspirationFolderName,
                    knowledgeBaseId = config.knowledgeBaseId,
                    buttonText = "点击选择灵感目标文件夹",
                    onSelected = { id, name ->
                        settings.setInspirationFolder(id, name)
                    },
                )
            }

            // ── 卡片 4：安静时暂停 ──
            SettingsCard(
                title = "安静时暂停",
                description = "录音中检测到持续安静时自动切片保存并上传，进入间隔期等待继续条件。",
            ) {
                SilencePauseSection(
                    enabled = segmentConfig.silencePauseEnabled,
                    silenceThresholdDb = segmentConfig.silenceThresholdDb,
                    silenceSustainMinutes = segmentConfig.silenceSustainMinutes,
                    dbCalibrationOffset = segmentConfig.dbCalibrationOffset,
                    onToggle = { enabled ->
                        segmentSettings.update { it.copy(silencePauseEnabled = enabled) }
                    },
                    onSilenceThresholdChange = { v ->
                        segmentSettings.update { it.copy(silenceThresholdDb = v) }
                    },
                    onSilenceSustainChange = { v ->
                        segmentSettings.update { it.copy(silenceSustainMinutes = v) }
                    },
                    onDbOffsetChange = { v ->
                        segmentSettings.update { it.copy(dbCalibrationOffset = v) }
                    },
                )
            }

            // ── 卡片 5：移动时继续 ──
            SettingsCard(
                title = "移动时继续",
                description = "录音暂停后定时检测步数变化，累计达阈值时自动恢复录音。与「安静时暂停」相互独立。",
            ) {
                StepResumeSection(
                    enabled = segmentConfig.stepStartEnabled,
                    stepThreshold = segmentConfig.stepStartThreshold,
                    onToggle = { enabled ->
                        segmentSettings.update { it.copy(stepStartEnabled = enabled) }
                    },
                    onThresholdChange = { v ->
                        segmentSettings.update { it.copy(stepStartThreshold = v) }
                    },
                )
            }

            // ── 卡片 6：暂停按钮设置 ──
            SettingsCard(
                title = "暂停按钮设置",
                description = "锁屏/通知暂停按钮连续点击循环：1下=一直暂停，2下=X分钟，3下=Y分钟，4下=Z分钟，5下回到一直暂停。",
            ) {
                PauseDurationSection(
                    minutesX = segmentConfig.pauseMinutesX,
                    minutesY = segmentConfig.pauseMinutesY,
                    minutesZ = segmentConfig.pauseMinutesZ,
                    onChange = { x, y, z ->
                        segmentSettings.update {
                            it.copy(pauseMinutesX = x, pauseMinutesY = y, pauseMinutesZ = z)
                        }
                    },
                )
            }

            // ── 卡片 7：定时停止 ──
            SettingsCard(
                title = "定时停止",
                description = "到达设定时刻后自动结束录音，当前片段会先保存并上传。",
            ) {
                StopAtSection(
                    enabled = segmentConfig.stopAtEnabled,
                    hour = segmentConfig.stopAtHour,
                    minute = segmentConfig.stopAtMinute,
                    onToggle = { enabled ->
                        segmentSettings.update { it.copy(stopAtEnabled = enabled) }
                    },
                    onTimeSelected = { h, m ->
                        segmentSettings.update { it.copy(stopAtHour = h, stopAtMinute = m) }
                    },
                )
            }

            // ── 卡片 8：地理围栏 ──
            SettingsCard(
                title = "地理围栏",
                description = "后台定时检查位置，进入预设地点偏差范围时强制分段并开新录音（文件名带地点备注）；离开范围可选自动停止。独立于录音会话运行，即使未在录音也会后台扫描。",
            ) {
                GeoTriggerSection(
                    config = geoConfig,
                    onToggle = { enabled ->
                        if (enabled) {
                            // 开启总开关时启动前台服务（权限在 Section 内部处理）
                            LocationTriggerService.start(context)
                        } else {
                            LocationTriggerService.stop(context)
                        }
                        geoSettings.setEnabled(enabled)
                    },
                    onIntervalChange = { v -> geoSettings.update { it.copy(scanIntervalMinutes = v) } },
                    onRadiusChange = { v -> geoSettings.update { it.copy(radiusMeters = v) } },
                    onLeaveToStopChange = { v -> geoSettings.update { it.copy(leaveToStop = v) } },
                    onAddLocation = { loc -> geoSettings.addLocation(loc) },
                    onRemoveLocation = { id -> geoSettings.removeLocation(id) },
                )
            }

            // ── 卡片 9：闪念胶囊 ──
            SettingsCard(
                title = "闪念胶囊",
                description = "从系统日历读取 AI 助手新建的日程，提取文字作为笔记上传到 IMA 指定文件夹。" +
                    "开启后后台定时扫描，锁屏也能运行。",
            ) {
                CalendarCapsuleSection(
                    config = capsuleConfig,
                    knowledgeBaseId = config.knowledgeBaseId,
                    onToggle = { enabled ->
                        if (enabled) {
                            CalendarScanService.start(context)
                        } else {
                            CalendarScanService.stop(context)
                        }
                        capsuleSettings.setEnabled(enabled)
                    },
                    onFolderSelected = { id, name ->
                        capsuleSettings.setTargetFolder(id, name)
                        // 配置变更时即时刷新通知
                        if (capsuleConfig.enabled) CalendarScanService.refresh(context)
                    },
                    onAnchorHourChange = { hour ->
                        capsuleSettings.setAnchorHour(hour)
                        if (capsuleConfig.enabled) CalendarScanService.refresh(context)
                    },
                    onIntervalChange = { minutes ->
                        capsuleSettings.setScanInterval(minutes)
                        if (capsuleConfig.enabled) CalendarScanService.refresh(context)
                    },
                )
            }
        }
    }
}

/**
 * 设置卡片容器：统一的卡片外观（标题 + 可选说明 + 分隔线 + 内容）。
 *
 * 用于设置页 8 张卡片的统一布局，避免每张卡片重复写 Card + padding + 样式。
 */
@Composable
private fun SettingsCard(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun PauseDurationSection(
    minutesX: Int,
    minutesY: Int,
    minutesZ: Int,
    onChange: (Int, Int, Int) -> Unit,
) {
    fun sanitize(v: String, fallback: Int): Int = v.toIntOrNull()?.takeIf { it in 1..600 } ?: fallback

    var textX by rememberSaveable(minutesX) { mutableStateOf(minutesX.toString()) }
    var textY by rememberSaveable(minutesY) { mutableStateOf(minutesY.toString()) }
    var textZ by rememberSaveable(minutesZ) { mutableStateOf(minutesZ.toString()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = textX,
            onValueChange = { textX = it.filter { c -> c.isDigit() } },
            label = { Text("X 分钟") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            isError = textX.toIntOrNull()?.let { it !in 1..600 } ?: true,
        )
        OutlinedTextField(
            value = textY,
            onValueChange = { textY = it.filter { c -> c.isDigit() } },
            label = { Text("Y 分钟") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            isError = textY.toIntOrNull()?.let { it !in 1..600 } ?: true,
        )
        OutlinedTextField(
            value = textZ,
            onValueChange = { textZ = it.filter { c -> c.isDigit() } },
            label = { Text("Z 分钟") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            isError = textZ.toIntOrNull()?.let { it !in 1..600 } ?: true,
        )
        OutlinedButton(
            onClick = {
                val x = sanitize(textX, minutesX)
                val y = sanitize(textY, minutesY)
                val z = sanitize(textZ, minutesZ)
                textX = x.toString()
                textY = y.toString()
                textZ = z.toString()
                onChange(x, y, z)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) { Text("保存") }
    }
}

/**
 * 安静时暂停卡片内容：独立开关 + 安静阈值 + 安静持续时长 + 分贝校准偏移。
 *
 * 开关关闭时仅显示开关行，其他字段隐藏。与「移动时继续」完全独立。
 */
@Composable
private fun SilencePauseSection(
    enabled: Boolean,
    silenceThresholdDb: Int,
    silenceSustainMinutes: Int,
    dbCalibrationOffset: Int,
    onToggle: (Boolean) -> Unit,
    onSilenceThresholdChange: (Int) -> Unit,
    onSilenceSustainChange: (Int) -> Unit,
    onDbOffsetChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "启用安静时暂停", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "安静持续达阈值后切片保存并上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }

    if (!enabled) return

    IntField(
        label = "安静阈值（dB SPL，0~120）",
        value = silenceThresholdDb,
        onChange = onSilenceThresholdChange,
    )
    IntField(
        label = "安静持续时长（分钟）",
        value = silenceSustainMinutes,
        onChange = onSilenceSustainChange,
    )
    IntField(
        label = "分贝校准偏移（高级，默认 90）",
        value = dbCalibrationOffset,
        onChange = onDbOffsetChange,
    )
    Text(
        text = "分贝为相对估算值（dBFS + 偏移），不同设备有差异，可在此校准。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 移动时继续卡片内容：独立开关 + 步数变化阈值。
 *
 * 录音暂停后定时检测步数累计变化，达阈值时自动恢复录音。与「安静时暂停」完全独立。
 */
@Composable
private fun StepResumeSection(
    enabled: Boolean,
    stepThreshold: Int,
    onToggle: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "启用移动时继续", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "暂停后步数变化达阈值自动恢复录音",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }

    if (!enabled) return

    IntField(
        label = "步数变化阈值（步）",
        value = stepThreshold,
        onChange = onThresholdChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopAtSection(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onToggle: (Boolean) -> Unit,
    onTimeSelected: (Int, Int) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "启用定时停止", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "到点自动结束录音并保存上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }

    if (!enabled) return

    var showPicker by rememberSaveable { mutableStateOf(false) }
    val timeText = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "停止时间",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(text = timeText, style = MaterialTheme.typography.titleLarge)
        }
    }

    if (showPicker) {
        ListTimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                onTimeSelected(h, m)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ListTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 列表项高度与列表总高度固定，确保首尾项也能滚动到正中央
    val itemHeight = 48.dp
    val listHeight = 240.dp
    val halfPadding = (listHeight - itemHeight) / 2

    val hourListState = rememberLazyListState()
    val minuteListState = rememberLazyListState()

    // 初始滚动使初始值居中
    LaunchedEffect(Unit) {
        hourListState.scrollToItem(initialHour)
        minuteListState.scrollToItem(initialMinute)
    }

    // 计算可视区域正中央的那一项（小时）
    val centeredHour by remember {
        derivedStateOf {
            centeredIndex(hourListState, fallback = initialHour)
        }
    }
    // 计算可视区域正中央的那一项（分钟）
    val centeredMinute by remember {
        derivedStateOf {
            centeredIndex(minuteListState, fallback = initialMinute)
        }
    }

    // 滚动停止后吸附到最近的项，保证某一项正好落在正中央
    LaunchedEffect(hourListState) {
        snapshotFlow { hourListState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    hourListState.animateScrollToItem(centeredHour)
                }
            }
    }
    LaunchedEffect(minuteListState) {
        snapshotFlow { minuteListState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    minuteListState.animateScrollToItem(centeredMinute)
                }
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择停止时间") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 小时列
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "时",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    WheelColumn(
                        listState = hourListState,
                        values = (0..23).toList(),
                        centeredValue = centeredHour,
                        itemHeight = itemHeight,
                        listHeight = listHeight,
                        halfPadding = halfPadding,
                    )
                }
                Text(
                    text = ":",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                // 分钟列
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    WheelColumn(
                        listState = minuteListState,
                        values = (0..59).toList(),
                        centeredValue = centeredMinute,
                        itemHeight = itemHeight,
                        listHeight = listHeight,
                        halfPadding = halfPadding,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(centeredHour, centeredMinute) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 计算当前位于可视区域正中央的项 index */
private fun centeredIndex(listState: androidx.compose.foundation.lazy.LazyListState, fallback: Int): Int {
    val layoutInfo = listState.layoutInfo
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo.minByOrNull { item ->
        val itemCenter = item.offset + item.size / 2
        kotlin.math.abs(itemCenter - viewportCenter)
    }?.index ?: fallback
}

@Composable
private fun WheelColumn(
    listState: androidx.compose.foundation.lazy.LazyListState,
    values: List<Int>,
    centeredValue: Int,
    itemHeight: androidx.compose.ui.unit.Dp,
    listHeight: androidx.compose.ui.unit.Dp,
    halfPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(72.dp)
            .height(listHeight),
        contentPadding = PaddingValues(vertical = halfPadding),
    ) {
        items(values) { value ->
            val selected = value == centeredValue
            Text(
                text = String.format(Locale.getDefault(), "%02d", value),
                style = if (selected) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AutoUploadToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(
                text = "录音结束后自动上传",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "生成录音文件后立即上传到 IMA 知识库",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ApiKeyField(
    apiKey: String,
    onValueChange: (String) -> Unit,
) {
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKey,
        onValueChange = onValueChange,
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = if (showApiKey) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
                Icon(
                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showApiKey) "隐藏" else "显示",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KnowledgeBasePicker(
    selectedId: String,
    selectedName: String,
    onSelected: (id: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var loading by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<FolderOption>>(emptyList()) }

    val displayText = when {
        selectedName.isNotBlank() -> "知识库：$selectedName"
        selectedId.isNotBlank() -> "知识库：$selectedId"
        else -> "点击选择知识库"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "知识库（固定一个）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(
                text = displayText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDialog) {
        LaunchedEffect(Unit) {
            loading = true
            error = null
            try {
                // 拉取可添加内容的知识库列表（仅用于选择唯一 KB，不再写 allKnowledgeBases）
                list = ImaUploader.get(context).listAddableKnowledgeBases()
            } catch (e: Exception) {
                error = e.message ?: "获取知识库列表失败"
            } finally {
                loading = false
            }
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("选择知识库") },
            text = {
                when {
                    loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                    )
                    list.isEmpty() -> Text("没有可用的知识库")
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        items(list, key = { it.id }) { kb ->
                            TextButton(
                                onClick = {
                                    onSelected(kb.id, kb.name)
                                    showDialog = false
                                },
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
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 文件夹选择器：拉取当前知识库根目录下的文件夹列表供用户选择。
 *
 * - 依赖 [knowledgeBaseId]：未配置知识库时提示先选 KB
 * - 通过 [ImaUploader.searchFolders] 按名称关键词搜索文件夹（文档推荐方法），
 *   因为 get_knowledge_list 实测不返回文件夹条目，仅返回文件
 * - [selectedName] 非空时按钮显示名称，否则回退到 id，再否则显示 [buttonText]
 * - 列表为空时给出提示
 */
@Composable
private fun FolderPicker(
    selectedId: String,
    selectedName: String = "",
    knowledgeBaseId: String,
    buttonText: String = "点击选择文件夹",
    onSelected: (id: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<FolderOption>>(emptyList()) }
    var hasSearched by rememberSaveable { mutableStateOf(false) }

    val displayText = when {
        selectedName.isNotBlank() -> "文件夹：$selectedName"
        selectedId.isNotBlank() -> "文件夹：$selectedId"
        else -> buttonText
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (knowledgeBaseId.isNotBlank()) showDialog = true },
            enabled = knowledgeBaseId.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(
                text = if (knowledgeBaseId.isBlank()) "请先选择知识库" else displayText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("搜索并选择文件夹") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "输入文件夹名称关键词搜索（在 IMA 客户端看到的文件夹名）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("文件夹名关键词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                loading = true
                                error = null
                                hasSearched = true
                                list = emptyList()
                                scope.launch {
                                    try {
                                        val fetched = ImaUploader.get(context).searchFolders(query.trim())
                                        list = fetched
                                    } catch (e: Exception) {
                                        error = e.message ?: "搜索失败"
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            enabled = query.isNotBlank() && !loading,
                            modifier = Modifier.padding(start = 4.dp),
                        ) { Text("搜索") }
                    }

                    when {
                        loading -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        error != null -> Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        hasSearched && list.isEmpty() -> Text(
                            text = "未搜到匹配项。请检查关键词或文件夹名是否正确。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        list.isNotEmpty() -> LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .padding(top = 8.dp),
                        ) {
                            items(list, key = { it.id }) { folder ->
                                TextButton(
                                    onClick = {
                                        onSelected(folder.id, folder.name)
                                        showDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = folder.name.ifBlank { folder.id },
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = folder.id,
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
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 已添加文件夹列表：展示用户已添加为主页 Tab 的文件夹，每项提供删除入口。
 *
 * 删除操作不在此处直接执行，而是通过 [onDeleteClick] 回调交由上层弹出确认弹窗，
 * 确认后才同步删除本地录音文件与主页 Tab。
 */
@Composable
private fun ActiveFolderList(
    folders: List<FolderOption>,
    onDeleteClick: (FolderOption) -> Unit,
) {
    if (folders.isEmpty()) {
        Text(
            text = "尚未添加任何文件夹",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "已添加的文件夹（${folders.size}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        folders.forEach { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = folder.name.ifBlank { folder.id },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                IconButton(onClick = { onDeleteClick(folder) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除文件夹",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 删除文件夹确认弹窗。
 *
 * 展示将被删除的文件夹名称、该文件夹下将被删除的录音文件列表，
 * 用户确认后由上层执行删除（删除本地录音文件 + 移除主页 Tab）。
 */
@Composable
private fun DeleteFolderDialog(
    folder: FolderOption,
    recordings: List<RecordingFile>,
    loading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除文件夹") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("确定删除文件夹「${folder.name.ifBlank { folder.id }}」吗？")
                Text(
                    text = "此操作将同时移除主页对应的标签页，并删除该文件夹下的所有本地录音文件，不可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                when {
                    loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    recordings.isNotEmpty() -> {
                        Text(
                            text = "将被删除的录音文件（${recordings.size} 个）：",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        recordings.forEach { rec ->
                            Text(
                                text = "• ${rec.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    else -> Text(
                        text = "该文件夹下没有录音文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !loading,
            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ── 地理触发录音设置 ──

/**
 * 地理围栏卡片内容区域。
 *
 * 卡片标题与说明文案由外层 [SettingsCard] 提供，本 Composable 仅输出：
 * 总开关（默认关闭，开启时请求定位权限并启动前台服务）、扫描间隔、偏差范围、
 * 离开停止开关、预设地点列表（可删除）、添加地点入口（弹窗支持照片 EXIF 与当前定位两种方式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeoTriggerSection(
    config: GeoTriggerConfig,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onRadiusChange: (Int) -> Unit,
    onLeaveToStopChange: (Boolean) -> Unit,
    onAddLocation: (GeoLocation) -> Unit,
    onRemoveLocation: (String) -> Unit,
) {
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    // 总开关：开启时需先请求定位权限（前台 + 后台），权限被拒时回退开关并 Toast
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val hasFine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission(context)
        if (!hasFine) {
            Toast.makeText(context, "需要定位权限才能启用地理触发", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        // 前台定位已授予，Android 10+ 还需要后台定位权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = result[Manifest.permission.ACCESS_BACKGROUND_LOCATION]
            if (hasBg != true) {
                Toast.makeText(
                    context,
                    "需要后台定位权限才能在后台扫描，请在系统设置中授予「始终允许」",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        onToggle(true)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "启用地理触发", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "进入预设地点时自动开新录音",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = config.enabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    // 检查前台定位权限，缺失则请求；已具备则直接开启
                    if (!hasLocationPermission(context)) {
                        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    } else {
                        onToggle(true)
                    }
                } else {
                    onToggle(false)
                }
            },
        )
    }

    if (!config.enabled) return

    IntField(
        label = "扫描间隔（分钟，1–60）",
        value = config.scanIntervalMinutes,
        onChange = { v -> onIntervalChange(v.coerceIn(1, 60)) },
    )
    IntField(
        label = "偏差范围（米，50–2000）",
        value = config.radiusMeters,
        onChange = { v -> onRadiusChange(v.coerceIn(50, 2000)) },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "离开范围自动停止", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "离开预设地点偏差范围时停止录音",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = config.leaveToStop, onCheckedChange = onLeaveToStopChange)
    }

    // 预设地点列表
    Text(
        text = "预设地点（${config.locations.size}）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (config.locations.isEmpty()) {
        Text(
            text = "尚未添加地点，点击下方按钮添加",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        config.locations.forEach { loc ->
            GeoLocationRow(location = loc, onDelete = { onRemoveLocation(loc.id) })
        }
    }

    OutlinedButton(
        onClick = { showAddDialog = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("添加地点")
    }

    if (showAddDialog) {
        AddLocationDialog(
            onConfirm = { loc ->
                onAddLocation(loc)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun GeoLocationRow(
    location: GeoLocation,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = location.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = String.format(
                    Locale.getDefault(),
                    "%.6f, %.6f · %s",
                    location.latitude,
                    location.longitude,
                    location.source,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除地点",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 添加地点弹窗：支持两种方式获取坐标。
 *
 * - 「从相册选照片」：调起系统图片选择器，解析 EXIF GPS；无 GPS 时 Toast 拒绝
 * - 「使用当前定位」：调起 LocationHelper（原生 LocationManager）获取当前位置
 * 解析成功后回显坐标，用户填写备注后确认保存。备注必填且清洗后不得为空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLocationDialog(
    onConfirm: (GeoLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var label by rememberSaveable { mutableStateOf("") }
    var latLng by remember { mutableStateOf<DoubleArray?>(null) }
    var source by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    // 照片选择器：通过 SAF 文件选择器（ACTION_OPEN_DOCUMENT）选取图片
    // 使用 OpenDocument + image/* 而非 Photo Picker：
    // 国产 ROM 的 Photo Picker 会剥离 EXIF GPS 信息，而 SAF 文件选择器保留完整 EXIF
    // 写法对齐 focus_mode_app 复活点设置
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            errorMessage = null
            val gps = withContext(Dispatchers.IO) {
                ExifGpsReader.readGps(context, uri)
            }
            loading = false
            if (gps == null) {
                errorMessage = null
                Toast.makeText(context, "该照片无 GPS 信息，请重新选择", Toast.LENGTH_SHORT).show()
            } else {
                latLng = gps
                source = "photo"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "添加地点",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("备注（必填，如 XX公园）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // 通过 SAF 文件选择器选取图片，保留 EXIF GPS 信息
                        photoPicker.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("从文件管理器选择")
                }
                OutlinedButton(
                    onClick = {
                        if (!hasLocationPermission(context)) {
                            Toast.makeText(context, "需要定位权限才能获取当前位置", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        scope.launch {
                            loading = true
                            errorMessage = null
                            val loc = getCurrentLocationOnce(context)
                            loading = false
                            if (loc == null) {
                                errorMessage = "获取当前位置失败"
                            } else {
                                latLng = doubleArrayOf(loc.latitude, loc.longitude)
                                source = "current"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("使用当前定位")
                }
            }

            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("正在获取位置…", style = MaterialTheme.typography.bodySmall)
                }
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // 坐标回显
            latLng?.let { coords ->
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        "坐标：%.6f, %.6f",
                        coords[0],
                        coords[1],
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                val cleanLabel = RecordingFileManager.sanitizeLocationLabel(label)
                val canConfirm = cleanLabel.isNotEmpty() && latLng != null
                TextButton(
                    onClick = {
                        val coords = latLng ?: return@TextButton
                        val finalLabel = RecordingFileManager.sanitizeLocationLabel(label)
                        if (finalLabel.isEmpty()) {
                            Toast.makeText(context, "备注清洗后为空，请重新填写", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        onConfirm(
                            GeoLocation(
                                id = UUID.randomUUID().toString(),
                                label = finalLabel,
                                latitude = coords[0],
                                longitude = coords[1],
                                source = source,
                            ),
                        )
                    },
                    enabled = canConfirm,
                ) { Text("保存") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 检查是否已授予精细定位权限。 */
private fun hasLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

/**
 * 用 [LocationHelper.requestFreshLocation] 获取一次当前位置（挂起函数）。
 * 原生 LocationManager 实现，不依赖 GMS。失败、超时或无可用 provider 时返回 null。
 */
private suspend fun getCurrentLocationOnce(
    context: android.content.Context,
): android.location.Location? = site.webbing.audiorec.LocationHelper.requestFreshLocation(context)

// ── 闪念胶囊设置 ──

/**
 * 闪念胶囊卡片内容区域。
 *
 * 总开关（开启时申请 READ_CALENDAR 权限并启动 [CalendarScanService]）、
 * 指定文件夹（依赖知识库已选择）、日历时间锚点（0-23 小时）、扫描间隔（1-5 分钟）。
 */
@Composable
private fun CalendarCapsuleSection(
    config: CalendarCapsuleConfig,
    knowledgeBaseId: String,
    onToggle: (Boolean) -> Unit,
    onFolderSelected: (id: String, name: String) -> Unit,
    onAnchorHourChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
) {
    val context = LocalContext.current

    // 总开关：开启时需先请求 READ_CALENDAR 权限，未授权不允许开启
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR]
            ?: (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR,
            ) == PackageManager.PERMISSION_GRANTED)
        if (!granted) {
            Toast.makeText(context, "需要日历读取权限才能启用闪念胶囊", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        onToggle(true)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "启用闪念胶囊", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "扫描系统日历，匹配日程时自动建笔记上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = config.enabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.READ_CALENDAR,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                    } else {
                        onToggle(true)
                    }
                } else {
                    onToggle(false)
                }
            },
        )
    }

    if (!config.enabled) return

    // 指定文件夹（依赖知识库已选择）
    FolderPicker(
        selectedId = config.targetFolderId,
        selectedName = config.targetFolderName,
        knowledgeBaseId = knowledgeBaseId,
        buttonText = "点击选择笔记上传目标文件夹",
        onSelected = onFolderSelected,
    )

    // 日历时间锚点说明
    Text(
        text = "日历时间锚点：这是一个固定的小时数（0-23），APP 只处理开始时间落在这个小时内的日程。" +
            "例如设为 ${config.anchorHour}，则只处理 ${config.anchorHour}:00-${config.anchorHour}:59 开始的日程。" +
            "用途：从最近创建的日程里筛出你想建笔记的那一条。按手机本地时区匹配。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    IntField(
        label = "锚点小时（0-23）",
        value = config.anchorHour,
        onChange = { v -> onAnchorHourChange(v.coerceIn(0, 23)) },
    )

    // 扫描间隔
    IntField(
        label = "扫描间隔（分钟，1-5）",
        value = config.scanIntervalMinutes,
        onChange = { v -> onIntervalChange(v.coerceIn(1, 5)) },
    )
    Text(
        text = "扫描间隔必须 ≤ 5 分钟，否则可能漏扫新创建的日程。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
