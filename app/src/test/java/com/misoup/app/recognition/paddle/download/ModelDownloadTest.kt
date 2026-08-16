package com.misoup.app.recognition.paddle.download

import com.misoup.app.recognition.paddle.model.ModelVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T02 镜像地址约定/版本清单解析/下载策略（纯逻辑） */
class ModelDownloadTest {

    // ---------- ModelMirror ----------

    @Test
    fun `清单解析-升序去重-非法行忽略`() {
        val text = """
            # 版本清单
            1.0.0
            1.2.0
            v2.0
            1.0.0
            1.10.0
        """.trimIndent()
        val versions = ModelMirror.parseManifest(text)
        assertEquals(
            listOf(ModelVersion(1, 0, 0), ModelVersion(1, 2, 0), ModelVersion(1, 10, 0)),
            versions
        )
        assertEquals(ModelVersion(1, 10, 0), ModelMirror.latestVersion(text))
    }

    @Test
    fun `清单为空-最新版本为null`() {
        assertNull(ModelMirror.latestVersion(""))
        assertNull(ModelMirror.latestVersion("垃圾行\n乱写"))
    }

    @Test
    fun `URL约定-文件名即版本`() {
        assertEquals(
            "https://m/models/ppocr-v4-mobile/versions.txt",
            ModelMirror.manifestUrl("https://m/models")
        )
        assertEquals(
            "https://m/models/ppocr-v4-mobile/1.2.0/det.1.2.0.onnx",
            ModelMirror.urlFor("det", "1.2.0", "https://m/models")
        )
        assertEquals(
            "https://m/models/ppocr-v4-mobile/1.2.0/ppocr_keys_v1.txt",
            ModelMirror.dictUrl("1.2.0", "https://m/models")
        )
    }

    // ---------- DownloadPolicy ----------

    @Test
    fun `缺失或损坏-需要下载`() {
        assertTrue(DownloadPolicy.shouldDownload(listOf("det"), emptyList()))
        assertTrue(DownloadPolicy.shouldDownload(emptyList(), listOf("rec")))
        assertTrue(DownloadPolicy.shouldDownload(listOf("det"), listOf("cls")))
        assertFalse(DownloadPolicy.shouldDownload(emptyList(), emptyList()))
    }

    @Test
    fun `版本落后-需要更新-相等或超前不更新`() {
        assertTrue(DownloadPolicy.shouldUpdate(ModelVersion(1, 0, 0), ModelVersion(1, 2, 0)))
        assertFalse(DownloadPolicy.shouldUpdate(ModelVersion(1, 2, 0), ModelVersion(1, 2, 0)))
        assertFalse(DownloadPolicy.shouldUpdate(ModelVersion(2, 0, 0), ModelVersion(1, 0, 0)))
        assertFalse(DownloadPolicy.shouldUpdate(null, ModelVersion(1, 0, 0)))
        assertFalse(DownloadPolicy.shouldUpdate(ModelVersion(1, 0, 0), null))
    }

    @Test
    fun `重试退避-指数增长-封顶60秒`() {
        assertEquals(1_000L, DownloadPolicy.retryDelayMs(1))
        assertEquals(2_000L, DownloadPolicy.retryDelayMs(2))
        assertEquals(4_000L, DownloadPolicy.retryDelayMs(3))
        assertEquals(8_000L, DownloadPolicy.retryDelayMs(4))
        assertEquals(60_000L, DownloadPolicy.retryDelayMs(10))    // 2^9=512s → 封顶
        assertEquals(60_000L, DownloadPolicy.retryDelayMs(100))
    }
}
