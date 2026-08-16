package com.misoup.app.recognition.paddle.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T04 ppocr_keys_v1 字典解析 + CTC greedy 解码 */
class DictMapperTest {

    private val dict = DictMapper.fromLines(listOf("一", "二", "三", "四", "五"))

    @Test
    fun `字典解析-每行一个字符-行号即类别id减一`() {
        assertEquals(5, dict.size)
        assertEquals('一', dict.charAt(1))
        assertEquals('五', dict.charAt(5))
        assertNull(dict.charAt(0))    // 0 是 CTC blank
        assertNull(dict.charAt(6))    // 越界
    }

    @Test
    fun `字典解析-容忍空行与CRLF`() {
        val d = DictMapper.fromText("一\r\n\r\n二\r\n三")
        assertEquals(3, d.size)
        assertEquals('一', d.charAt(1))
        assertEquals('三', d.charAt(3))
    }

    @Test
    fun `字典解析-多字符行忽略-空字典`() {
        assertEquals(0, DictMapper.fromLines(emptyList()).size)
        assertEquals(1, DictMapper.fromLines(listOf("", "ab", "中")).size)
    }

    @Test
    fun `CTC解码-合并重复-删除blank`() {
        // 0=blank, 1=一, 2=二, 3=三
        assertEquals("一二三", dict.decodeClassIds(listOf(0, 1, 1, 2, 0, 3, 3, 3)))
        assertEquals("一三二", dict.decodeClassIds(listOf(1, 1, 1, 3, 0, 2)))
        assertEquals("", dict.decodeClassIds(emptyList()))
        assertEquals("", dict.decodeClassIds(listOf(0, 0, 0)))
    }

    @Test
    fun `CTC解码-未知类别跳过不崩`() {
        assertEquals("一二", dict.decodeClassIds(listOf(1, 99, 2, -5)))
    }

    @Test
    fun `概率矩阵解码-逐时间步argmax-CTC解码`() {
        // T=3, C=4（0=blank, 1=一, 2=二, 3=三）
        val probs = floatArrayOf(
            0.1f, 0.9f, 0f, 0f,      // t0 → 一
            0f, 0f, 0.8f, 0.2f,      // t1 → 二
            0.9f, 0.05f, 0f, 0.05f,  // t2 → blank
        )
        assertEquals("一二", dict.decode(probs, timeSteps = 3, classes = 4))
    }

    @Test
    fun `概率矩阵解码-非法尺寸返回空`() {
        assertEquals("", dict.decode(FloatArray(0), timeSteps = 0, classes = 4))
        assertEquals("", dict.decode(FloatArray(8), timeSteps = 2, classes = 0))
    }
}
