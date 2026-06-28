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
import site.webbing.audiorec.FolderOption
import site.webbing.audiorec.ImaSettings
import site.webbing.audiorec.ImaUploader
import site.webbing.audiorec.InspirationModeStore
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
    private var autoSegmentEnabled = false

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
     * 分组按钮的 5 秒倒计时任务。每次点击分组按钮都会取消上一个并重启，
     * 到点后清除通知反馈行并执行 [manualSegment]（上传当前段 + 开新段）。
     */
    private var kbSwitchJob: Job? = null

    /**
     * 分段按钮反馈的清除任务。点击分段按钮后显示反馈行（当前片段保存到的知识库），
     * 5 秒后由此任务清除。与 [kbSwitchJob] 互斥：分段点击取消分组倒计时，分组点击取消本任务。
     */
    private var segmentFeedbackJob: Job? = null

    /**
     * 分段按钮双击检测的延迟任务。第一次点击后启动 [DOUBLE_CLICK_WINDOW_MS] 计时，
     * 期间无第二次点击则视为单击执行普通分段；有第二次点击则触发双击进入灵感模式。
     * 灵感模式下不启用双击检测（任何点击立即保存灵感录音）。
     */
    private var segmentClickJob: Job? = null

    /**
     * 是否处于灵感记录模式。
     *
     * 双击分段按钮进入：开新段继续录音，反馈行持续显示
     * "灵感开始记录，锁屏/再次点击将存入 xx"，期间产生的录音（手动保存或自动分段）
     * 一律归到灵感目标文件夹 [ImaConfig.inspirationFolderId]，且不受 10 秒上传限制。
     * 保存灵感有两种方式：再次点击分段按钮（单击/双击均识别为单击），或按下电源键熄屏
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
    }

    private var phase: Phase = Phase.Idle

    /** 是否正在录音会话中（含 Recording / Monitoring / Paused* / PauseSelecting）。 */
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
        if (inspirationMode) return
        val recorder = mediaRecorder ?: return
        val file = currentFile ?: return
        when (phase) {
            Phase.Recording -> enterPauseSelecting(file)
            Phase.PauseSelecting -> cyclePauseSelecting(file)
            Phase.PausedForever, Phase.PausedTimed -> resumeRecording(file)
            Phase.Monitoring, Phase.Idle -> Unit
        }
    }

    /**
     * 进入暂停选择窗口：count=1（一直暂停），启动 5 秒倒计时。
     * 录音继续进行，仅刷新通知按钮文本为"暂停"、提示行为"5 秒后暂停"。
     * 同时取消分组/分段按钮可能挂起的反馈任务，避免与暂停选择反馈行冲突。
     */
    private fun enterPauseSelecting(file: File) {
        pauseSelectCount = 1
        phase = Phase.PauseSelecting
        // 选择窗口期间取消分组/分段反馈，提示行由暂停选择接管
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        // 取消分段按钮可能挂起的双击检测，避免暂停期间触发分段
        segmentClickJob?.cancel()
        segmentClickJob = null
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
                segmentFeedbackJob?.cancel()
                segmentFeedbackJob = null
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
     */
    private fun resumeRecording(file: File) {
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        val recorder = mediaRecorder ?: return
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
    }

    /**
     * 分段按钮点击入口。按当前是否处于灵感模式与双击状态分流：
     *
     * - 灵感模式：任何点击（单击或双击）立即保存灵感录音到灵感目标知识库并回到普通模式，
     *   不启用双击检测。
     * - 普通模式 + 第一次点击：启动 [DOUBLE_CLICK_WINDOW_MS] 延迟计时，期间无第二次点击
     *   则视为单击执行普通分段；有第二次点击则触发双击。
     * - 普通模式 + 1 秒内第二次点击（双击）：
     *   - 灵感目标文件夹已配置：执行一次普通分段（截断保存当前段），然后进入灵感模式。
     *   - 灵感目标文件夹未配置：双击识别为单击，仅执行一次普通分段，不进入灵感模式。
     *
     * 仅在 Recording 阶段有效；Paused / Monitoring / Idle 忽略。
     */
    fun manualSegment() {
        if (phase != Phase.Recording) return

        // 灵感模式：任何点击立即保存灵感录音并回到普通模式
        if (inspirationMode) {
            saveInspirationSegment()
            return
        }

        // 普通模式：双击检测
        if (segmentClickJob?.isActive == true) {
            // 第二次点击（1 秒内）= 双击
            segmentClickJob?.cancel()
            segmentClickJob = null
            val config = imaSettings.config.value
            if (config.inspirationFolderId.isNotBlank()) {
                // 已配置灵感文件夹：执行一次普通分段 + 进入灵感模式
                performManualSegment()
                enterInspirationMode()
            } else {
                // 未配置灵感文件夹：双击识别为单击，仅执行一次普通分段
                performManualSegment()
            }
        } else {
            // 第一次点击：启动 1 秒延迟计时，到点无第二次点击则执行普通分段
            segmentClickJob = scope.launch {
                delay(DOUBLE_CLICK_WINDOW_MS)
                segmentClickJob = null
                // 延迟期间状态可能变化（暂停/停止/分组/自动分段），防御性检查避免在不当时机分段
                if (phase != Phase.Recording) return@launch
                performManualSegment()
            }
        }
    }

    /**
     * 执行一次普通手动分段（落盘当前段 + 开新段 + 5 秒反馈行），与双击检测无关。
     * 取消分组/分段/暂停可能挂起的任务，避免与本次分段冲突。
     */
    private fun performManualSegment() {
        kbSwitchJob?.cancel()
        kbSwitchJob = null
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
     * 内部复用 [saveInspirationSegment] 完成落盘、上传、切回普通模式、开新段继续录音。
     *
     * 由于灵感模式已禁用暂停（[togglePause] 开头 guard），inspirationMode == true 时 phase 必然为
     * Recording，此处无需额外检查 phase。
     */
    fun stopInspirationByScreenOff() {
        if (!inspirationMode) return
        saveInspirationSegment()
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
        segmentClickJob?.cancel()
        segmentClickJob = null
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        kbSwitchJob?.cancel()
        kbSwitchJob = null
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
     * 分段的实际执行体：落盘当前段 + 开新段。
     *
     * @param retagFolderId 非空时，把当前段文件名中的文件夹 ID 重写为此值后再上传。
     *                      仅分组按钮 5 秒倒计时到点这条路径传入（切到新文件夹后，让当前段归到新文件夹）；
     *                      其他路径传 null，文件名保持创建时的文件夹不变。
     */
    private fun manualSegmentInternal(retagFolderId: String?) {
        samplingJob?.cancel()
        samplingJob = null
        finalizeAndUploadCurrent(retagFolderId)
        startNewSegment(reason = "手动分段")
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
     * - 5 秒内无再点击：清除反馈行并执行分段（上传当前段 + 用新文件夹开新段）；
     *   到点时把当前段文件名重打为切换后的新文件夹，使落盘归属与上传目标一致
     *
     * 仅在 Recording 阶段且 activeFolders ≥ 2 时有效；其他态或无可切换文件夹时忽略。
     */
    fun switchFolder() {
        if (phase != Phase.Recording) return
        val folders = imaSettings.config.value.activeFolders
        if (folders.size < 2) return
        // 分组按钮接管，取消分段按钮可能挂起的双击检测
        segmentClickJob?.cancel()
        segmentClickJob = null
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
        kbSwitchJob = scope.launch {
            delay(KB_SWITCH_DELAY_MS)
            onGroupFeedback?.invoke(null)
            // 倒计时期间状态可能变化（暂停/停止会取消本协程，这里只是防御性检查）
            if (phase != Phase.Recording) return@launch
            // 把当前段（在旧文件夹下开始录的）重打为切换后的新文件夹，
            // 使落盘文件名标签与上传目标、本地列表归属一致
            val newFolderId = imaSettings.config.value.currentFolderId
            manualSegmentInternal(retagFolderId = newFolderId)
        }
    }

    /** 结束整个会话：落盘当前片段并上传，释放所有资源。 */
    fun stopSession() {
        samplingJob?.cancel()
        samplingJob = null
        stopTimerJob?.cancel()
        stopTimerJob = null
        kbSwitchJob?.cancel()
        kbSwitchJob = null
        segmentFeedbackJob?.cancel()
        segmentFeedbackJob = null
        segmentClickJob?.cancel()
        segmentClickJob = null
        pauseSelectJob?.cancel()
        pauseSelectJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        audioMonitor?.stop()
        audioMonitor = null

        when (phase) {
            Phase.Recording, Phase.PausedForever, Phase.PausedTimed, Phase.PauseSelecting ->
                finalizeAndUploadCurrent()
            Phase.Monitoring -> Unit // 间隔期无文件，无需上传
            Phase.Idle -> Unit
        }

        // 落盘完成后才重置灵感模式状态，确保最后一段按灵感模式归到灵感 KB
        inspirationMode = false
        InspirationModeStore.reset()
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
        val file = fileManager.createRecordingFile(folderId)
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
    private fun enterMonitoring(reason: String) {
        samplingJob?.cancel()
        samplingJob = null
        // 自动分段进入间隔期时，取消分组按钮可能挂起的 5 秒倒计时，避免与间隔期逻辑冲突
        kbSwitchJob?.cancel()
        kbSwitchJob = null
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
    private fun finalizeAndUploadCurrent(retagFolderId: String? = null) {
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
            val finalFile = if (effectiveFolderId != null) fileManager.retagFolderId(file, effectiveFolderId) else file
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
        /** 分组按钮点击后等待再次点击的窗口期，期间无新点击则自动执行分段。 */
        private const val KB_SWITCH_DELAY_MS = 5_000L
        /** 分段按钮点击后反馈行显示时长，到时自动清除。 */
        private const val SEGMENT_FEEDBACK_DURATION_MS = 5_000L
        /** 分段按钮双击检测窗口：第一次点击后在此时间内有第二次点击则判定为双击。 */
        private const val DOUBLE_CLICK_WINDOW_MS = 1_000L
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
