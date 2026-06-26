package site.webbing.audiorec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackStateStore {
    private val mutableStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val status: StateFlow<PlaybackStatus> = mutableStatus.asStateFlow()

    fun update(status: PlaybackStatus) {
        mutableStatus.value = status
    }
}
