package com.misoup.app.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** DavConfig 配置校验（模块 09 T01/T04）：完整性 + HTTPS 强制 */
class DavConfigTest {

    @Test
    fun `未配置任何项 - 校验失败`() {
        val r = DavConfig().validate()
        assertTrue(r.isFailure)
    }

    @Test
    fun `缺密码 - 校验失败`() {
        val r = DavConfig(url = "https://dav.jianguoyun.com/dav/", username = "u", password = "").validate()
        assertTrue(r.isFailure)
    }

    @Test
    fun `缺用户名 - 校验失败`() {
        val r = DavConfig(url = "https://dav.jianguoyun.com/dav/", username = "", password = "p").validate()
        assertTrue(r.isFailure)
    }

    @Test
    fun `无协议头 - 校验失败且提示协议`() {
        val r = DavConfig(url = "dav.jianguoyun.com/dav/", username = "u", password = "p").validate()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("协议"))
    }

    @Test
    fun `http 地址 - 拒绝且为 InsecureHttpException`() {
        val r = DavConfig(url = "http://192.168.1.10:8080/dav/", username = "u", password = "p").validate()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is InsecureHttpException)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("HTTPS"))
    }

    @Test
    fun `https 地址 - 校验通过`() {
        val r = DavConfig(url = "https://dav.jianguoyun.com/dav/", username = "u", password = "p").validate()
        assertTrue(r.isSuccess)
    }

    @Test
    fun `isHttps 大小写不敏感且忽略首尾空白`() {
        assertTrue(DavConfig(url = "  HTTPS://dav.example.com/ ", username = "u", password = "p").isHttps)
        assertFalse(DavConfig(url = "http://dav.example.com/", username = "u", password = "p").isHttps)
    }

    @Test
    fun `syncRoot 拼接 misoup-library 且去尾斜杠`() {
        assertEquals(
            "https://dav.example.com/dav/misoup-library",
            DavConfig(url = "https://dav.example.com/dav/", username = "u", password = "p").syncRoot()
        )
        assertEquals(
            "https://dav.example.com/misoup-library",
            DavConfig(url = "https://dav.example.com", username = "u", password = "p").syncRoot()
        )
    }

    @Test
    fun `isConfigured 需三项齐全`() {
        assertTrue(DavConfig(url = "https://a/", username = "u", password = "p").isConfigured)
        assertFalse(DavConfig(url = "https://a/", username = "u", password = "").isConfigured)
        assertFalse(DavConfig().isConfigured)
    }

    @Test
    fun `空配置校验失败消息不含协议提示 - 防误导`() {
        val msg = DavConfig(url = "", username = "u", password = "p").validate().exceptionOrNull()!!.message!!
        assertTrue(msg.contains("未配置完整"))
        try {
            DavConfig(url = "http://a/", username = "u", password = "p").validate().getOrThrow()
            fail("http 不应通过")
        } catch (e: InsecureHttpException) {
            // 预期
        }
    }
}
