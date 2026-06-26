package site.webbing.audiorec

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RECORDING_DIRECTORY = "recordings"

data class RecordingFile(
    val name: String,
    val path: String,
    val lastModifiedMillis: Long,
    val sizeBytes: Long,
)

class RecordingFileManager(private val context: Context) {
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun createRecordingFile(): File {
        val directory = recordingsDirectory()
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, "REC_${fileNameFormat.format(Date())}.m4a")
    }

    fun listRecordings(): List<RecordingFile> {
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
            .map { file ->
                RecordingFile(
                    name = file.name,
                    path = file.absolutePath,
                    lastModifiedMillis = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }
    }

    private fun recordingsDirectory(): File {
        val musicDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(musicDirectory, RECORDING_DIRECTORY)
    }
}
