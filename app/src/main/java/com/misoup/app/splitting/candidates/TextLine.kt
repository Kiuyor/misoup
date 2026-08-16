package com.misoup.app.splitting.candidates

/**
 * 文本行（模块 06 T01a 输入）：det 输出单行的简化纯类型。
 *
 * 坐标约定：y/x 为行框左上角，height/width 为行框尺寸；
 * 方便 JVM 单测直接构造模拟行数据，不依赖 Android/OpenCV。
 */
data class TextLine(
    val y: Float,
    val height: Float,
    val x: Float,
    val width: Float,
    val text: String = "",
) {
    val top: Float get() = y
    val bottom: Float get() = y + height
    val left: Float get() = x
    val right: Float get() = x + width
    val centerY: Float get() = y + height / 2f
    val centerX: Float get() = x + width / 2f
}

/** 行聚类产出的行块：一组 y 间距 < 阈值的相邻行，带外接盒与拼接文本 */
data class LineBlock(
    val lines: List<TextLine>,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
) {
    val height: Float get() = bottom - top
    val width: Float get() = right - left

    /** 块文本（行文本以空格拼接；题号分界识别用） */
    val text: String get() = lines.joinToString(" ") { it.text }

    companion object {
        /** 由行列表构建（外接盒 = 所有行并集） */
        fun of(lines: List<TextLine>): LineBlock {
            require(lines.isNotEmpty()) { "行块不能为空" }
            return LineBlock(
                lines = lines,
                top = lines.minOf { it.top },
                bottom = lines.maxOf { it.bottom },
                left = lines.minOf { it.left },
                right = lines.maxOf { it.right },
            )
        }
    }
}
