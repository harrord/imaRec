package site.webbing.audiorec.segment

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * 间隔期（Monitoring 状态）的纯监测器：用 AudioRecord 读取 PCM 计算分贝，不写文件。
 *
 * 与正式片段期用 MediaRecorder.getMaxAmplitude() 区分开：
 * - 正式期：MediaRecorder 写文件 + getMaxAmplitude
 * - 间隔期：AudioRecord 读 PCM + 算 RMS → dB SPL（本类）
 *
 * 通过 [onLevel] 回调把当前分贝抛给 [SegmentController] 驱动规则引擎。
 *
 * @param calibrationOffset dBFS → dB SPL 校准偏移
 * @param onLevel 每次采样回调，参数为近似 dB SPL 正值
 */
class AudioMonitor(
    private val calibrationOffset: Int,
    private val onLevel: (Float) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var samplingThread: Thread? = null

    @Volatile
    private var running = false

    private val sampleRate = 44_100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int = run {
        val min = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        (min * 2).coerceAtLeast(sampleRate) // 至少容纳 1 秒数据
    }

    /** 启动监测。调用方需确保此时无其他录音占用 MIC。 */
    fun start() {
        if (running) return
        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.e(TAG, "AudioRecord init failed")
                return
            }
            audioRecord = record
            record.startRecording()
            running = true
            samplingThread = Thread { samplingLoop(record) }.apply {
                name = "AudioMonitor-sampling"
                isDaemon = true
                start()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord start failed: no RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed", e)
        }
    }

    /** 停止监测并释放资源。幂等。 */
    fun stop() {
        running = false
        samplingThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
            }
        }
        samplingThread = null
        audioRecord?.let { record ->
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
        audioRecord = null
    }

    private fun samplingLoop(record: AudioRecord) {
        // 每次读约 100ms 数据：44100 * 0.1 = 4410 samples
        val readSamples = sampleRate / 10
        val buffer = ShortArray(readSamples)
        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                var sumSq = 0.0
                for (i in 0 until read) {
                    val v = buffer[i].toDouble()
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / read)
                val db = DbCalculator.rmsToDbSpl(rms, calibrationOffset)
                onLevel(db)
            }
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    companion object {
        private const val TAG = "AudioMonitor"
        private const val SAMPLE_INTERVAL_MS = 100L
    }
}
