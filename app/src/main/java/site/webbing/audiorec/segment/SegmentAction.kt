package site.webbing.audiorec.segment

/** 规则引擎求值后建议 [SegmentController] 执行的动作。 */
sealed interface SegmentAction {
    /** 无动作。 */
    data object None : SegmentAction

    /** 结束当前片段：保存并上传当前文件，进入监测间隔期。 */
    data object EndCurrent : SegmentAction

    /** 结束间隔期：开始写入新片段文件。 */
    data object StartNew : SegmentAction
}
