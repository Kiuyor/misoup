package com.misoup.app.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** T07 图片缩放纯逻辑（原图 2048 封顶 / 缩略图 256 / 非法输入容错） */
class ImageScaleTest {

    @Test
    fun `长边未超限不缩放`() {
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(2048, 1000, 2048), 1e-6f)
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(100, 50, 2048), 1e-6f)
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(1, 1, 2048), 1e-6f)
    }

    @Test
    fun `长边超限按比例缩小`() {
        assertEquals(0.5f, ImageScale.scaleForMaxLongEdge(4096, 2048, 2048), 1e-6f)
        assertEquals(0.25f, ImageScale.scaleForMaxLongEdge(10000, 10000, 2500), 1e-6f)
    }

    @Test
    fun `非法输入返回1不除零`() {
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(0, 100, 2048), 1e-6f)
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(100, 0, 2048), 1e-6f)
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(-5, 100, 2048), 1e-6f)
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(100, 100, 0), 1e-6f)
        assertEquals(1f, ImageScale.scaleForMaxLongEdge(100, 100, -1), 1e-6f)
    }

    @Test
    fun `缩略图尺寸-长边256`() {
        assertEquals(256 to 128, ImageScale.thumbSize(4096, 2048, 256))
        assertEquals(128 to 256, ImageScale.thumbSize(2048, 4096, 256))
        // 小图不放大
        assertEquals(100 to 50, ImageScale.thumbSize(100, 50, 256))
        // 恰好 256 不缩放
        assertEquals(256 to 256, ImageScale.thumbSize(256, 256, 256))
    }

    @Test
    fun `缩放尺寸最小为1`() {
        assertEquals(1 to 1, ImageScale.scaledSize(1, 1, 0.1f))
        assertEquals(1 to 1, ImageScale.scaledSize(0, 0, 1f))
        assertEquals(2 to 1, ImageScale.scaledSize(4096, 2048, 0.0005f))
    }
}
