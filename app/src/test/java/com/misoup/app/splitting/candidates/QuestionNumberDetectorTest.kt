package com.misoup.app.splitting.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionNumberDetectorTest {

    // ---------- 命中：阿拉伯数字 ----------

    @Test
    fun `matches arabic number with dot`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("1. 下列计算正确的是"))
    }

    @Test
    fun `matches arabic number with chinese dunhao`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("2、求函数定义域"))
    }

    @Test
    fun `matches arabic number with fullwidth dot`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("3．已知集合"))
    }

    @Test
    fun `matches arabic number with ascii closing paren`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("4) 证明"))
    }

    @Test
    fun `matches arabic number with fullwidth closing paren`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("5）设"))
    }

    @Test
    fun `matches leading whitespace before number`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("  6. 解方程"))
    }

    @Test
    fun `matches multi digit question number`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("12. 求值"))
    }

    // ---------- 命中：括号数字 ----------

    @Test
    fun `matches ascii paren number`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("(1) 已知"))
    }

    @Test
    fun `matches fullwidth paren number`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("（2）求证"))
    }

    // ---------- 命中：中文数字 ----------

    @Test
    fun `matches chinese numeral with dunhao`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("一、选择题"))
    }

    @Test
    fun `matches chinese numeral with dot`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("二. 填空题"))
    }

    @Test
    fun `matches multi char chinese numeral`() {
        assertTrue(QuestionNumberDetector.isQuestionStart("十二、综合题"))
    }

    // ---------- 误报防护 ----------

    @Test
    fun `option line with letter does not match`() {
        assertFalse(QuestionNumberDetector.isQuestionStart("A. 选项一"))
        assertFalse(QuestionNumberDetector.isQuestionStart("B、选项二"))
    }

    @Test
    fun `decimal number does not match`() {
        assertFalse(QuestionNumberDetector.isQuestionStart("3.5 分"))
    }

    @Test
    fun `plain text without number does not match`() {
        assertFalse(QuestionNumberDetector.isQuestionStart("下列计算正确的是"))
        assertFalse(QuestionNumberDetector.isQuestionStart("已知函数 f(x)"))
    }

    @Test
    fun `number in middle of text does not match`() {
        assertFalse(QuestionNumberDetector.isQuestionStart("答案：1. 略"))
    }

    @Test
    fun `empty and blank text does not match`() {
        assertFalse(QuestionNumberDetector.isQuestionStart(""))
        assertFalse(QuestionNumberDetector.isQuestionStart("   "))
    }

    // ---------- findBoundaries ----------

    @Test
    fun `findBoundaries returns indices of question blocks`() {
        val blocks = listOf(
            block("1. 第一题"),
            block("正文行"),
            block("2、第二题"),
            block("（3）第三题"),
        )
        assertEquals(listOf(0, 2, 3), QuestionNumberDetector.findBoundaries(blocks))
    }

    @Test
    fun `findBoundaries empty when no question start`() {
        val blocks = listOf(block("卷头"), block("正文"), block("A. 选项"))
        assertEquals(emptyList<Int>(), QuestionNumberDetector.findBoundaries(blocks))
    }

    private fun block(text: String): LineBlock {
        val lines = text.split(" ").mapIndexed { i, t ->
            TextLine(y = i * 20f, height = 15f, x = 0f, width = 100f, text = t)
        }
        return LineBlock.of(lines)
    }
}
