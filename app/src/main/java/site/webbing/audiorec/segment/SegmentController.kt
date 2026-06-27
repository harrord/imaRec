package site.webbing.audiorec.segment

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import site.webbing.audiorec.ImaSettings
import site.webbing.audiorec.ImaUploader
import site.webbing.audiorec.RecordingFileManager
import site.webbing.audiorec.RecordingService
import site.webbing.audiorec.RecordingStatus
import site.webbing.audiorec.RecordingStateStore
import site.webbing.audiorec.segment.conditions.SilenceSustainCondition
import site.webbing.audiorec.segment.conditions.StepCountStartCondition
import java.io.File
import java.util.Calendar

/**
 * 自动分段协调器：串联录音引擎、分贝采样、规则引擎、文件管理与上传触发。
 *
 * 职责：
 * - 管理 [MediaRecorder]（正式片段写文件）与 [AudioMonitor]（间隔期纯监测）的生命周期
 * - 维护会话状态机：[Phase.Idle] → [Phase.Recording] → [Phase.Monitoring] → [Phase.Recording] → …
 * - 驱动采样循环，构建 [ConditionContext] 并交由 [SegmentRuleEngine] 求值
 * - 切片时落盘并触发上传，状态变更写入 [RecordingStateStore] / [SegmentStateStore] / [AudioLevelStore]
 * - 通过 [onStatusUpdate] 回调通知 [RecordingService] 同步通知与 MediaSession
 *
 * 与 [RecordingService] 的分工：
 * - Controller 管录音引擎、WakeLock、切片逻辑、状态存储
 * - Service 管前台通知、MediaSession、Intent 分发、权限
 *
 * 未开启自动分段时，[engine] 为 null，整个会话只产生一个片段，行为与旧版一致。
 *
 * @param scope 由 Service 提供，使用 Main 调度器，Service onDestroy 时取消
 */
