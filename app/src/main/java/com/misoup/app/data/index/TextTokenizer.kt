package com.misoup.app.data.index

/**
 * 中文分词（简单倒排用，1 万题量级足够）：
 * - ASCII 单词/数字串（小写化）→ 整词
 * - 连续中文 → 单字 + 二元组（bigram）
 *
 * 例："二次函数 f(x) 区间" → {二次, 次函, 函数, 二, 次, 函, 数, f, x, 区间, 区, 间}
 */
object TextTokenizer {

    private val ASCII_WORD = Regex("[A-Za-z0-9_]+")
    private val CJK_RUN = Regex("[\\u4e00-\\u9fa5]+")

    fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        if (text.isBlank()) return tokens
        ASCII_WORD.findAll(text).forEach { tokens.add(it.value.lowercase()) }
        CJK_RUN.findAll(text).forEach { m ->
            val s = m.value
            if (s.length == 1) {
                tokens.add(s)
            } else {
                s.forEach { ch -> tokens.add(ch.toString()) }
                for (i in 0 until s.length - 1) {
                    tokens.add(s.substring(i, i + 2))
                }
            }
        }
        return tokens
    }
}
