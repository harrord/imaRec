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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.webbing.audiorec.FolderOption
import site.webbing.audiorec.GroupCountdownStore
import site.webbing.audiorec.ImaSettings
import site.webbing.audiorec.ImaUploader
import site.webbing.audiorec.InspirationModeStore
import site.webbing.audiorec.PauseGroupSegmentStore
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

    /**
     * 分组按钮反馈回调：非空文本表示在通知按钮行下方临时显示一行；
     * null 表示清除该行。Service 据此调用 NotificationHelper 更新通知。
     */
    var onGroupFeedback: ((String?) -> Unit)? = null

    /**
     * 暂停状态反馈回调：用于通知按钮行下方提示行与按钮文本的同步刷新。
     * 参数分别为 [toggleText] 暂停按钮文本（"暂停"/"继续"）与 [hintText] 提示行文案。
     * Service 据此调用 NotificationHelper 更新通知。
     */
    var onPauseFeedback: ((toggleText: String, hintText: String) -> Unit)? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioMonitor: AudioMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionStartMs = 0L
    private var segmentStartMs = 0L
    private var segmentIndex = 0
    private var currentFile: File? = null
    private var lastEndReason: String? = null

    /** 当前片段累计实际录音时长（不含暂停间隔），用于触发上传时长限制的分段。 */
    private var segmentRecordedMs = 0L
    /** 上一次采样时间戳，用于在采样循环中累计实际录音时长；0 表示需重新基准。 */
    private var lastSampleMs = 0L

    private var samplingJob: Job? = null
    private val dbWindow1s = DbWindowBuffer()
    private val dbWindow5s = DbWindowBuffer()

    private var engine: SegmentRuleEngine? = null
    /** 安静时暂停开关（会话开始时快照，会话期间不变）。 */
    private var silencePauseEnabled = false
    /** 移动时继续开关（会话开始时快照，会话期间不变）。 */
    private var stepResumeEnabled = false

    /** 定时停止的协程任务，到点后调用 stopSession() 结束录音会话。 */
    private var stopTimerJob: Job? = null

    /** 暂停选择窗口的 5 秒倒计时任务，到点后按点击次数进入对应暂停态。 */
    private var pauseSelectJob: Job? = null

    /** 定时暂停的每分钟递减任务，到 0 后自动恢复录音。 */
    private var pauseTimerJob: Job? = null

    /** 暂停选择窗口内的累计点击次数，用于 4 档循环映射。 */
    private var pauseSelectCount = 0

    /** 定时暂停的剩余分钟数，每分钟递减，到 0 自动恢复。 */
    private var pauseRemainingMinutes = 0

    /**
     * 暂停态分组按钮是否已执行过分段（方案 B）。
     *
     * - 进入暂停态时重置为 false，分组按钮可点击
     * - 暂停态点分组按钮启动 5 秒倒计时，到点执行分段后置 true，分组按钮变灰
     * - 进入 PausedInspiration 时也置 true（灵感期间分组按钮禁用）
     * - resumeRecording() 恢复录音时重置为 false，分组按钮恢复可用
     *
     * 由 [RecordingStateStore] 间接暴露给通知层，决定暂停态下分组按钮是否可点击。
     */
    private var pauseGroupSegmentDone = false

    /**
     * 暂停态用于通知展示的占位文件。
     *
     * 暂停态下点分组/灵感按钮会释放 recorder（mediaRecorder=null、currentFile=null），
     * 但 [RecordingStatus.Paused] 仍需一个 file 参数用于通知展示（不参与录音逻辑）。
     * 进入暂停态时记录当前文件，释放后用此字段占位。
     */
    private var lastPausedFile: File? = null

    /**
     * 分组按钮的 5 秒倒计时任务。每次点击分组按钮都会取消上一个并重启，
     * 到点后清除通知反馈行并执行 [manualSegment]（上传当前段 + 开新段）。
     */
    private var kbSwitchJob: Job? = null

    /**
     * 灵感按钮反馈的清除任务。点击灵感按钮后显示反馈行（当前片段保存到的知识库），
     * 5 秒后由此任务清除。与 [kbSwitchJob] 互斥：灵感点击取消分组倒计时，分组点击取消本任务。
     */
    private var segmentFeedbackJob: Job? = null

    /**
     * 是否处于灵感记录模式。
     *
     * 单击灵感按钮进入：开新段继续录音，反馈行持续显示
     * "灵感开始记录，锁屏/再次点击将存入 xx"，期间产生的录音（手动保存或自动分段）
     * 一律归到灵感目标文件夹 [ImaConfig.inspirationFolderId]，且不受 10 秒上传限制。
     * 保存灵感有两种方式：再次点击灵感按钮，或按下电源键熄屏
     * （由 [stopInspirationByScreenOff] 触发）。保存后回到普通模式。
     * 灵感模式期间禁用暂停（[togglePause] guard）。
     */
    private var inspirationMode = false

    /** 内部阶段状态机，与 [RecordingStatus] 对应但更细粒度（区分手动暂停）。 */
    private sealed interface Phase {
        data object Idle : Phase
        data object Recording : Phase
        data object Monitoring : Phase
        /** 一直暂停：MediaRecorder 已 pause，需用户点继续恢复。 */
        data object PausedForever : Phase
        /** 定时暂停：MediaRecorder 已 pause，倒计时到 0 自动恢复。 */
        data object PausedTimed : Phase
        /**
         * 暂停选择窗口：用户点击暂停按钮后的 5 秒内，录音仍在继续（未真正 pause），
         * 按点击次数循环选择暂停模式。窗口结束按所选模式进入 PausedForever / PausedTimed。
         */
        data object PauseSelecting : Phase
        /**
         * 暂停态下进入灵感录制：recorder 已创建并 start，正在录制灵感段。
         * 结束灵感后回到 [origin] 对应的暂停态（定时暂停恢复剩余分钟数倒计时）。
         * 此状态下 inspirationMode == true，暂停按钮置灰不可点击。
         */
        data class PausedInspiration(val origin: PausedOrigin) : Phase
    }

    /** 暂停态来源，用于 [PausedInspiration] 结束后恢复正确的暂停子态。 */
    private sealed interface PausedOrigin {
        data object Forever : PausedOrigin
        data object Timed : PausedOrigin
    }

    private var phase: Phase = Phase.Idle

    /**
     * 待消费的地理触发地点备注。由 [forceSegmentByGeoTrigger] 或
     * [setPendingGeoLabel] 设置，下一次 [startNewSegment] 创建文件时嵌入文件名后清空。
     *
     * 普通录音路径不设置此字段，[startNewSegment] 传入空串，文件名退化为原格式，
     * 保证非地理触发的录音文件名完全不变。
     */
    private var pendingGeoLabel: String = ""

    /** 是否正在录音会话中（含 Recording / Monitoring / Paused* / PauseSelecting）。 */
    val isActive: Boolean get() = phase != Phase.Idle

    /** 会话开始：创建第一个片段并启动采样循环。幂等。 */
    fun startSession() {
        if (phase != Phase.Idle) return
        val config = settings.config.value
        silencePauseEnabled = config.silencePauseEnabled
        stepResumeEnabled = config.stepStartEnabled
        // 任一开关开启即构建引擎；引擎内部按各自开关注册对应条件
        engine = if (silencePauseEnabled || stepResumeEnabled) buildEngine(config) else null
        sessionStartMs = System.currentTimeMillis()
        segmentIndex = 0
        lastEndReason = null
        acquireWakeLock()
        // 步数传感器仅在"移动时继续"开启时启动，避免无用功耗
        if (stepResumeEnabled) stepProvider.start()
        startNewSegment(reason = null)
        // 定时停止：到达用户设定的时刻后自动结束会话（落盘并触发上传）
        if (config.stopAtEnabled) {
            startStopTimer(
                computeStopTargetMs(config.stopAtHour, config.stopAtMinute, sessionStartMs),
            )
        }
    }

    /**
     * 用户点击暂停/继续按钮。行为按当前阶段分流：
     *
     * - [Phase.Recording]：进入 [Phase.PauseSelecting]，开始 5 秒选择窗口（count=1 → 一直暂停）。
     *   录音在此期间不暂停，按钮文本保持"暂停"，提示行显示"5 秒后暂停"。
     * - [Phase.PauseSelecting]：count++ 并重启 5 秒窗口，按 count%4 切换提示文案
     *   （一直暂停 / 暂停 X 分钟 / 暂停 Y 分钟 / 暂停 Z 分钟）。
     * - [Phase.PausedForever] / [Phase.PausedTimed]：立即恢复录音（取消定时暂停倒计时）。
     *
     * 5 秒选择窗口结束（[pauseSelectJob] 到点）后才真正调用 recorder.pause()，
     * 按所选档位进入 PausedForever（一直暂停）或 PausedTimed（X/Y/Z 分钟，每分钟递减）。
     *
     * Monitoring / Idle 态忽略。
     */
    fun togglePause() {
        // 灵感模式下禁用暂停：避免「灵感模式 + 暂停」组合态带来的状态冲突
        // （暂停态下按电源键会破坏暂停语义、对 paused recorder 调 stop() 并开新段）。
        // 所有暂停入口（通知暂停键、MediaSession.onPause、蓝牙耳机按键、锁屏媒体控件）
        // 均走 togglePause()，一处 guard 即可全部堵住。
        // 覆盖 Phase.PausedInspiration（inspirationMode == true）。
        if (inspirationMode) return
        when (phase) {
            Phase.Recording -> {
                val file = currentFile ?: return
                enterPauseSelecting(file)
            }
            Phase.PauseSelecting -> {
                val file = currentFile ?: return
                cyclePauseSelecting(file)
            }
            Phase.PausedForever, Phase.PausedTimed -> {
                // recorder 可能已释放（暂停态点过分组/灵感），用 lastPausedFile 占位
                val file = currentFile ?: lastPausedFile ?: return
                scope.launch { resumeRecording(file) }
            }
            is Phase.PausedInspiration, Phase.Monitoring, Phase.Idle -> Unit
        }
    }

    /**
     * 进入暂停选择窗口：count=1（一直暂停），启动 5 秒倒计时。
     * 录音继续进行，仅刷新通知按钮文本为"暂停"、提示行为"5 秒后暂停"。
     * 同时取消分组/灵感按钮可能挂起的反馈任务，避免与暂停选择反馈行冲突。
     */
    private fun enterPauseSelecting(file: File) {
        pauseSelectCount = 1
        phase = Phase.PauseSelecting
        // 选择窗口期间取消分组/灵感反馈，提示行由暂停选择接管
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        onPauseFeedback?.invoke("暂停", "5 秒后暂停")
        startPauseSelectJob(file)
    }

    /**
     * 选择窗口内再次点击：count++，重启 5 秒窗口，按 count%4 更新提示文案。
     * 4 档循环：1→一直暂停，2→X 分钟，3→Y 分钟，4→Z 分钟，5→一直暂停…
     */
    private fun cyclePauseSelecting(file: File) {
        pauseSelectCount++
        val config = settings.config.value
        val hint = when ((pauseSelectCount - 1) % 4) {
            0 -> "5 秒后暂停"
            1 -> "暂停 ${config.pauseMinutesX} 分钟"
            2 -> "暂停 ${config.pauseMinutesY} 分钟"
            else -> "暂停 ${config.pauseMinutesZ} 分钟"
        }
        onPauseFeedback?.invoke("暂停", hint)
        startPauseSelectJob(file)
    }

    /**
     * 启动/重启 5 秒选择窗口倒计时。到点后按当前 count%4 进入对应暂停态：
     * - mode 0：一直暂停（PausedForever）
     * - mode 1/2/3：定时暂停 X/Y/Z 分钟（PausedTimed）
     *
     * 到点时才真正调用 recorder.pause()，选择窗口期间录音不中断。
     */
    private fun startPauseSelectJob(file: File) {
        pauseSelectJob?.cancel()
        pauseSelectJob = scope.launch {
            delay(PAUSE_SELECT_WINDOW_MS)
            val mode = (pauseSelectCount - 1) % 4
            val recorder = mediaRecorder ?: return@launch
            try {
                recorder.pause()
                // 暂停时取消采样循环与分组/分段反馈
                samplingJob?.cancel()
                samplingJob = null
                kbSwitchJob?.cancel()
                kbSwitchJob = null
                GroupCountdownStore.update(false)
                segmentFeedbackJob?.cancel()
                segmentFeedbackJob = null
                // 记录暂停态占位文件（recorder 后续可能被分组/灵感释放，用此占位展示通知）
                lastPausedFile = file
                // 重置分组按钮可用态：进入暂停态时分组按钮可点击
                pauseGroupSegmentDone = false
                PauseGroupSegmentStore.update(false)
                if (mode == 0) {
                    // 一直暂停
                    phase = Phase.PausedForever
                    publishStatus(RecordingStatus.Paused(file, remainingMinutes = null))
                    releaseWakeLock()
                } else {
                    // 定时暂停 X/Y/Z 分钟
                    val config = settings.config.value
                    val minutes = when (mode) {
                        1 -> config.pauseMinutesX
                        2 -> config.pauseMinutesY
                        else -> config.pauseMinutesZ
                    }
                    pauseRemainingMinutes = minutes
                    phase = Phase.PausedTimed
                    publishStatus(RecordingStatus.Paused(file, remainingMinutes = minutes))
                    releaseWakeLock()
                    startPauseCountdown(file)
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "pause failed", e)
            }
        }
    }

    /**
     * 定时暂停的每分钟递减任务。每 60 秒 pauseRemainingMinutes-- 并刷新通知提示
     * "暂停剩余 N 分钟"；到 0 时自动恢复录音。
     */
    private fun startPauseCountdown(file: File) {
        pauseTimerJob?.cancel()
        pauseTimerJob = scope.launch {
            while (isActive && phase == Phase.PausedTimed && pauseRemainingMinutes > 0) {
                delay(PAUSE_COUNTDOWN_INTERVAL_MS)
                if (phase != Phase.PausedTimed) return@launch
                pauseRemainingMinutes--
                if (pauseRemainingMinutes <= 0) {
                    // 倒计时结束，自动恢复录音
                    resumeRecording(file)
                    return@launch
                }
                // 刷新通知提示剩余分钟
                onPauseFeedback?.invoke("继续", "暂停剩余 $pauseRemainingMinutes 分钟")
            }
        }
    }

    /**
     * 恢复录音：取消定时暂停倒计时与选择窗口，recorder.resume()，回到 Recording。
     * 用于用户点继续（PausedForever/PausedTimed）或定时暂停到 0 自动恢复。
     *
     * 两条路径：
     * - recorder 仍存在（暂停态未点过分组/灵感）：直接 [MediaRecorder.resume]
     * - recorder 已释放（暂停态点过分组/灵感，mediaRecorder=null）：调 [startNewSegment] 创建新段
     *
     * 恢复时重置 [pauseGroupSegmentDone]=false，分组按钮恢复可用。
     */
    private suspend fun resumeRecording(file: File) {
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        pauseGroupSegmentDone = false
        PauseGroupSegmentStore.update(false)
        val recorder = mediaRecorder
        if (recorder != null) {
            try {
                recorder.resume()
                phase = Phase.Recording
                // 恢复后重置结束条件，避免暂停期间时间跳跃误触发
                engine?.onSegmentStart()
                publishStatus(RecordingStatus.Recording(file))
                acquireWakeLock()
                startSamplingLoop()
                // 灵感模式下恢复录音后，通知重建会清除反馈行，重新显示灵感提示
                if (inspirationMode) {
                    onGroupFeedback?.invoke("灵感开始记录，锁屏/再次点击将存入 ${inspirationFolderName()}")
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "resume failed", e)
            }
        } else {
            // recorder 已释放（暂停态点过分组/灵感）：创建新段
            startNewSegment(reason = "继续录音")
        }
    }

    /**
     * 灵感按钮单击入口。移除双击检测，单击立即响应。
     *
     * 按 phase 分流：
     * - [Phase.Recording]（普通录音态）：灵感模式单击保存灵感；普通模式执行分段+进入灵感模式
     * - [Phase.PausedInspiration]：结束灵感录制，保存到灵感文件夹，回到原暂停态（保持暂停）
     * - [Phase.PausedForever] / [Phase.PausedTimed]：落盘当前段（归当前分组，不开新段），
     *   创建灵感段并 resume recorder 录制灵感，phase 切到 PausedInspiration，保持暂停语义
     *   （暂停按钮文案仍为"继续"、置灰不可点击；倒计时暂停保留剩余分钟数）
     * - [Phase.PauseSelecting] / [Phase.Monitoring] / [Phase.Idle]：忽略
     *
     * 未配置灵感文件夹时按钮在通知层已禁用，不会进入此方法。
     */
    suspend fun manualSegment() {
        when (phase) {
            Phase.Recording -> {
                if (inspirationMode) saveInspirationSegment() else { performManualSegment(); enterInspirationMode() }
            }
            is Phase.PausedInspiration -> {
                savePausedInspirationSegment()
            }
            Phase.PausedForever, Phase.PausedTimed -> {
                enterPausedInspiration()
            }
            Phase.PauseSelecting, Phase.Monitoring, Phase.Idle -> return
        }
    }

    /**
     * 执行一次普通手动分段（落盘当前段 + 开新段 + 5 秒反馈行）。
     * 取消分组/分段/暂停可能挂起的任务，避免与本次分段冲突。
     */
    private suspend fun performManualSegment() {
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        manualSegmentInternal(retagFolderId = null)
        // 分段完成后显示反馈行：当前片段保存到的文件夹。startNewSegment 内部会
        // 先 publishStatus(Recording) 触发一次无反馈行的通知重建，此处再以反馈文本
        // 重建一次，反馈行最终可见。
        val folderName = currentFolderName()
        onGroupFeedback?.invoke("录音片段已保存到「$folderName」")
        segmentFeedbackJob = scope.launch {
            delay(SEGMENT_FEEDBACK_DURATION_MS)
            onGroupFeedback?.invoke(null)
        }
    }

    /**
     * 进入灵感记录模式：开新段继续录音，反馈行持续显示灵感提示文案。
     * 调用前应已通过 [performManualSegment] 截断保存上一段。
     * 灵感提示文案不被 5 秒反馈清除逻辑覆盖（不启动 segmentFeedbackJob）。
     *
     * 用户按电源键熄屏可正常触发 [stopInspirationByScreenOff] 保存灵感。
     */
    private fun enterInspirationMode() {
        inspirationMode = true
        InspirationModeStore.update(true)
        // 取消普通分段可能挂起的 5 秒反馈清除，避免灵感提示被清掉
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        val folderName = inspirationFolderName()
        onGroupFeedback?.invoke("灵感开始记录，锁屏/再次点击将存入 $folderName")
    }

    /**
     * 通过屏幕熄灭自动结束灵感记录。
     *
     * 由 [RecordingService] 收到 ACTION_SCREEN_OFF 广播时调用。仅在当前处于灵感模式时生效，
     * 普通录音模式下忽略（[inspirationMode] == false 直接返回），不影响普通录音。
     *
     * 按 phase 分流：
     * - [Phase.Recording]（普通录音态灵感）：复用 [saveInspirationSegment] 落盘+开新段继续录音
     * - [Phase.PausedInspiration]（暂停态灵感）：复用 [savePausedInspirationSegment] 落盘+回到暂停态
     *
     * 由于灵感模式已禁用暂停（[togglePause] 开头 guard），inspirationMode == true 时 phase 必然为
     * Recording 或 PausedInspiration。
     */
    fun stopInspirationByScreenOff() {
        if (!inspirationMode) return
        scope.launch {
            when (phase) {
                is Phase.PausedInspiration -> savePausedInspirationSegment()
                Phase.Recording -> saveInspirationSegment()
                else -> Unit
            }
        }
    }

    /**
     * 灵感模式下保存当前段到灵感目标文件夹并回到普通模式。
     *
     * - 把当前段 retag 到灵感文件夹 ID（同步文件名标签与上传目标）
     * - 不受 10 秒限制，无论时长都上传
     * - 上传目标指向灵感文件夹（[ImaUploader.enqueueUpload] 传入 overrideFolderId）
     * - 若灵感文件夹不在主页 activeFolders 则新建该 Tab（不改变当前选中态，默认上传文件夹保持不变）
     * - 开新段继续录音，回到普通模式，反馈行显示保存结果 5 秒后恢复默认
     */
    private fun saveInspirationSegment() {
        inspirationMode = false
        InspirationModeStore.update(false)
        val config = imaSettings.config.value
        val inspirationFolderId = config.inspirationFolderId
        val folderName = config.inspirationFolderName.ifBlank { inspirationFolderId }

        // 取消可能挂起的任务
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        pauseSelectJob?.cancel()
        pauseSelectJob = null

        // 落盘并上传到灵感文件夹（不受 10 秒限制）
        finalizeInspirationSegment(inspirationFolderId)

        // 仅把灵感文件夹加入主页 Tab 供用户查看，不改变当前选中态，确保后续录音仍上传到原默认文件夹
        imaSettings.addFolder(FolderOption(id = inspirationFolderId, name = folderName))

        // 开新段继续录音
        startNewSegment(reason = "灵感保存")

        // 显示保存结果反馈，5 秒后清除
        onGroupFeedback?.invoke("灵感已保存到「$folderName」")
        segmentFeedbackJob = scope.launch {
            delay(SEGMENT_FEEDBACK_DURATION_MS)
            onGroupFeedback?.invoke(null)
        }
    }

    /**
     * 暂停态进入灵感录制（方案 A：灵感期间 recorder 临时恢复录音）。
     *
     * 步骤：
     * 1. 落盘当前段（归当前选中文件夹，startNew=false 不开新段）—— recorder 被 stop/release/null
     * 2. 取消定时暂停倒计时（pauseTimerJob），但保留 [pauseRemainingMinutes] 数值供恢复用
     * 3. 取消暂停态分组按钮可能挂起的 5 秒倒计时（避免与灵感冲突），并置 [pauseGroupSegmentDone]=true
     *    （灵感期间分组按钮置灰不可点击）
     * 4. 调 [startNewSegment] 创建新段并 start recorder（用于录灵感）
     * 5. 覆盖 phase 为 [Phase.PausedInspiration]，记录 origin（Forever/Timed）供结束灵感时恢复
     * 6. 置 inspirationMode=true，反馈行显示灵感提示
     *
     * 此时 recorder 处于活跃录音状态（不是 paused），录到的就是灵感音频。
     * 用户再次点击灵感按钮时由 [savePausedInspirationSegment] 结束并回到原暂停态。
     */
    private suspend fun enterPausedInspiration() {
        val origin = when (phase) {
            Phase.PausedForever -> PausedOrigin.Forever
            Phase.PausedTimed -> PausedOrigin.Timed
            else -> return
        }
        // 1. 落盘当前段（归当前分组，不开新段）
        manualSegmentInternal(retagFolderId = null, startNew = false)
        // 2. 取消定时暂停倒计时（保留数值），取消分组倒计时
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        pauseGroupSegmentDone = true
        PauseGroupSegmentStore.update(true)
        // 3. 创建灵感段（startNewSegment 会 publishStatus(Recording)，需在之后覆盖 phase 与状态）
        startNewSegment(reason = "暂停灵感")
        phase = Phase.PausedInspiration(origin)
        // 4. 进入灵感模式
        inspirationMode = true
        InspirationModeStore.update(true)
        // 5. 重新 publish Paused 状态：通知层据此识别"暂停态灵感"（isPaused && inspirationActive），
        //    显示继续按钮置灰、灵感按钮呼吸动画。currentFile 是灵感段文件（实际录音中），
        //    但通知展示用 lastPausedFile 占位（不参与录音逻辑）。
        //    lastPausedFile 在进入暂停态时一定已赋值（startPauseSelectJob 到点时设置）
        publishStatus(RecordingStatus.Paused(lastPausedFile!!, remainingMinutes = pauseRemainingMinutes.takeIf { origin == PausedOrigin.Timed }))
        // 6. 反馈行显示灵感提示
        onGroupFeedback?.invoke("灵感开始记录，再次点击将存入 ${inspirationFolderName()}")
    }

    /**
     * 结束暂停态灵感录制：落盘灵感段（归灵感文件夹，跳过 10 秒限制）+ 释放 recorder +
     * 回到原暂停态（保持暂停语义，不退出暂停）。
     *
     * - [PausedOrigin.Forever]：回到 PausedForever，提示"人生记录已暂停"
     * - [PausedOrigin.Timed]：回到 PausedTimed，从进入灵感前保留的 [pauseRemainingMinutes] 继续倒计时
     *
     * recorder 释放后不再重建（保持暂停），直到用户点"继续"由 [resumeRecording] 创建新段。
     * 分组按钮仍保持 [pauseGroupSegmentDone]=true（灵感结束后分组按钮仍不可点击），
     * 直到 resumeRecording 重置。
     */
    private suspend fun savePausedInspirationSegment() {
        val origin = (phase as? Phase.PausedInspiration)?.origin ?: return
        val config = imaSettings.config.value
        val inspirationFolderId = config.inspirationFolderId
        val folderName = config.inspirationFolderName.ifBlank { inspirationFolderId }

        // 取消可能挂起的反馈清除任务
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null

        // 落盘灵感段（归灵感文件夹，跳过 10 秒限制）
        finalizeInspirationSegment(inspirationFolderId)
        imaSettings.addFolder(FolderOption(id = inspirationFolderId, name = folderName))

        // 退出灵感模式
        inspirationMode = false
        InspirationModeStore.update(false)

        // 恢复原暂停态
        // lastPausedFile 在进入暂停态时一定已赋值，此处非空
        val pausedFile = lastPausedFile!!
        when (origin) {
            PausedOrigin.Forever -> {
                phase = Phase.PausedForever
                publishStatus(RecordingStatus.Paused(pausedFile, remainingMinutes = null))
            }
            PausedOrigin.Timed -> {
                phase = Phase.PausedTimed
                publishStatus(RecordingStatus.Paused(pausedFile, remainingMinutes = pauseRemainingMinutes))
                if (pauseRemainingMinutes > 0) {
                    startPauseCountdown(pausedFile)
                } else {
                    // 剩余分钟数为 0（理论不会发生，防御性处理）：直接恢复录音
                    resumeRecording(pausedFile)
                }
            }
        }

        // 显示保存结果反馈，5 秒后清除
        onGroupFeedback?.invoke("灵感已保存到「$folderName」")
        segmentFeedbackJob = scope.launch {
            delay(SEGMENT_FEEDBACK_DURATION_MS)
            onGroupFeedback?.invoke(null)
        }
    }

    /**
     * 灵感片段落盘 + 上传：retag 到灵感文件夹，跳过 10 秒限制，上传目标指向灵感文件夹。
     * 与 [finalizeAndUploadCurrent] 的区别：不检查最小时长，且强制上传到指定文件夹。
     */
    private fun finalizeInspirationSegment(inspirationFolderId: String) {
        val file = currentFile
        val recorder = mediaRecorder
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            file?.delete()
            Log.e(TAG, "stop recorder failed", e)
        } finally {
            recorder?.releaseSafely()
            mediaRecorder = null
        }
        if (file != null && file.exists() && file.length() > 0) {
            // 把文件名 folder 标签重写为灵感文件夹，同步本地列表归属与上传目标
            val finalFile = fileManager.retagFolderId(file, inspirationFolderId)
            // 灵感录音不受 10 秒限制，直接上传到灵感文件夹
            uploader.enqueueUpload(finalFile, inspirationFolderId)
        }
        currentFile = null
    }

    /** 获取灵感目标文件夹的显示名称，未配置时返回"未设置"。 */
    private fun inspirationFolderName(): String {
        val config = imaSettings.config.value
        return config.inspirationFolderName.takeIf { it.isNotBlank() }
            ?: config.inspirationFolderId.takeIf { it.isNotBlank() }
            ?: "未设置"
    }

    /**
     * 分段的实际执行体：落盘当前段，可选开新段。
     *
     * @param retagFolderId 非空时，把当前段文件名中的文件夹 ID 重写为此值后再上传。
     *                      仅分组按钮 5 秒倒计时到点这条路径传入（切到新文件夹后，让当前段归到新文件夹）；
     *                      其他路径传 null，文件名保持创建时的文件夹不变。
     * @param startNew true（默认）落盘后开新段继续录音；false 仅落盘上传不开新段，
     *                 用于暂停态分组按钮到点——保持暂停状态，recorder 释放后不再重建，
     *                 直到用户点"继续"由 [resumeRecording] 创建新段。
     */
    private suspend fun manualSegmentInternal(retagFolderId: String?, startNew: Boolean = true) {
        samplingJob?.cancel()
        samplingJob = null
        finalizeAndUploadCurrent(retagFolderId)
        if (startNew) {
            startNewSegment(reason = "手动分段")
        }
    }

    /**
     * 获取当前选中文件夹的显示名称，用于分段后反馈提示当前片段归属。
     * 未选择文件夹或名称为空时返回"未分类"（上传到知识库根目录）。
     */
    private fun currentFolderName(): String {
        val config = imaSettings.config.value
        return config.activeFolders.firstOrNull { it.id == config.currentFolderId }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: "未分类"
    }

    /**
     * 切换文件夹并启动 5 秒后台倒计时，到点后执行分段。
     *
     * 行为：
     * - 在主页已展开 Tab（activeFolders）之间循环切换到下一个文件夹
     * - 每次点击都弹 Toast 提示当前选中文件夹与 5 秒后自动分段
     * - 在通知按钮行下方临时显示反馈行（锁屏可见，弥补 Toast 在锁屏可能不弹的限制）
     * - 5 秒内再次点击：取消上一个倒计时、切换到下一个文件夹、重启倒计时
     * - 5 秒内无再点击：清除反馈行并执行分段；
     *   录音态：上传当前段 + 用新文件夹开新段（startNew=true）
     *   暂停态：仅上传当前段不开新段（startNew=false），recorder 释放后分组按钮变灰，
     *   暂停状态保持，直到用户点"继续"
     *   到点时把当前段文件名重打为切换后的新文件夹，使落盘归属与上传目标一致
     *
     * 仅在 Recording / PausedForever / PausedTimed 阶段且 activeFolders ≥ 2 时有效。
     * 暂停态下若 [pauseGroupSegmentDone] 已为 true（已用过分组按钮），则忽略点击。
     * PauseSelecting / PausedInspiration / Monitoring / Idle 态忽略。
     */
    fun switchFolder() {
        when (phase) {
            Phase.Recording -> Unit
            Phase.PausedForever, Phase.PausedTimed -> {
                // 暂停态：分组按钮已用过则忽略
                if (pauseGroupSegmentDone) return
            }
            else -> return
        }
        val folders = imaSettings.config.value.activeFolders
        if (folders.size < 2) return
        val currentId = imaSettings.config.value.currentFolderId
        val currentIndex = folders.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % folders.size
        val next = folders[nextIndex]
        imaSettings.selectFolder(next.id)

        Toast.makeText(
            service,
            "连续点击以切换保存的文件夹。当前选择的「${next.name}」，5 秒后自动执行分段",
            Toast.LENGTH_SHORT,
        ).show()
        onGroupFeedback?.invoke("当前选中：${next.name}，5 秒后自动分段")

        // 重启 5 秒倒计时：到点后清除反馈行并执行分段
        kbSwitchJob?.cancel()
        // 分组按钮接管反馈行，取消分段按钮可能挂起的反馈清除任务
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        GroupCountdownStore.update(true)
        val startNewOnTimeout = phase == Phase.Recording
        kbSwitchJob = scope.launch {
            try {
                delay(KB_SWITCH_DELAY_MS)
                onGroupFeedback?.invoke(null)
                GroupCountdownStore.update(false)
                // 倒计时期间状态可能变化（停止会取消本协程，这里只是防御性检查）
                if (phase != Phase.Recording && phase != Phase.PausedForever && phase != Phase.PausedTimed) return@launch
                // 把当前段（在旧文件夹下开始录的）重打为切换后的新文件夹，
                // 使落盘文件名标签与上传目标、本地列表归属一致
                val newFolderId = imaSettings.config.value.currentFolderId
                manualSegmentInternal(retagFolderId = newFolderId, startNew = startNewOnTimeout)
                // 暂停态到点后：分组按钮变灰（pauseGroupSegmentDone=true），通知重建
                if (!startNewOnTimeout) {
                    pauseGroupSegmentDone = true
                PauseGroupSegmentStore.update(true)
                publishStatus(RecordingStatus.Paused(lastPausedFile!!, remainingMinutes = pauseRemainingMinutes.takeIf { phase == Phase.PausedTimed }))
                }
            } catch (e: CancellationException) {
                // 倒计时被取消（暂停/停止/灵感等打断）：重置分组倒计时状态
                GroupCountdownStore.update(false)
                throw e
            }
        }
    }

    /**
     * 地理触发强制分段：任何活跃状态都强制落盘当前段（含上传）+ 开新段，新段文件名带 [label]。
     *
     * 行为按当前阶段分流（spec「进入触发时的状态处理：任何状态都强制触发」）：
     * - [Phase.Recording]：取消挂起的分段/分组/暂停任务，落盘当前段（普通模式遵守 10s 上传规则；
     *   灵感模式按灵感逻辑保存到灵感文件夹），退出灵感模式，开新段带 label
     * - [Phase.PauseSelecting] / [Phase.PausedForever] / [Phase.PausedTimed]：
     *   直接结束当前段（recorder.stop）+ 开新段带 label（spec 建议方案）
     * - [Phase.Monitoring]：停止间隔期 AudioMonitor，开新段带 label
     * - [Phase.Idle]：仅设置 [pendingGeoLabel]，等待外部调用 [startSession] 启动新会话
     *
     * 触发动作与用户手动点击「分段」按钮效果等价（含上传、文件列表刷新），
     * 且会安全打断暂停倒计时、灵感模式、分组倒计时等可能冲突的状态。
     *
     * 不重置定时停止计时（spec：地理触发开新段不应重置定时停止计时）。
     *
     * @param label 地点备注（未经清洗），内部会经 [RecordingFileManager.sanitizeLocationLabel] 清洗
     */
    suspend fun forceSegmentByGeoTrigger(label: String) {
        val cleanLabel = RecordingFileManager.sanitizeLocationLabel(label)
        pendingGeoLabel = cleanLabel
        cancelPendingSegmentJobsForGeo()

        when (phase) {
            Phase.Recording -> {
                samplingJob?.cancel()
                samplingJob = null
                // 灵感模式：当前段按灵感逻辑保存（灵感文件夹 + 跳过 10s 限制），
                // 然后退出灵感模式，新段为地理触发段（普通模式）
                if (inspirationMode) {
                    finalizeAndUploadCurrent()
                    inspirationMode = false
                    InspirationModeStore.update(false)
                } else {
                    finalizeAndUploadCurrent()
                }
                startNewSegment(reason = "地理触发")
            }
            Phase.PauseSelecting, Phase.PausedForever, Phase.PausedTimed -> {
                // 暂停态：直接结束当前段 + 开新段带 label（spec 建议方案）
                // 不走 stopSession 以避免触发 Idle → stopForeground
                samplingJob?.cancel()
                samplingJob = null
                if (inspirationMode) {
                    finalizeAndUploadCurrent()
                    inspirationMode = false
                    InspirationModeStore.update(false)
                } else {
                    finalizeAndUploadCurrent()
                }
                startNewSegment(reason = "地理触发")
            }
            is Phase.PausedInspiration -> {
                // 暂停态灵感录制中：落盘灵感段（归灵感文件夹）+ 退出灵感模式 + 开新段带 label
                samplingJob?.cancel()
                samplingJob = null
                finalizeAndUploadCurrent()
                inspirationMode = false
                InspirationModeStore.update(false)
                pauseGroupSegmentDone = false
                PauseGroupSegmentStore.update(false)
                startNewSegment(reason = "地理触发")
            }
            Phase.Monitoring -> {
                // 间隔期：停止监测，开新段带 label
                audioMonitor?.stop()
                audioMonitor = null
                startNewSegment(reason = "地理触发")
            }
            Phase.Idle -> Unit // pendingGeoLabel 已设置，等待外部 startSession
        }
    }

    /**
     * 设置待消费的地理触发地点备注，供下一次 [startSession] 创建的第一个片段使用。
     *
     * 用于地理触发在 Idle 态启动新录音会话的场景：[RecordingService] 收到触发动作时
     * 若 controller 未活跃，调用此方法设置 label 后再 [startRecording]，
     * [startSession] → [startNewSegment] 会消费此 label 嵌入文件名。
     */
    fun setPendingGeoLabel(label: String) {
        pendingGeoLabel = RecordingFileManager.sanitizeLocationLabel(label)
    }

    /**
     * 取消所有可能挂起的分段/分组/暂停/反馈任务，避免与地理触发分段冲突。
     * 用于 [forceSegmentByGeoTrigger] 开始前清理状态。
     */
    private fun cancelPendingSegmentJobsForGeo() {
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        pauseGroupSegmentDone = false
        PauseGroupSegmentStore.update(false)
    }

    /** 结束整个会话：落盘当前片段并上传，释放所有资源。 */
    suspend fun stopSession() {
        samplingJob?.cancel()
        samplingJob = null
        stopTimerJob?.cancel()
        stopTimerJob = null
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        audioMonitor?.stop()
        audioMonitor = null

        when (phase) {
            Phase.Recording, Phase.PausedForever, Phase.PausedTimed, Phase.PauseSelecting,
            is Phase.PausedInspiration ->
                finalizeAndUploadCurrent()
            Phase.Monitoring -> Unit // 间隔期无文件，无需上传
            Phase.Idle -> Unit
        }

        // 落盘完成后才重置灵感模式状态，确保最后一段按灵感模式归到灵感 KB
        inspirationMode = false
        InspirationModeStore.reset()
        pauseGroupSegmentDone = false
        PauseGroupSegmentStore.reset()
        GroupCountdownStore.reset()
        lastPausedFile = null
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
        // 取当前选中的文件夹 ID 嵌入文件名，作为后续列表过滤的归属标识。
        // 未选择文件夹时为空串，文件名退化为无 _f 后缀格式，归类为「未分类」（上传到知识库根目录）。
        val folderId = imaSettings.config.value.currentFolderId
        // 消费地理触发的地点备注：非空时文件名插入地点备注（地理触发格式），
        // 空串时退化为原格式，保证非地理触发的录音文件名完全不变。
        val geoLabel = pendingGeoLabel
        pendingGeoLabel = ""
        val file = fileManager.createRecordingFile(folderId, geoLabel)
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
            scope.launch { stopSession() }
            return
        }

        mediaRecorder = recorder
        currentFile = file
        segmentIndex++
        segmentStartMs = System.currentTimeMillis()
        segmentRecordedMs = 0L
        phase = Phase.Recording
        engine?.onSegmentStart()
        dbWindow1s.clear()
        dbWindow5s.clear()
        publishStatus(RecordingStatus.Recording(file))
        updateSegmentInfo(inMonitoring = false)
        startSamplingLoop()
    }

    /** 进入间隔期：结束当前片段并上传，启动 AudioMonitor 纯监测。 */
    private suspend fun enterMonitoring(reason: String) {
        samplingJob?.cancel()
        samplingJob = null
        // 自动分段进入间隔期时，取消分组按钮可能挂起的 5 秒倒计时，避免与间隔期逻辑冲突
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        GroupCountdownStore.update(false)
        // 间隔期通知不再使用卡片反馈行，取消分段按钮可能挂起的反馈清除任务
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        // 取消可能挂起的暂停选择窗口
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
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
        lastSampleMs = 0L
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
                // 累计实际录音时长（不含暂停间隔），暂停后由 startSamplingLoop 重置基准
                if (lastSampleMs > 0) {
                    segmentRecordedMs += (now - lastSampleMs)
                }
                lastSampleMs = now
                // 上传硬性限制：文件大小 ≥ 60MB 或实际录音时长 ≥ 1小时59分时立即分段，
                // 60MB 远低于 200MB 上传上限，主要目的是降低大文件后台上传的 broken pipe 风险；
                // 时长仍保留 1 分钟余量以满足 2 小时上传约束
                val fileSize = currentFile?.length() ?: 0L
                if (fileSize >= MAX_SEGMENT_SIZE_BYTES || segmentRecordedMs >= MAX_SEGMENT_DURATION_MS) {
                    samplingJob?.cancel()
                    samplingJob = null
                    finalizeAndUploadCurrent()
                    startNewSegment(reason = "超过上传限制")
                    break
                }
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
                    if (inspirationMode) {
                        // 灵感模式下不进入间隔期，直接落盘当前段（归灵感文件夹）+ 开新段继续灵感录音。
                        // 这样用户在灵感期间安静一会儿也不会中断，无需步数变化即可继续。
                        samplingJob?.cancel()
                        samplingJob = null
                        finalizeAndUploadCurrent()
                        startNewSegment(reason = "灵感分段")
                        // startNewSegment 触发的通知重建会清除反馈行，重新显示灵感提示
                        onGroupFeedback?.invoke("灵感开始记录，锁屏/再次点击将存入 ${inspirationFolderName()}")
                    } else {
                        enterMonitoring(reason = "安静持续")
                    }
                    break
                }
            }
        }
    }

    /**
     * 停止 MediaRecorder 并把当前文件加入上传队列。过短的片段保留到本地但不上传。
     *
     * 灵感模式下（[inspirationMode] 为 true）：当前段 retag 到灵感文件夹并跳过 10 秒限制，
     * 上传目标指向灵感文件夹。覆盖自动分段进入间隔期、上传限制触发分段等路径。
     *
     * @param retagFolderId 非空时，在 recorder.stop() 关闭文件后，把文件名中的文件夹 ID
     *                      重写为此值（rename 磁盘文件）再读时长/入队上传。仅分组按钮
     *                      5 秒倒计时到点这条路径传入；其他路径传 null 保持原文件名。
     *                      灵感模式下此参数被忽略，统一使用灵感文件夹 ID。
     */
    private suspend fun finalizeAndUploadCurrent(retagFolderId: String? = null) {
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
            // 灵感模式下统一归到灵感文件夹；否则按调用方传入的 retagFolderId 重打标签
            val effectiveFolderId = if (inspirationMode) {
                imaSettings.config.value.inspirationFolderId
            } else {
                retagFolderId
            }
            val finalFile = if (effectiveFolderId != null) {
                withContext(Dispatchers.IO) { fileManager.retagFolderId(file, effectiveFolderId) }
            } else {
                file
            }
            val durationMs = getSegmentDurationMs(finalFile)
            if (!inspirationMode && durationMs < MIN_SEGMENT_DURATION_MS) {
                // 普通模式：10 秒以内的片段保留到本地，但不上传。
                // 覆盖手动停止、手动分段、自动分段、定时停止等所有产生文件的路径。
                // 灵感模式不受此限制，无论时长都上传。
                Log.d(TAG, "skip upload for short segment: ${finalFile.name} duration=${durationMs}ms")
                Toast.makeText(
                    service,
                    "录音时长不足 10 秒，已保存但不上传",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                // 灵感模式上传到灵感文件夹；普通模式上传到当前 config 的当前选中文件夹
                val uploadFolderId = if (inspirationMode) effectiveFolderId else null
                uploader.enqueueUpload(finalFile, uploadFolderId)
            }
        }
        currentFile = null
    }

    /**
     * 获取片段时长（毫秒）。优先用 [MediaMetadataRetriever] 读取文件真实时长，
     * 这样暂停期间未写入的数据不会计入，判断最准确；读取失败时退回墙钟时间兜底，
     * 避免因读取异常误删有效数据（墙钟含暂停时间会偏长，倾向保守少丢弃）。
     *
     * 使用 withContext(Dispatchers.IO) 将文件 I/O 移出主线程，避免大文件或存储繁忙时
     * 阻塞主线程导致 ANR。
     */
    private suspend fun getSegmentDurationMs(file: File): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
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
        // 结束条件（安静时暂停）：仅在 silencePauseEnabled 开启时注册
        val endConditions = mutableListOf<SegmentEndCondition>()
        if (config.silencePauseEnabled) {
            endConditions += SilenceSustainCondition(
                thresholdDb = config.silenceThresholdDb,
                sustainMinutes = config.silenceSustainMinutes,
            )
        }
        // 开始条件（移动时继续）：仅在 stepStartEnabled 开启时注册
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
        // 任一分段开关开启时才更新分段信息（用于主页状态展示）
        if (!silencePauseEnabled && !stepResumeEnabled) return
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
        ).also {
            // 设置 4 小时上限，防止异常路径下 releaseWakeLock 未被调用导致 WakeLock 永久持有
            it.acquire(4 * 60 * 60 * 1000L)
        }
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
        /** 分组按钮点击后等待再次点击的窗口期，期间无新点击则自动执行分段。 */
        private const val KB_SWITCH_DELAY_MS = 5_000L
        /** 灵感按钮点击后反馈行显示时长，到时自动清除。 */
        private const val SEGMENT_FEEDBACK_DURATION_MS = 5_000L
        /** 暂停按钮点击后的选择窗口期，期间无新点击则按当前档位进入暂停。 */
        private const val PAUSE_SELECT_WINDOW_MS = 5_000L
        /** 定时暂停的倒计时轮询间隔（1 分钟），到 0 自动恢复录音。 */
        private const val PAUSE_COUNTDOWN_INTERVAL_MS = 60_000L
        /** 片段最小上传时长，低于此值的片段保留到本地但不上传。 */
        private const val MIN_SEGMENT_DURATION_MS = 10_000L
        /** 单段文件大小上限（60MB），超过立即分段以降低大文件后台上传的 broken pipe 风险。 */
        private const val MAX_SEGMENT_SIZE_BYTES = 60L * 1024 * 1024
        /** 单段录音时长上限（1小时59分），超过立即分段以符合 2 小时上传限制。 */
        private const val MAX_SEGMENT_DURATION_MS = 119 * 60 * 1000L
    }
}