class SegmentController(
    private val service: RecordingService,
    private val fileManager: RecordingFileManager,
    private val uploader: ImaUploader,
    private val settings: SegmentSettings,
    private val imaSettings: ImaSettings,
    private val stepProvider: StepSensorProvider,
    private val scope: CoroutineScope,
) {
    /** 录音状态变化回调，Service 用于同步更新前台通知与 MediaSession。 */
    var onStatusUpdate: ((RecordingStatus) -> Unit)? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioMonitor: AudioMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionStartMs = 0L
    private var segmentStartMs = 0L
    private var segmentIndex = 0
    private var currentFile: File? = null
    private var lastEndReason: String? = null

    private var samplingJob: Job? = null
    private val dbWindow1s = DbWindowBuffer()
    private val dbWindow5s = DbWindowBuffer()

    private var engine: SegmentRuleEngine? = null
    private var autoSegmentEnabled = false

    /** 定时停止的协程任务，到点后调用 stopSession() 结束录音会话。 */
    private var stopTimerJob: Job? = null

    /** 内部阶段状态机，与 [RecordingStatus] 对应但更细粒度（区分手动暂停）。 */
    private sealed interface Phase {
        data object Idle : Phase
        data object Recording : Phase
        data object Monitoring : Phase
        data object Paused : Phase
    }

    private var phase: Phase = Phase.Idle

    /** 是否正在录音会话中（含 Recording / Monitoring / Paused）。 */
    val isActive: Boolean get() = phase != Phase.Idle

    /** 会话开始：创建第一个片段并启动采样循环。幂等。 */
    fun startSession() {
        if (phase != Phase.Idle) return
        val config = settings.config.value
        autoSegmentEnabled = config.autoSegmentEnabled
        engine = if (autoSegmentEnabled) buildEngine(config) else null
        sessionStartMs = System.currentTimeMillis()
        segmentIndex = 0
        lastEndReason = null
        acquireWakeLock()
        if (autoSegmentEnabled) stepProvider.start()
        startNewSegment(reason = null)
        // 定时停止：到达用户设定的时刻后自动结束会话（落盘并触发上传）
        if (config.stopAtEnabled) {
            startStopTimer(
                computeStopTargetMs(config.stopAtHour, config.stopAtMinute, sessionStartMs),
            )
        }
    }

    /** 用户手动暂停/继续，仅在 Recording ↔ Paused 间切换。Monitoring 状态下忽略。 */
    fun togglePause() {
        val recorder = mediaRecorder ?: return
        val file = currentFile ?: return
        when (phase) {
            Phase.Recording -> {
                try {
                    recorder.pause()
                    samplingJob?.cancel()
                    samplingJob = null
                    phase = Phase.Paused
                    publishStatus(RecordingStatus.Paused(file))
                    releaseWakeLock()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "pause failed", e)
                }
            }
            Phase.Paused -> {
                try {
                    recorder.resume()
                    phase = Phase.Recording
                    // 恢复后重置结束条件，避免暂停期间时间跳跃误触发
                    engine?.onSegmentStart()
                    publishStatus(RecordingStatus.Recording(file))
                    acquireWakeLock()
                    startSamplingLoop()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "resume failed", e)
                }
            }
            Phase.Monitoring, Phase.Idle -> Unit
        }
    }

    /**
     * 手动分段：结束当前片段并上传，立即开启新片段继续录音。
     *
     * 仅在 Recording 阶段有效；Paused / Monitoring / Idle 忽略，避免与暂停或间隔期逻辑冲突。
     * 与自动分段的 [enterMonitoring] 不同：手动分段不进入间隔期监测，直接开始下一段录音。
     */
    fun manualSegment() {
        if (phase != Phase.Recording) return
        samplingJob?.cancel()
        samplingJob = null
        finalizeAndUploadCurrent()
        startNewSegment(reason = "手动分段")
    }

    /** 结束整个会话：落盘当前片段并上传，释放所有资源。 */
    fun stopSession() {
        samplingJob?.cancel()
        samplingJob = null
        stopTimerJob?.cancel()
        stopTimerJob = null
        audioMonitor?.stop()
        audioMonitor = null

        when (phase) {
            Phase.Recording, Phase.Paused -> finalizeAndUploadCurrent()
            Phase.Monitoring -> Unit // 间隔期无文件，无需上传
            Phase.Idle -> Unit
        }

        stepProvider.stop()
        releaseWakeLock()
        phase = Phase.Idle
        currentFile = null
        AudioLevelStore.reset()
        SegmentStateStore.update(null)
        publishStatus(RecordingStatus.Idle)
    }

    // ── 片段生命周期 ──

    /** 开始一个新片段：创建文件、启动 MediaRecorder、进入 Recording 阶段。 */
    private fun startNewSegment(reason: String?) {
        lastEndReason = reason
        // 取当前选中的知识库 ID 嵌入文件名，作为后续列表过滤的归属标识。
        // 未选择知识库时为空串，文件名退化为旧格式（无 _kb 后缀），归类为「未分类」。
        val kbId = imaSettings.config.value.knowledgeBaseId
        val file = fileManager.createRecordingFile(kbId)
        val recorder = createMediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(file.absolutePath)
        }
        try {
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            recorder.releaseSafely()
            file.delete()
            Log.e(TAG, "start segment failed", e)
            // 录音引擎启动失败属于致命错误，结束会话
            stopSession()
            return
        }

        mediaRecorder = recorder
        currentFile = file
        segmentIndex++
        segmentStartMs = System.currentTimeMillis()
        phase = Phase.Recording
        engine?.onSegmentStart()
        dbWindow1s.clear()
        dbWindow5s.clear()
        publishStatus(RecordingStatus.Recording(file))
        updateSegmentInfo(inMonitoring = false)
        startSamplingLoop()
    }

    /** 进入间隔期：结束当前片段并上传，启动 AudioMonitor 纯监测。 */
    private fun enterMonitoring(reason: String) {
        samplingJob?.cancel()
        samplingJob = null
        finalizeAndUploadCurrent()

        val offset = settings.config.value.dbCalibrationOffset
        audioMonitor = AudioMonitor(offset) { db -> onMonitoringSample(db) }
        audioMonitor?.start()

        val since = System.currentTimeMillis()
        phase = Phase.Monitoring
        engine?.onMonitoringStart()
        dbWindow1s.clear()
        dbWindow5s.clear()
        publishStatus(RecordingStatus.Monitoring(since, segmentIndex))
        updateSegmentInfo(inMonitoring = true, monitoringSinceMs = since)
    }

    /** 间隔期采样回调（在 AudioMonitor 子线程）：驱动开始条件求值。 */
    private fun onMonitoringSample(db: Float) {
        AudioLevelStore.update(db)
        val engine = engine ?: return
        val now = System.currentTimeMillis()
        dbWindow1s.add(now, db, 1_000)
        dbWindow5s.add(now, db, 5_000)
        val ctx = ConditionContext(
            clockMs = now,
            sessionStartMs = sessionStartMs,
            segmentStartMs = now,
            currentDb = db,
            avgDbLast1s = dbWindow1s.average(),
            avgDbLast5s = dbWindow5s.average(),
        )
        if (engine.evaluateStart(ctx) == SegmentAction.StartNew) {
            // 切回主线程执行 MediaRecorder 操作
            scope.launch { startNewSegment(reason = "步数变化") }
        }
    }

    /** 正式片段采样循环：读 getMaxAmplitude → dB → 驱动结束条件求值。 */
    private fun startSamplingLoop() {
        samplingJob?.cancel()
        samplingJob = scope.launch {
            while (isActive && phase == Phase.Recording) {
                delay(SAMPLE_INTERVAL_MS)
                val recorder = mediaRecorder ?: break
                val amp = try {
                    recorder.maxAmplitude
                } catch (e: RuntimeException) {
                    0
                }
                val offset = settings.config.value.dbCalibrationOffset
                val db = DbCalculator.toDbSpl(amp, offset)
                AudioLevelStore.update(db)

                val now = System.currentTimeMillis()
                dbWindow1s.add(now, db, 1_000)
                dbWindow5s.add(now, db, 5_000)
                val ctx = ConditionContext(
                    clockMs = now,
                    sessionStartMs = sessionStartMs,
                    segmentStartMs = segmentStartMs,
                    currentDb = db,
                    avgDbLast1s = dbWindow1s.average(),
                    avgDbLast5s = dbWindow5s.average(),
                )
                val engineRef = engine
                if (engineRef != null && engineRef.evaluateEnd(ctx) == SegmentAction.EndCurrent) {
                    enterMonitoring(reason = "安静持续")
                    break
                }
            }
        }
    }

    /** 停止 MediaRecorder 并把当前文件加入上传队列。过短的片段保留到本地但不上传。 */
    private fun finalizeAndUploadCurrent() {
        val file = currentFile
        val recorder = mediaRecorder
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            // 录音过短等导致 stop 失败，删除残缺文件
            file?.delete()
            Log.e(TAG, "stop recorder failed", e)
        } finally {
            recorder?.releaseSafely()
            mediaRecorder = null
        }
        if (file != null && file.exists() && file.length() > 0) {
            val durationMs = getSegmentDurationMs(file)
            if (durationMs < MIN_SEGMENT_DURATION_MS) {
                // 10 秒以内的片段保留到本地，但不上传。
                // 覆盖手动停止、手动分段、自动分段、定时停止等所有产生文件的路径。
                Log.d(TAG, "skip upload for short segment: ${file.name} duration=${durationMs}ms")
                Toast.makeText(
                    service,
                    "录音时长不足 10 秒，已保存但不上传",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                uploader.enqueueUpload(file)
            }
        }
        currentFile = null
    }

    /**
     * 获取片段时长（毫秒）。优先用 [MediaMetadataRetriever] 读取文件真实时长，
     * 这样暂停期间未写入的数据不会计入，判断最准确；读取失败时退回墙钟时间兜底，
     * 避免因读取异常误删有效数据（墙钟含暂停时间会偏长，倾向保守少丢弃）。
     */
    private fun getSegmentDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: wallClockSegmentMs()
        } catch (e: Exception) {
            Log.e(TAG, "get segment duration failed", e)
            wallClockSegmentMs()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 墙钟估算的片段时长，含暂停时间会偏长，仅作兜底用。无基准时返回 MAX_VALUE 以保留文件。 */
    private fun wallClockSegmentMs(): Long =
        if (segmentStartMs > 0) System.currentTimeMillis() - segmentStartMs else Long.MAX_VALUE

    // ── 定时停止 ──

    /**
     * 计算定时停止的目标时间戳。
     *
     * 以会话开始时刻 [sessionStartMs] 为基准，把今天的 [hour]:[minute] 作为候选；
     * 若该时刻已不晚于会话开始（即今日的停止时间已过），则顺延到次日，
     * 保证跨午夜场景下也能正确触发。
     */
    private fun computeStopTargetMs(hour: Int, minute: Int, sessionStartMs: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= sessionStartMs) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /**
     * 启动定时停止轮询：每隔 [STOP_CHECK_INTERVAL_MS] 检查一次当前时间是否到达目标。
     *
     * 录音期间持有 WakeLock，CPU 不会休眠，轮询可稳定触发；
     * 到点后调用 [stopSession]，它内部会落盘当前片段并触发上传，
     * 满足"先保存并上传再停止"的要求。
     */
    private fun startStopTimer(targetMs: Long) {
        stopTimerJob?.cancel()
        stopTimerJob = scope.launch {
            while (isActive && phase != Phase.Idle) {
                delay(STOP_CHECK_INTERVAL_MS)
                if (System.currentTimeMillis() >= targetMs) {
                    stopSession()
                    break
                }
            }
        }
    }

    // ── 引擎构建（扩展点：新增条件在此注册） ──

    private fun buildEngine(config: SegmentConfig): SegmentRuleEngine {
        val endConditions = mutableListOf<SegmentEndCondition>(
            SilenceSustainCondition(
                thresholdDb = config.silenceThresholdDb,
                sustainMinutes = config.silenceSustainMinutes,
            ),
        )
        val startConditions = mutableListOf<SegmentStartCondition>()
        if (config.stepStartEnabled) {
            startConditions += StepCountStartCondition(
                stepProvider = stepProvider,
                threshold = config.stepStartThreshold,
            )
        }
        return SegmentRuleEngine(endConditions, startConditions)
    }

    // ── 状态广播 ──

    private fun publishStatus(status: RecordingStatus) {
        RecordingStateStore.update(status)
        onStatusUpdate?.invoke(status)
    }

    private fun updateSegmentInfo(inMonitoring: Boolean, monitoringSinceMs: Long = 0L) {
        if (!autoSegmentEnabled) return
        SegmentStateStore.update(
            SegmentInfo(
                segmentIndex = segmentIndex,
                lastEndReason = lastEndReason,
                inMonitoring = inMonitoring,
                monitoringSinceMs = monitoringSinceMs,
            ),
        )
    }

    // ── MediaRecorder 与 WakeLock 工具（沿用原 RecordingService 实现） ──

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(service)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "imaRec::recording-wakelock",
        ).also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "SegmentController"
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val STOP_CHECK_INTERVAL_MS = 30_000L
        /** 片段最小上传时长，低于此值的片段保留到本地但不上传。 */
        private const val MIN_SEGMENT_DURATION_MS = 10_000L
    }
}
