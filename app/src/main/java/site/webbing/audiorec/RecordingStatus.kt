package site.webbing.audiorec

import java.io.File

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data class Recording(val file: File) : RecordingStatus
    data class Paused(val file: File) : RecordingStatus
}
