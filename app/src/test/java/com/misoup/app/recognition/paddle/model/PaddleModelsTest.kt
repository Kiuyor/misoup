package com.misoup.app.recognition.paddle.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T01 模型文件/版本约定 + T08 缺失/损坏盘点 */
class PaddleModelsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 版本号解析 ----------

    @Test
    fun `版本解析-合法与非法`() {
        assertEquals(ModelVersion(1, 0, 0), ModelVersion.parse("1.0.0"))
        assertEquals(ModelVersion(1, 20, 3), ModelVersion.parse("1.20.3"))
        assertNull(ModelVersion.parse(""))
        assertNull(ModelVersion.parse("v1.0"))
        assertNull(ModelVersion.parse("1.0"))
        assertNull(ModelVersion.parse("1.0.0.0"))
        assertNull(ModelVersion.parse("a.b.c"))
    }

    @Test
    fun `版本比较-语义序`() {
        assertTrue(ModelVersion(1, 0, 0) < ModelVersion(1, 0, 1))
        assertTrue(ModelVersion(1, 9, 9) < ModelVersion(1, 10, 0))
        assertTrue(ModelVersion(1, 0, 0) < ModelVersion(2, 0, 0))
        assertEquals(0, ModelVersion(2, 3, 4).compareTo(ModelVersion(2, 3, 4)))
    }

    // ---------- 文件名即版本约定 ----------

    @Test
    fun `文件名生成与解析-往返一致`() {
        val name = PaddleModels.fileNameFor(PaddleModels.FILE_DET, "1.0.0")
        assertEquals("det.1.0.0.onnx", name)
        assertEquals("1.0.0", PaddleModels.versionFromFileName(name, PaddleModels.FILE_DET))
        // 角色前缀不匹配 → null
        assertNull(PaddleModels.versionFromFileName("det.1.0.0.onnx", PaddleModels.FILE_REC))
        // 非 onnx / 版本非法 → null
        assertNull(PaddleModels.versionFromFileName("det.1.0.onnx", PaddleModels.FILE_DET))
        assertNull(PaddleModels.versionFromFileName("det.txt", PaddleModels.FILE_DET))
    }

    // ---------- ModelBundle.resolve：缺失 ----------

    @Test
    fun `空目录-三件套全缺失-不可用`() {
        val bundle = ModelBundle.resolve(tmp.newFolder("empty"))
        assertFalse(bundle.valid)
        assertEquals(listOf("det", "rec", "cls"), bundle.missing)
        assertNull(bundle.version)
        assertTrue(bundle.corrupt.contains(PaddleModels.DICT_FILE))  // 字典缺失也算损坏
    }

    @Test
    fun `目录不存在-视为全缺失`() {
        val bundle = ModelBundle.resolve(File(tmp.root, "not-exist"))
        assertFalse(bundle.valid)
        assertEquals(3, bundle.missing.size)
    }

    @Test
    fun `只装 det-其余缺失-不可用`() {
        val dir = tmp.newFolder("partial")
        writeModel(dir, PaddleModels.FILE_DET, "1.0.0", 2 * 1024 * 1024)
        writeDict(dir)
        val bundle = ModelBundle.resolve(dir)
        assertFalse(bundle.valid)
        assertEquals(listOf("rec", "cls"), bundle.missing)
    }

    // ---------- ModelBundle.resolve：完整可用 ----------

    @Test
    fun `三件套齐全-最高共有版本生效-可用`() {
        val dir = tmp.newFolder("complete")
        writeModel(dir, PaddleModels.FILE_DET, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_REC, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_CLS, "1.0.0", 100 * 1024)
        writeDict(dir)
        val bundle = ModelBundle.resolve(dir)
        assertTrue(bundle.valid)
        assertEquals("1.0.0", bundle.version)
        assertTrue(bundle.missing.isEmpty())
        assertTrue(bundle.corrupt.isEmpty())
        assertEquals("det.1.0.0.onnx", bundle.det?.name)
    }

    @Test
    fun `多版本并存-选取三件套共有最高版本`() {
        val dir = tmp.newFolder("multi")
        writeModel(dir, PaddleModels.FILE_DET, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_DET, "2.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_REC, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_REC, "2.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_CLS, "1.0.0", 100 * 1024)
        writeModel(dir, PaddleModels.FILE_CLS, "2.0.0", 100 * 1024)
        writeDict(dir)
        val bundle = ModelBundle.resolve(dir)
        assertEquals("2.0.0", bundle.version)
        assertTrue(bundle.valid)
    }

    @Test
    fun `版本不一致-各角色都有文件但版本不齐-按缺失处理`() {
        val dir = tmp.newFolder("mismatch")
        writeModel(dir, PaddleModels.FILE_DET, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_REC, "2.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_CLS, "1.0.0", 100 * 1024)
        writeDict(dir)
        val bundle = ModelBundle.resolve(dir)
        assertFalse(bundle.valid)   // 新旧版本混搭视为不可用（防错配推理）
        assertNull(bundle.version)
        assertTrue(bundle.missing.isNotEmpty())
    }

    // ---------- ModelBundle.resolve：损坏 ----------

    @Test
    fun `文件体积低于阈值-判损坏`() {
        val dir = tmp.newFolder("corrupt")
        writeModel(dir, PaddleModels.FILE_DET, "1.0.0", 10)            // 空文件
        writeModel(dir, PaddleModels.FILE_REC, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_CLS, "1.0.0", 100 * 1024)
        writeDict(dir)
        val bundle = ModelBundle.resolve(dir)
        assertFalse(bundle.valid)
        assertTrue(bundle.corrupt.contains(PaddleModels.FILE_DET))
    }

    @Test
    fun `字典损坏-判损坏`() {
        val dir = tmp.newFolder("dict-corrupt")
        writeModel(dir, PaddleModels.FILE_DET, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_REC, "1.0.0", 2 * 1024 * 1024)
        writeModel(dir, PaddleModels.FILE_CLS, "1.0.0", 100 * 1024)
        File(dir, PaddleModels.DICT_FILE).writeText("x")   // 体积不足 1KB
        val bundle = ModelBundle.resolve(dir)
        assertFalse(bundle.valid)
        assertTrue(bundle.corrupt.contains(PaddleModels.DICT_FILE))
    }

    // ---------- 工具 ----------

    private fun writeModel(dir: File, role: String, version: String, bytes: Int) {
        val f = File(dir, PaddleModels.fileNameFor(role, version))
        f.writeBytes(ByteArray(bytes) { 1 })
    }

    private fun writeDict(dir: File) {
        File(dir, PaddleModels.DICT_FILE).writeText("一\n二\n三\n".repeat(500))  // >1KB
    }
}
