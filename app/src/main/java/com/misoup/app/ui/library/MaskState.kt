package com.misoup.app.ui.library

/**
 * 遮罩开关状态（T06）：隐藏答案 toggle → 答案/解析区遮罩。
 *
 * - [hidden]：是否开启遮罩
 * - [revealProgress]：揭开预览动画进度 0..1（1=完全揭开）
 * - 遮罩可见性 = hidden && revealProgress < 1（完全揭开后即使开关还开着也不挡内容）
 *
 * 先全局遮罩（模块 02），模块 03 再细化答案区定位。
 */
data class MaskState(
    val hidden: Boolean = false,
    val revealProgress: Float = 0f
) {

    /** 切换开关：打开时重置揭开进度，关闭时归零 */
    fun toggle(): MaskState = if (hidden) {
        MaskState(hidden = false, revealProgress = 0f)
    } else {
        MaskState(hidden = true, revealProgress = 0f)
    }

    /** 更新揭开动画进度（钳制 0..1，非法值按边界处理） */
    fun withRevealProgress(progress: Float): MaskState =
        copy(revealProgress = progress.coerceIn(0f, 1f))

    /** 当前是否应显示遮罩层 */
    val maskVisible: Boolean
        get() = hidden && revealProgress < 1f

    /** 揭开动画是否已完成 */
    val fullyRevealed: Boolean
        get() = hidden && revealProgress >= 1f

    companion object {
        /** 动画未开始时的初始进度（隐藏态） */
        const val INITIAL_PROGRESS = 0f
        /** 动画完成阈值 */
        const val REVEAL_COMPLETE = 1f
    }
}
