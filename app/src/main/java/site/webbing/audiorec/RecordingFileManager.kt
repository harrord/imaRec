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
 * [folderId] 为该文件归属的知识库文件夹 ID：
 * - 新录音创建时会把当前选中的文件夹 ID 写入文件名，[folderId] 从文件名解析得到
 * - 文件名中没有文件夹 ID 时，[folderId] 为空字符串，表示上传到知识库根目录（未分类）
 */
data class RecordingFile(
    val name: String,
    val path: String,
    val lastModifiedMillis: Long,
    val sizeBytes: Long,
    val folderId: String = "",
)

class RecordingFileManager(private val context: Context) {
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 创建一个新的录音文件。
     *
     * 文件名格式：`REC_yyyyMMdd_HHmmss_f<folderId>.m4a`
     * 当 [folderId] 为空时（用户未选择文件夹，上传到知识库根目录），
     * 退化为 `REC_yyyyMMdd_HHmmss.m4a`。
     */
    fun createRecordingFile(folderId: String = ""): File {
        val directory = recordingsDirectory()
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val base = "REC_${fileNameFormat.format(Date())}"
        val name = if (folderId.isBlank()) "$base.m4a" else "${base}_f$folderId.m4a"
        return File(directory, name)
    }

    /**
     * 把文件名中嵌入的文件夹 ID 重写为 [newFolderId] 并 rename 磁盘文件。
     *
     * 用途：分组按钮 5 秒倒计时到点触发分段时，把当前段（在旧文件夹下开始录的）
     * 归到切换后的新文件夹——同步文件名标签与上传目标，使本地列表与上传目标一致。
     *
     * - 旧文件名形如 `REC_xxx_fAAA.m4a` → 重命名为 `REC_xxx_fBBB.m4a`
     * - 旧文件名为「未分类」格式（无 `_f` 后缀）且 [newFolderId] 非空时，补上 `_f<id>` 后缀
     * - [newFolderId] 为空时，剥离 `_f` 后缀退化为「未分类」格式（上传到根目录）
     * - 文件不存在或 rename 失败（目标已存在/IO 异常）时返回原 [file]，调用方继续用旧文件上传
     *
     * 必须在 MediaRecorder.stop() 关闭文件之后调用，避免写入未结束。
     */
    fun retagFolderId(file: File, newFolderId: String): File {
        if (!file.exists()) return file
        val oldName = file.name
        if (!oldName.endsWith(".m4a", ignoreCase = true)) return file
        val stem = oldName.removeSuffix(".m4a")
        val idx = stem.lastIndexOf("_f")
        val base = if (idx >= 0) stem.substring(0, idx) else stem
        val newName = if (newFolderId.isBlank()) "$base.m4a" else "${base}_f$newFolderId.m4a"
        if (newName == oldName) return file
        val target = File(file.parentFile, newName)
        return if (file.renameTo(target)) target else file
    }

    /**
     * 列出当前已落盘的录音文件。
     *
     * - [folderId] 为 null：返回全部录音（无 Tab 状态使用）
     * - [folderId] 为非空字符串：只返回归属该文件夹的录音（按文件名中嵌入的 folder ID 精确匹配）
     * - [folderId] 为空字符串：返回所有「未分类」录音（文件名中没有 folder ID 的文件，上传到根目录）
     *
     * 正在写入的文件（Recording / Paused 状态下的当前片段）会被排除，
     * 避免用户误以为录音已结束。
     */
    fun listRecordings(folderId: String? = null): List<RecordingFile> {
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
                when (folderId) {
                    null -> true
                    "" -> rec.folderId.isBlank() // 未分类（根目录）
                    else -> rec.folderId == folderId
                }
            }
    }

    private fun File.toRecordingFile(): RecordingFile =
        RecordingFile(
            name = name,
            path = absolutePath,
            lastModifiedMillis = lastModified(),
            sizeBytes = length(),
            folderId = parseFolderIdFromName(name),
        )

    /**
     * 从文件名中解析归属的文件夹 ID。
     * 文件名形如 `REC_20260626_120000_f12345.m4a`，返回 `12345`；
     * 旧格式 `REC_20260626_120000.m4a` 返回空字符串（上传到知识库根目录）。
     */
    private fun parseFolderIdFromName(name: String): String {
        if (!name.endsWith(".m4a", ignoreCase = true)) return ""
        val stem = name.removeSuffix(".m4a")
        val idx = stem.lastIndexOf("_f")
        if (idx < 0) return ""
        return stem.substring(idx + 2)
    }

    private fun recordingsDirectory(): File {
        val musicDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(musicDirectory, RECORDING_DIRECTORY)
    }
}
