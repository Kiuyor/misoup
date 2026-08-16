package com.misoup.app.settings.model

import com.misoup.app.recognition.paddle.model.ModelBundle
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR 模型状态文案（模块 10 T03） */
class ModelStatusFormatterTest {

    private fun bundle(
        valid: Boolean,
        version: String? = null,
        missing: List<String> = emptyList(),
        corrupt: List<String> = emptyList(),
    ) = ModelBundle(
        det = null, rec = null, cls = null, dict = null,
        version = version, missing = missing, corrupt = corrupt
    ).let {
        // valid 由字段推导；测试构造时直接覆盖
        if (valid) {
            it.copy(version = version ?: "1.0.0", missing = emptyList(), corrupt = emptyList())
        } else {
            it
        }
    }

    @Test
    fun `正常状态 - 含版本号`() {
        val b = bundle(valid = true, version = "1.2.0")
        assertTrue(ModelStatusFormatter.isHealthy(b))
        val text = ModelStatusFormatter.describe(b)
        assertTrue(text.contains("正常"))
        assertTrue(text.contains("1.2.0"))
    }

    @Test
    fun `缺失状态 - 列出缺失角色`() {
        val b = bundle(valid = false, missing = listOf("det", "rec"))
        assertFalse(ModelStatusFormatter.isHealthy(b))
        val text = ModelStatusFormatter.describe(b)
        assertTrue(text.contains("缺失"))
        assertTrue(text.contains("det/rec"))
        assertTrue(text.contains("下载"))
    }

    @Test
    fun `损坏状态 - 列出损坏角色`() {
        val b = bundle(valid = false, corrupt = listOf("cls", "ppocr_keys_v1.txt"))
        val text = ModelStatusFormatter.describe(b)
        assertTrue(text.contains("损坏"))
        assertTrue(text.contains("cls"))
    }

    @Test
    fun `缺失与损坏并存 - 都列出`() {
        val b = bundle(valid = false, missing = listOf("det"), corrupt = listOf("rec"))
        val text = ModelStatusFormatter.describe(b)
        assertTrue(text.contains("缺失（det）"))
        assertTrue(text.contains("损坏（rec）"))
    }

    @Test
    fun `自换模型说明 - 含文件名约定`() {
        assertTrue(ModelStatusFormatter.SWAP_MODEL_GUIDE.contains("det."))
        assertTrue(ModelStatusFormatter.SWAP_MODEL_GUIDE.contains("文件名即版本约定"))
        assertTrue(ModelStatusFormatter.SWAP_MODEL_GUIDE.contains("files/models"))
    }
}
