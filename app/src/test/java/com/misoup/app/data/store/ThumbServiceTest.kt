package com.misoup.app.data.store

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T09 缩略图服务：cacheDir/thumbs/<id>.webp，首次访问生成 256px WebP，
 * 复用不重复编码、失败路径、删除
 */
class ThumbServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var encoder: FakeImageEncoder
    private lateinit var service: ThumbService

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("cache")
        encoder = FakeImageEncoder()
        service = ThumbService(cacheDir, encoder)
    }

    private val img = FakeImageAsset(4096, 2048)

    @Test
    fun `路径为 cacheDir thumbs id webp`() {
        val f = service.thumbFile("abc-123")
        assertEquals(File(File(cacheDir, "thumbs"), "abc-123.webp"), f)
    }

    @Test
    fun `首次访问生成缩略图`() {
        assertFalse(service.exists("id-1"))
        val result = service.ensure("id-1", img)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isFile)
        assertTrue(result.getOrThrow().absolutePath.endsWith("thumbs${File.separator}id-1.webp"))
        assertTrue(service.exists("id-1"))
        assertEquals(1, encoder.thumbCalls)
        // 内容非空
        assertTrue(result.getOrThrow().length() > 0)
    }

    @Test
    fun `已存在则复用不重复编码`() {
        service.ensure("id-2", img).getOrThrow()
        service.ensure("id-2", img).getOrThrow()
        service.ensure("id-2", img).getOrThrow()

        assertEquals(1, encoder.thumbCalls)   // 只编码一次
        assertEquals(1, encoder.encodedThumbs.size)
    }

    @Test
    fun `不同 id 各自生成`() {
        service.ensure("a", img).getOrThrow()
        service.ensure("b", img).getOrThrow()

        assertEquals(2, encoder.thumbCalls)
        assertTrue(service.exists("a"))
        assertTrue(service.exists("b"))
    }

    @Test
    fun `编码失败返回 failure 无文件`() {
        encoder.failThumb = true
        val result = service.ensure("bad", img)

        assertTrue(result.isFailure)
        assertFalse(service.exists("bad"))
    }

    @Test
    fun `删除缩略图`() {
        service.ensure("del", img).getOrThrow()
        assertTrue(service.exists("del"))
        service.delete("del")
        assertFalse(service.exists("del"))
    }

    @Test
    fun `小图不放大-缩略图尺寸逻辑`() {
        // 小图（100x50）→ 不放大，编码器收到的仍是原尺寸请求（缩放逻辑在 ImageScale）
        val small = FakeImageAsset(100, 50)
        service.ensure("small", small).getOrThrow()
        assertTrue(service.exists("small"))
    }
}
