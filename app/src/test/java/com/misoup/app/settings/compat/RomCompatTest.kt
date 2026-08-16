package com.misoup.app.settings.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ROM 白名单检测（模块 10 T09） */
class RomCompatTest {

    @Test
    fun `vivo 检测 - 大小写不敏感`() {
        assertEquals(RomKind.VIVO, RomCompat.detect("vivo"))
        assertEquals(RomKind.VIVO, RomCompat.detect("VIVO"))
        assertEquals(RomKind.VIVO, RomCompat.detect("vivo 2233"))
    }

    @Test
    fun `iQOO 归 vivo 系 - 验收标准指定 iQOO 真机`() {
        assertEquals(RomKind.VIVO, RomCompat.detect("iQOO"))
        assertEquals(RomKind.VIVO, RomCompat.detect("iqoo"))
    }

    @Test
    fun `小米系检测 - xiaomi redmi poco`() {
        assertEquals(RomKind.XIAOMI, RomCompat.detect("Xiaomi"))
        assertEquals(RomKind.XIAOMI, RomCompat.detect("Redmi"))
        assertEquals(RomKind.XIAOMI, RomCompat.detect("POCO"))
    }

    @Test
    fun `华为系检测 - huawei honor`() {
        assertEquals(RomKind.HUAWEI, RomCompat.detect("HUAWEI"))
        assertEquals(RomKind.HUAWEI, RomCompat.detect("HONOR"))
    }

    @Test
    fun `其他厂商与空值 - OTHER 无引导`() {
        assertEquals(RomKind.OTHER, RomCompat.detect("samsung"))
        assertEquals(RomKind.OTHER, RomCompat.detect("google"))
        assertEquals(RomKind.OTHER, RomCompat.detect(""))
        assertEquals(RomKind.OTHER, RomCompat.detect("unknown"))
    }

    @Test
    fun `白名单 ROM 需要引导 - 其他不需要`() {
        assertTrue(RomCompat.needsWhitelistGuide(RomKind.VIVO))
        assertTrue(RomCompat.needsWhitelistGuide(RomKind.XIAOMI))
        assertTrue(RomCompat.needsWhitelistGuide(RomKind.HUAWEI))
        assertFalse(RomCompat.needsWhitelistGuide(RomKind.OTHER))
    }

    @Test
    fun `引导文案 - 三家各有对应指引且含关键路径`() {
        val vivo = RomCompat.guideText(RomKind.VIVO)
        assertNotNull(vivo)
        assertTrue(vivo!!.contains("后台耗电管理"))
        assertTrue(vivo.contains("白名单"))

        val xiaomi = RomCompat.guideText(RomKind.XIAOMI)
        assertNotNull(xiaomi)
        assertTrue(xiaomi!!.contains("省电策略"))
        assertTrue(xiaomi.contains("无限制"))

        val huawei = RomCompat.guideText(RomKind.HUAWEI)
        assertNotNull(huawei)
        assertTrue(huawei!!.contains("应用启动管理"))
    }

    @Test
    fun `OTHER 无引导文案`() {
        assertNull(RomCompat.guideText(RomKind.OTHER))
    }
}
