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

        return directory
            .listFiles { file -> file.isFile && file.extension.equals("m4a", ignoreCase = true) }
            .orEmpty()
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
