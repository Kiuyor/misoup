package com.misoup.app.splitting.candidates

/**
 * T01b 题号分界识别：判断一行块文本是否为一道新题的开始。
 *
 * 支持模式（模块 06 文档 §实现路径）：
 * - 阿拉伯数字：`1.` `1、` `1．` `1)` `1）`（点/顿号/括号收尾）
 * - 括号数字：`(1)` `（1）`
 * - 中文数字：`一、` `一．` `一.`（顿号/点收尾；仅纯中文数字序列）
 *
 * 判定规则：题号必须位于块文本开头（允许前导空白）；块文本 = 块内行文本
 * 空格拼接（题号行与题干行常被聚类进同一块，故只取「首个以题号开头的行」
 * 语义 → 用块文本开头匹配即可，命中即视为分界）。
 *
 * 误报防护：
 * - 选项行 `A.` `B、` 不匹配（字母题号不在文档模式内）
 * - 小数 `3.5` 不匹配（数字 + 点后必须紧跟空白/行尾，或点后是中文/非数字）
 */
object QuestionNumberDetector {

    // `1.` `1、` `1．` `1)` `1）` —— 数字 + 分隔符，分隔符后必须是非数字（防小数）
    private val ARABIC = Regex("""^\s*\d{1,3}\s*[.、．)）](?!\d)""")

    // `(1)` `（1）` —— 括号内数字
    private val PAREN = Regex("""^\s*[（(]\s*\d{1,3}\s*[）)]""")

    // `一、` `一．` `一.` `十二、` —— 中文数字 + 顿号/点
    private val CHINESE = Regex("""^\s*[一二三四五六七八九十百千零两]+\s*[、.．]""")

    /** 文本是否为题号开头 */
    fun isQuestionStart(text: String): Boolean =
        ARABIC.containsMatchIn(text) || PAREN.containsMatchIn(text) || CHINESE.containsMatchIn(text)

    /**
     * 返回行块列表中「题号分界块」的下标（升序）。
     * 无分界 → 空列表（调用方按 T05a 提示手动裁剪）。
     */
    fun findBoundaries(blocks: List<LineBlock>): List<Int> =
        blocks.mapIndexedNotNull { index, block ->
            if (isQuestionStart(block.text)) index else null
        }
}
