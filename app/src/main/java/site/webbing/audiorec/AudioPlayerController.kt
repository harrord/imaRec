package site.webbing.audiorec

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POSITION_TICK_MS = 200L

class AudioPlayerController(
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private var mediaPlayer: MediaPlayer? = null
    private var tickJob: Job? = null
    private var currentPath: String? = null

    /**
     * 切换播放：未播放此文件则开始播放，正在播放则暂停，已暂停则继续。
     */
    fun toggle(path: String) {
        when (val status = PlaybackStateStore.status.value) {
            is PlaybackStatus.Playing -> {
                if (status.path == path) pause() else play(path)
            }
            is PlaybackStatus.Paused -> {
                if (status.path == path) resume() else play(path)
            }
            PlaybackStatus.Idle -> play(path)
        }
    }

    fun stop() {
        cancelTick()
        mediaPlayer?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        currentPath = null
        PlaybackStateStore.update(PlaybackStatus.Idle)
    }

    fun release() {
        stop()
    }

    private fun play(path: String) {
        stop()

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(path)
            player.setOnPreparedListener {
                it.start()
                val duration = it.duration.coerceAtLeast(0)
                PlaybackStateStore.update(
                    PlaybackStatus.Playing(
                        path = path,
                        positionMs = 0,
                        durationMs = duration,
                    ),
                )
                startTick()
            }
            player.setOnCompletionListener {
                stop()
            }
            player.setOnErrorListener { _, _, _ ->
                onError("播放失败")
                stop()
                true
            }
            player.prepareAsync()
            mediaPlayer = player
            currentPath = path
        } catch (exception: Exception) {
            onError("播放失败：${exception.localizedMessage ?: "未知错误"}")
            runCatching { player.release() }
            mediaPlayer = null
            currentPath = null
            PlaybackStateStore.update(PlaybackStatus.Idle)
        }
    }

    private fun pause() {
        val player = mediaPlayer ?: return
        val path = currentPath ?: return
        try {
            player.pause()
            cancelTick()
            val duration = player.duration.coerceAtLeast(0)
            PlaybackStateStore.update(
                PlaybackStatus.Paused(
                    path = path,
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = duration,
                ),
            )
        } catch (_: IllegalStateException) {
            stop()
        }
    }

    private fun resume() {
        val player = mediaPlayer ?: return
        val path = currentPath ?: return
        try {
            player.start()
            val duration = player.duration.coerceAtLeast(0)
            PlaybackStateStore.update(
                PlaybackStatus.Playing(
                    path = path,
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = duration,
                ),
            )
            startTick()
        } catch (_: IllegalStateException) {
            stop()
        }
    }

    private fun startTick() {
        cancelTick()
        tickJob = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                val player = mediaPlayer ?: break
                val path = currentPath ?: break
                val position = runCatching { player.currentPosition }.getOrNull() ?: break
                val duration = runCatching { player.duration }.getOrNull() ?: 0
                if (PlaybackStateStore.status.value is PlaybackStatus.Playing) {
                    PlaybackStateStore.update(
                        PlaybackStatus.Playing(
                            path = path,
                            positionMs = position.coerceAtLeast(0),
                            durationMs = duration.coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    private fun cancelTick() {
        tickJob?.cancel()
        tickJob = null
    }
}
