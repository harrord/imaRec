package site.webbing.audiorec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.webbing.audiorec.FolderOption
import site.webbing.audiorec.ImaSettings
import site.webbing.audiorec.ImaUploader
import site.webbing.audiorec.RecordingFile
import site.webbing.audiorec.RecordingFileManager
import site.webbing.audiorec.segment.SegmentConfig
import site.webbing.audiorec.segment.SegmentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "IMA 知识库自动上传",
                style = MaterialTheme.typography.titleMedium,
            )

            // 自动上传开关
            AutoUploadToggle(
                enabled = config.enabled,
                onToggle = { enabled ->
                    settings.update { it.copy(enabled = enabled) }
                },
            )

            // 凭证与目标知识库
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

            // ── 文件夹选择 ──
            // 重构后 APP 操作范围限定在同一个知识库内，主页 Tab 与上传目标均为文件夹。
            // 通过下方按钮搜索并添加文件夹：选中后加入主页 Tab 并切换为当前选中，并持久化到本地。
            Text(
                text = "文件夹",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "录音会上传到所选知识库的此文件夹中。主页 Tab 栏展示已添加的文件夹，可在录音中切换。删除文件夹会同步移除主页标签及该文件夹下的所有本地录音。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            // ── 灵感目标文件夹 ──
            // 独立于默认上传文件夹：双击锁屏分段按钮进入灵感模式后，灵感期间的录音保存并上传到此文件夹。
            // 仅更新灵感文件夹配置，不影响默认文件夹与主页 Tab 选中态。选中后名称会显示在按钮上。
            Text(
                text = "灵感目标文件夹",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "锁屏分段按钮双击进入灵感模式，灵感期间的录音会保存并上传到此文件夹（不受 10 秒限制）。未配置时双击等同于单击，不进入灵感模式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FolderPicker(
                selectedId = config.inspirationFolderId,
                selectedName = config.inspirationFolderName,
                knowledgeBaseId = config.knowledgeBaseId,
                buttonText = "点击选择灵感目标文件夹",
                onSelected = { id, name ->
                    settings.setInspirationFolder(id, name)
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

            // ── 自动分段 ──
            Text(
                text = "自动分段",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "开启后录音全程不停止，按条件自动切片保存并上传；切片后进入监测间隔期，等待“继续条件”满足再开新片段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoSegmentSection(
                config = segmentConfig,
                onToggle = { enabled -> segmentSettings.update { it.copy(autoSegmentEnabled = enabled) } },
                onSilenceThresholdChange = { v -> segmentSettings.update { it.copy(silenceThresholdDb = v) } },
                onSilenceSustainChange = { v -> segmentSettings.update { it.copy(silenceSustainMinutes = v) } },
                onStepEnabledChange = { v -> segmentSettings.update { it.copy(stepStartEnabled = v) } },
                onStepThresholdChange = { v -> segmentSettings.update { it.copy(stepStartThreshold = v) } },
                onDbOffsetChange = { v -> segmentSettings.update { it.copy(dbCalibrationOffset = v) } },
            )

            // ── 定时停止 ──
            Text(
                text = "定时停止",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "到达设定时刻后自动结束录音，当前片段会先保存并上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            // ── 暂停时长 ──
            Text(
                text = "暂停时长",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "锁屏/通知暂停按钮连续点击循环：1下=一直暂停，2下=X分钟，3下=Y分钟，4下=Z分钟，5下回到一直暂停。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun AutoSegmentSection(
    config: SegmentConfig,
    onToggle: (Boolean) -> Unit,
    onSilenceThresholdChange: (Int) -> Unit,
    onSilenceSustainChange: (Int) -> Unit,
    onStepEnabledChange: (Boolean) -> Unit,
    onStepThresholdChange: (Int) -> Unit,
    onDbOffsetChange: (Int) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "启用自动分段", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "录音不停止，按条件自动切片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = config.autoSegmentEnabled, onCheckedChange = onToggle)
    }

    if (!config.autoSegmentEnabled) return

    IntField(
        label = "安静阈值（dB SPL，0~120）",
        value = config.silenceThresholdDb,
        onChange = onSilenceThresholdChange,
    )
    IntField(
        label = "安静持续时长（分钟）",
        value = config.silenceSustainMinutes,
        onChange = onSilenceSustainChange,
    )

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = "步数继续", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "间隔期步数变化达阈值后开始新片段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = config.stepStartEnabled, onCheckedChange = onStepEnabledChange)
    }
    if (config.stepStartEnabled) {
        IntField(
            label = "步数变化阈值（步）",
            value = config.stepStartThreshold,
            onChange = onStepThresholdChange,
        )
    }

    IntField(
        label = "分贝校准偏移（高级，默认 90）",
        value = config.dbCalibrationOffset,
        onChange = onDbOffsetChange,
    )
    Text(
        text = "分贝为相对估算值（dBFS + 偏移），不同设备有差异，可在此校准。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        val state = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择停止时间") },
            text = {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TimePicker(state = state)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onTimeSelected(state.hour, state.minute)
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        )
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
