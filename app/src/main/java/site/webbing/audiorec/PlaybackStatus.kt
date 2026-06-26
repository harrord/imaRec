package site.webbing.audiorec

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus

    data class Playing(
        val path: String,
        val positionMs: Int,
        val durationMs: Int,
    ) : PlaybackStatus

    data class Paused(
        val path: String,
        val positionMs: Int,
        val durationMs: Int,
    ) : PlaybackStatus
}

val PlaybackStatus.activePath: String?
    get() = when (this) {
        is PlaybackStatus.Playing -> path
        is PlaybackStatus.Paused -> path
        PlaybackStatus.Idle -> null
    }
