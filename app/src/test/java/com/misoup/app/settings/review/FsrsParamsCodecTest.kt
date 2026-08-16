package com.misoup.app.settings.review

import com.misoup.app.review.fsrs.Fsrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FSRS 参数编解码（模块 10 T04） */
class FsrsParamsCodecTest {

    @Test
    fun `默认参数 17 个且可往返`() {
        val text = FsrsParamsCodec.formatW(Fsrs.DEFAULT_W)
        val parsed = FsrsParamsCodec.parseW(text)
        assertTrue(parsed.isSuccess)
        val w = parsed.getOrThrow()
        assertEquals(17, w.size)
        for (i in Fsrs.DEFAULT_W.indices) {
            assertEquals(Fsrs.DEFAULT_W[i], w[i], 1e-9)
        }
    }

    @Test
    fun `中英文逗号与空白分隔均可解析`() {
        val nums = List(17) { i -> (i + 1).toDouble() }
        val withCn = nums.joinToString("，")
        val withSpace = nums.joinToString(" ")
        assertTrue(FsrsParamsCodec.parseW(withCn).isSuccess)
        assertTrue(FsrsParamsCodec.parseW(withSpace).isSuccess)
    }

    @Test
    fun `个数不对 - 拒绝并提示实际个数`() {
        val r = FsrsParamsCodec.parseW("0.1, 0.2")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("需要 17 个参数，当前 2 个"))
    }

    @Test
    fun `非数字 - 拒绝并定位第几个`() {
        val text = "0.40255, abc, " + (3..17).joinToString(", ") { "1.0" }
        val r = FsrsParamsCodec.parseW(text)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("第 2 个参数非法"))
    }

    @Test
    fun `负数与非有限数 - 拒绝`() {
        val neg = "0.1, " + (-1.0).toString() + ", " + (3..17).joinToString(", ") { "1.0" }
        assertTrue(FsrsParamsCodec.parseW(neg).isFailure)
        val nan = "0.1, NaN, " + (3..17).joinToString(", ") { "1.0" }
        assertTrue(FsrsParamsCodec.parseW(nan).isFailure)
        val inf = "0.1, Infinity, " + (3..17).joinToString(", ") { "1.0" }
        assertTrue(FsrsParamsCodec.parseW(inf).isFailure)
    }

    @Test
    fun `空文本 - 拒绝`() {
        assertTrue(FsrsParamsCodec.parseW("").isFailure)
        assertTrue(FsrsParamsCodec.parseW("   ").isFailure)
    }

    @Test
    fun `validate - 数量与数值域校验`() {
        assertTrue(FsrsParamsCodec.validate(Fsrs.DEFAULT_W).isSuccess)
        assertTrue(FsrsParamsCodec.validate(DoubleArray(16) { 1.0 }).isFailure)
        assertTrue(FsrsParamsCodec.validate(DoubleArray(17) { -1.0 }).isFailure)
        assertTrue(FsrsParamsCodec.validate(DoubleArray(17) { Double.NaN }).isFailure)
    }

    @Test
    fun `toParams - 组合 w 与 short-term 开关`() {
        val text = FsrsParamsCodec.formatW(Fsrs.DEFAULT_W)
        val params = FsrsParamsCodec.toParams(text, enableShortTerm = false).getOrThrow()
        assertEquals(false, params.enableShortTerm)
        assertTrue(params.w.contentEquals(Fsrs.DEFAULT_W))
    }

    @Test
    fun `格式化 - 保留 5 位小数`() {
        val formatted = FsrsParamsCodec.formatW(Fsrs.DEFAULT_W)
        assertTrue(formatted.contains("0.40255"))
        assertTrue(formatted.contains("15.69105"))
    }
}
