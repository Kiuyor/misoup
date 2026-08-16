package com.misoup.app.data.store

import com.misoup.app.data.model.MisoupConfigConst
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错题目录自动命名：yyyyMMdd_HHmmss_<摘要前12字>
 *
 * 摘要 = 题干前 12 字，剔除空白/公式符号（DESIGN §5 定稿：
 * 自动命名规则 YYYYMMDD_HHMMSS_题目摘要，摘要取题干前 12 字剔除空白/公式符）。
 */
object NameGenerator {

    /**
     * 公式/排版符号（剔除）：LaTeX 定界符与命令符、常见标记、路径分隔符。
     * FINDING 8：路径分隔符 / 和 \ 必须剔除，否则含 / 的题干生成目录名后 File 创建失败。
     */
    private val FORMULA_CHARS = setOf(
        '\\', '/', '$', '{', '}', '^', '_', '[', ']',
        '|', '~', '`', '=', '<', '>', '*', '#', '&', '%'
    )

    /**
     * 生成目录名。空摘要时省略摘要段（仅时间戳），避免尾部下划线。
     *
     * @param timestamp 毫秒时间戳
     * @param summary 题干文本（原始，可含公式/空白，内部清洗）
     * @return 如 20260816_140523_二次函数区间最值
     */
    fun generate(timestamp: Long, summary: String): String {
        val datePart = SimpleDateFormat(
            MisoupConfigConst.NAME_DATE_PATTERN, Locale.US
        ).format(Date(timestamp))
        val cleaned = cleanSummary(summary)
        return if (cleaned.isEmpty()) datePart else "${datePart}_$cleaned"
    }

    /**
     * 清洗摘要：去公式符号 + 去空白 + 截前 12 字。
     * 注意：先截断后清洗会留下残缺符号，故先清洗后截断。
     */
    fun cleanSummary(summary: String): String {
        val stripped = summary.filter { it !in FORMULA_CHARS && !it.isWhitespace() }.trim()
        return if (stripped.length <= MisoupConfigConst.NAME_SUMMARY_LENGTH) {
            stripped
        } else {
            stripped.substring(0, MisoupConfigConst.NAME_SUMMARY_LENGTH)
        }
    }
}
