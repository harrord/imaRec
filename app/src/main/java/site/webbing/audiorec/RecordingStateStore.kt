package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecordingStateStore {
    private val mutableStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val status: StateFlow<RecordingStatus> = mutableStatus.asStateFlow()

    fun update(status: RecordingStatus) {
        mutableStatus.value = status
    }
}
