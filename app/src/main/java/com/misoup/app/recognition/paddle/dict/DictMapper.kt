package com.misoup.app.recognition.paddle.dict

/**
 * ppocr_keys_v1 字典解析与 CTC 解码（T04，纯逻辑）
 *
 * PP-OCRv4 rec 输出：T×C（时间步 × 类别数），C = 字典长度 + 1。
 * 类别 0 固定为 CTC blank（'\u0000' 占位）；类别 i（i≥1）对应字典第 i-1 行。
 *
 * 解码（CTC greedy）：合并相邻重复 → 删除 blank → 逐字符查字典。
 */
class DictMapper private constructor(
    /** 字典字符表（索引 = 类别 id - 1） */
    private val chars: List<Char>,
) {
    val size: Int get() = chars.size

    /** 类别 id（≥1）→ 字符；越界 → null（未知类别防御） */
    fun charAt(classId: Int): Char? = chars.getOrNull(classId - 1)

    /** 类别 id 序列 → 文本（CTC 合并 + 去 blank） */
    fun decodeClassIds(classIds: List<Int>): String {
        val sb = StringBuilder()
        var prev = -1
        for (id in classIds) {
            if (id == prev) continue          // CTC：合并相邻重复
            prev = id
            if (id <= 0) continue             // blank（0 或负保护）
            sb.append(charAt(id) ?: continue) // 未知类别跳过
        }
        return sb.toString()
    }

    /**
     * 概率矩阵解码（greedy）：每时间步取 argmax → CTC 解码。
     * @param probs T×C 行主序
     * @param timeSteps 时间步数 T
     * @param classes 类别数 C（≤ probs.size / T）
     */
    fun decode(probs: FloatArray, timeSteps: Int, classes: Int): String {
        if (timeSteps <= 0 || classes <= 0) return ""
        val ids = ArrayList<Int>(timeSteps)
        for (t in 0 until timeSteps) {
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            val base = t * classes
            for (c in 0 until classes) {
                val v = probs[base + c]
                if (v > bestScore) {
                    bestScore = v
                    best = c
                }
            }
            ids += best
        }
        return decodeClassIds(ids)
    }

    companion object {
        /** 从字典文本构造映射（每行一个字符，行号 = 类别 id - 1）；空行忽略 */
        fun fromText(dictText: String): DictMapper = fromLines(dictText.lineSequence().toList())

        fun fromLines(lines: List<String>): DictMapper {
            val chars = lines
                .mapIndexedNotNull { _, line ->
                    val c = line.trimEnd('\r')
                    // 仅保留恰好一个字符的行（跳过空行/注释/多字符行）
                    if (c.length == 1) c[0] else null
                }
            return DictMapper(chars)
        }
    }
}
