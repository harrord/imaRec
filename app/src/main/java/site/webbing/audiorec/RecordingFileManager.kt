package site.webbing.audiorec

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RECORDING_DIRECTORY = "recordings"

/**
 * 录音文件元数据。
 *
 * [kbId] 为该文件归属的知识库 ID：
 * - 新录音创建时会把当前选中的 KB ID 写入文件名，[kbId] 从文件名解析得到
 * - 旧文件名中没有 KB ID 时，[kbId] 为空字符串，表示「未分类」
 */
data class RecordingFile(
    val name: String,
    val path: String,
    val lastModifiedMillis: Long,
    val sizeBytes: Long,
    val kbId: String = "",
)

class RecordingFileManager(private val context: Context) {
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 创建一个新的录音文件。
     *
     * 文件名格式：`REC_yyyyMMdd_HHmmss_kb<id>.m4a`
     * 当 [kbId] 为空时（用户未选择知识库），退化为旧格式 `REC_yyyyMMdd_HHmmss.m4a`，
     * 兼容无 Tab 状态下的录音。
     */
    fun createRecordingFile(kbId: String = ""): File {
        val directory = recordingsDirectory()
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val base = "REC_${fileNameFormat.format(Date())}"
        val name = if (kbId.isBlank()) "$base.m4a" else "${base}_kb$kbId.m4a"
        return File(directory, name)
    }

    /**
     * 把文件名中嵌入的 KB ID 重写为 [newKbId] 并 rename 磁盘文件。
     *
     * 用途：分组按钮 5 秒倒计时到点触发分段时，把当前段（在旧 KB 下开始录的）
     * 归到切换后的新 KB——同步文件名标签与上传目标，使本地列表与上传目标一致。
     *
     * - 旧文件名形如 `REC_xxx_kbAAA.m4a` → 重命名为 `REC_xxx_kbBBB.m4a`
     * - 旧文件名为「未分类」格式（无 `_kb` 后缀）且 [newKbId] 非空时，补上 `_kb<id>` 后缀
     * - [newKbId] 为空时，剥离 `_kb` 后缀退化为「未分类」格式
     * - 文件不存在或 rename 失败（目标已存在/IO 异常）时返回原 [file]，调用方继续用旧文件上传
     *
     * 必须在 MediaRecorder.stop() 关闭文件之后调用，避免写入未结束。
     */
    fun retagKbId(file: File, newKbId: String): File {
        if (!file.exists()) return file
        val oldName = file.name
        if (!oldName.endsWith(".m4a", ignoreCase = true)) return file
        val stem = oldName.removeSuffix(".m4a")
        val idx = stem.lastIndexOf("_kb")
        val base = if (idx >= 0) stem.substring(0, idx) else stem
        val newName = if (newKbId.isBlank()) "$base.m4a" else "${base}_kb$newKbId.m4a"
        if (newName == oldName) return file
        val target = File(file.parentFile, newName)
        return if (file.renameTo(target)) target else file
    }

    /**
     * 列出当前已落盘的录音文件。
     *
     * - [kbId] 为 null：返回全部录音（无 Tab 状态使用，兼容旧版）
     * - [kbId] 为非空字符串：只返回归属该 KB 的录音（按文件名中嵌入的 KB ID 精确匹配）
     * - [kbId] 为空字符串：返回所有「未分类」录音（文件名中没有 KB ID 的旧文件）
     *
     * 正在写入的文件（Recording / Paused 状态下的当前片段）会被排除，
     * 避免用户误以为录音已结束。
     */
    fun listRecordings(kbId: String? = null): List<RecordingFile> {
        val directory = recordingsDirectory()
        if (!directory.exists()) return emptyList()

        // 录音过程中文件已经写入磁盘，但只有片段结束（进入间隔期或会话结束）后才应出现在列表中，
        // 否则用户会误以为录音已结束。这里根据全局录音状态过滤掉正在写入的文件。
        // Monitoring（间隔期）时 MediaRecorder 已停止、无正在写入的文件，上一个片段应正常显示。
        val activeRecordingPath = when (val status = RecordingStateStore.status.value) {
            is RecordingStatus.Recording -> status.file.absolutePath
            is RecordingStatus.Paused -> status.file.absolutePath
            is RecordingStatus.Monitoring -> null
            RecordingStatus.Idle -> null
        }

        return directory
            .listFiles { file -> file.isFile && file.extension.equals("m4a", ignoreCase = true) }
            .orEmpty()
            .filter { it.absolutePath != activeRecordingPath }
            .sortedByDescending { it.lastModified() }
            .map { file -> file.toRecordingFile() }
            .filter { rec ->
                when (kbId) {
                    null -> true
                    "" -> rec.kbId.isBlank() // 未分类
                    else -> rec.kbId == kbId
                }
            }
    }

    private fun File.toRecordingFile(): RecordingFile =
        RecordingFile(
            name = name,
            path = absolutePath,
            lastModifiedMillis = lastModified(),
            sizeBytes = length(),
            kbId = parseKbIdFromName(name),
        )

    /**
     * 从文件名中解析归属的 KB ID。
     * 文件名形如 `REC_20260626_120000_kb12345.m4a`，返回 `12345`；
     * 旧格式 `REC_20260626_120000.m4a` 返回空字符串。
     */
    private fun parseKbIdFromName(name: String): String {
        if (!name.endsWith(".m4a", ignoreCase = true)) return ""
        val stem = name.removeSuffix(".m4a")
        val idx = stem.lastIndexOf("_kb")
        if (idx < 0) return ""
        return stem.substring(idx + 3)
    }

    private fun recordingsDirectory(): File {
        val musicDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(musicDirectory, RECORDING_DIRECTORY)
    }
}
