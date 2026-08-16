package com.misoup.app.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存版凭据存储（JVM 单测用） */
class FakeDavCredentialStore : DavCredentialStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String): Boolean {
        map[key] = value
        return true
    }

    override fun remove(key: String): Boolean {
        map.remove(key)
        return true
    }

    override fun has(key: String): Boolean = map.containsKey(key)
}

/** DavCredentialRepository：读写加密存储 + 校验拦截（模块 09 T01） */
class DavCredentialRepositoryTest {

    private fun repo() = DavCredentialRepository(FakeDavCredentialStore())

    @Test
    fun `初始未配置 - load 返回空配置`() {
        val r = repo()
        assertFalse(r.isConfigured)
        val cfg = r.load()
        assertEquals("", cfg.url)
        assertEquals("", cfg.username)
        assertEquals("", cfg.password)
    }

    @Test
    fun `保存合法配置后 load 往返一致`() {
        val r = repo()
        val cfg = DavConfig(url = "https://dav.jianguoyun.com/dav/", username = "user@x.com", password = "secret")
        assertTrue(r.save(cfg).isSuccess)
        assertEquals(cfg, r.load())
        assertTrue(r.isConfigured)
    }

    @Test
    fun `保存时地址首尾空白被修剪`() {
        val r = repo()
        r.save(DavConfig(url = "  https://dav.example.com/dav/  ", username = " u ", password = "p"))
        assertEquals("https://dav.example.com/dav/", r.load().url)
        assertEquals("u", r.load().username)
        // 密码不修剪（可能含空格）
        assertEquals("p", r.load().password)
    }

    @Test
    fun `http 配置拒绝落盘`() {
        val r = repo()
        val result = r.save(DavConfig(url = "http://dav.example.com/dav/", username = "u", password = "p"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InsecureHttpException)
        assertFalse(r.isConfigured)
        assertNull(r.load().url.takeIf { it.isNotBlank() })
    }

    @Test
    fun `不完整配置拒绝落盘`() {
        val r = repo()
        assertTrue(r.save(DavConfig(url = "https://dav.example.com/dav/", username = "u", password = "")).isFailure)
        assertFalse(r.isConfigured)
    }

    @Test
    fun `clear 一键清除三项`() {
        val r = repo()
        r.save(DavConfig(url = "https://dav.example.com/dav/", username = "u", password = "p"))
        r.clear()
        assertFalse(r.isConfigured)
        assertEquals("", r.load().url)
        assertEquals("", r.load().password)
    }
}
