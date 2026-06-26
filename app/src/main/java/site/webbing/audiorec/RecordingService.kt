package site.webbing.audiorec

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

class RecordingService : Service() {
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var fileManager: RecordingFileManager
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        fileManager = RecordingFileManager(this)
        notificationHelper.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TOGGLE_PAUSE -> togglePause()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseRecorder()
        releaseWakeLock()
        releaseMediaSession()
        RecordingStateStore.update(RecordingStatus.Idle)
        super.onDestroy()
    }

    private fun startRecording() {
        if (mediaRecorder != null) return

        val outputFile = fileManager.createRecordingFile()
        val recorder = createMediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(outputFile.absolutePath)
        }

        try {
            val startingStatus = RecordingStatus.Recording(outputFile)
            setupMediaSession()
            updateMediaSessionState(isRecording = true)
            startForeground(
                RECORDING_NOTIFICATION_ID,
                notificationHelper.buildRecordingNotification(startingStatus, mediaSession?.sessionToken),
            )
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            currentFile = outputFile
            acquireWakeLock()
            RecordingStateStore.update(startingStatus)
        } catch (exception: Exception) {
            recorder.releaseSafely()
            outputFile.delete()
            currentFile = null
            releaseMediaSession()
            RecordingStateStore.update(RecordingStatus.Idle)
            stopForegroundSafely()
            stopSelf()
            Toast.makeText(this, "开始录音失败：${exception.localizedMessage ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        val recorder = mediaRecorder
        val recordedFile = currentFile

        try {
            recorder?.stop()
        } catch (exception: RuntimeException) {
            recordedFile?.delete()
            Toast.makeText(this, "录音时间过短或保存失败，已丢弃本次录音", Toast.LENGTH_LONG).show()
        } finally {
            releaseRecorder()
            releaseWakeLock()
            releaseMediaSession()
            currentFile = null
            RecordingStateStore.update(RecordingStatus.Idle)
            stopForegroundSafely()
            stopSelf()
        }
    }

    private fun togglePause() {
        val recorder = mediaRecorder ?: return
        val file = currentFile ?: return

        when (RecordingStateStore.status.value) {
            is RecordingStatus.Recording -> {
                try {
                    recorder.pause()
                    val status = RecordingStatus.Paused(file)
                    RecordingStateStore.update(status)
                    updateMediaSessionState(isRecording = false)
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken)
                    releaseWakeLock()
                } catch (exception: RuntimeException) {
                    Toast.makeText(this, "暂停录音失败", Toast.LENGTH_SHORT).show()
                }
            }
            is RecordingStatus.Paused -> {
                try {
                    recorder.resume()
                    val status = RecordingStatus.Recording(file)
                    RecordingStateStore.update(status)
                    updateMediaSessionState(isRecording = true)
                    notificationHelper.updateRecordingNotification(status, mediaSession?.sessionToken)
                    acquireWakeLock()
                } catch (exception: RuntimeException) {
                    Toast.makeText(this, "继续录音失败", Toast.LENGTH_SHORT).show()
                }
            }
            RecordingStatus.Idle -> Unit
        }
    }

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.releaseSafely()
        mediaRecorder = null
    }

    private fun MediaRecorder.releaseSafely() {
        try {
            reset()
        } catch (_: RuntimeException) {
        }
        try {
            release()
        } catch (_: RuntimeException) {
        }
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "imaRec::recording-wakelock",
        ).also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun setupMediaSession() {
        releaseMediaSession()
        val openAppIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSessionCompat(this, "imaRecRecording").apply {
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (RecordingStateStore.status.value is RecordingStatus.Paused) togglePause()
                }

                override fun onPause() {
                    if (RecordingStateStore.status.value is RecordingStatus.Recording) togglePause()
                }

                override fun onStop() {
                    stopRecording()
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState(isRecording: Boolean) {
        val session = mediaSession ?: return
        val state = if (isRecording) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(playbackState)

        val title = if (isRecording) "正在录音" else "录音已暂停"
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "imaRec")
            .build()
        session.setMetadata(metadata)
    }

    private fun releaseMediaSession() {
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
    }

    companion object {
        const val ACTION_START_RECORDING = "site.webbing.audiorec.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "site.webbing.audiorec.action.STOP_RECORDING"
        const val ACTION_TOGGLE_PAUSE = "site.webbing.audiorec.action.TOGGLE_PAUSE"

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_RECORDING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }
}
