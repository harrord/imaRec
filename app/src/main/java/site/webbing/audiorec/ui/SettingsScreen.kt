package site.webbing.audiorec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import site.webbing.audiorec.ImaSettings
import site.webbing.audiorec.ImaUploader
import site.webbing.audiorec.KnowledgeBaseOption
import site.webbing.audiorec.segment.SegmentConfig
import site.webbing.audiorec.segment.SegmentSettings

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
                    settings.update { it.copy(knowledgeBaseId = id, knowledgeBaseName = name) }
                },
            )
            ConfigField(
                label = "知识库名称（可选，仅用于展示）",
                value = config.knowledgeBaseName,
                onValueChange = { v -> settings.update { it.copy(knowledgeBaseName = v) } },
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
        }
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
    var list by remember { mutableStateOf<List<KnowledgeBaseOption>>(emptyList()) }

    val displayText = when {
        selectedName.isNotBlank() -> "知识库：$selectedName"
        selectedId.isNotBlank() -> "知识库：$selectedId"
        else -> "点击选择知识库"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "知识库",
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
