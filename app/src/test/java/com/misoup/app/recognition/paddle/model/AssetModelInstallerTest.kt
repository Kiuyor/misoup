package com.misoup.app.recognition.paddle.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T01 assets 内置模型 → filesDir/models 首次复制（幂等/失败降级） */
class AssetModelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assetsDir(): File = tmp.newFolder("assets-models")

    /** 生产等价的 assets 打开器：相对 models/ 的文件名 → 流 */
    private fun opener(assets: File): (String) -> java.io.InputStream? =
        { name -> File(assets, name).takeIf { it.isFile }?.inputStream() }

    /** 测试用体积阈值（避免写入 1MB+ 真实模型体积的测试文件） */
    private val smallMinBytes = mapOf(
        PaddleModels.FILE_DET to 10L,
        PaddleModels.FILE_REC to 10L,
        PaddleModels.FILE_CLS to 5L,
        PaddleModels.DICT_FILE to 5L,
    )

    private fun writeAssets(assets: File, det: Int = 64, rec: Int = 64, cls: Int = 16, dict: Boolean = true) {
        File(assets, "det.onnx").writeBytes(ByteArray(det) { 1 })
        File(assets, "rec.onnx").writeBytes(ByteArray(rec) { 1 })
        File(assets, "cls.onnx").writeBytes(ByteArray(cls) { 1 })
        if (dict) File(assets, PaddleModels.DICT_FILE).writeText("一\n二\n三\n".repeat(500))
    }

    private fun install(assets: File, modelsDir: File) =
        AssetModelInstaller.install(opener(assets), modelsDir, bundledVersion = "1.0.0", minBytes = smallMinBytes)

    @Test
    fun `首次安装-四件复制到modelsDir-带版本文件名`() {
        val assets = assetsDir()
        writeAssets(assets)
        val modelsDir = tmp.newFolder("models")

        val result = install(assets, modelsDir)

        assertTrue(result.ok)
        assertEquals(4, result.installed.size)
        assertTrue(File(modelsDir, "det.1.0.0.onnx").isFile)
        assertTrue(File(modelsDir, "rec.1.0.0.onnx").isFile)
        assertTrue(File(modelsDir, "cls.1.0.0.onnx").isFile)
        assertTrue(File(modelsDir, PaddleModels.DICT_FILE).isFile)
        // 安装后盘点应可用（配小阈值）
        assertTrue(ModelBundle.resolve(modelsDir, smallMinBytes).valid)
    }

    @Test
    fun `幂等-目标已存在且达标-跳过不覆盖`() {
        val assets = assetsDir()
        writeAssets(assets)
        val modelsDir = tmp.newFolder("models")
        install(assets, modelsDir)

        // 用户自换模型（不同版本）不应被覆盖
        val userModel = File(modelsDir, "det.9.9.9.onnx")
        userModel.writeBytes(ByteArray(64) { 7 })

        val result = install(assets, modelsDir)
        assertTrue(result.ok)
        assertEquals(4, result.skipped.size)      // 全部跳过
        assertTrue(userModel.readBytes().all { it == 7.toByte() })  // 未覆盖
        assertTrue(result.installed.isEmpty())
    }

    @Test
    fun `目标文件损坏-重新复制覆盖`() {
        val assets = assetsDir()
        writeAssets(assets)
        val modelsDir = tmp.newFolder("models")
        install(assets, modelsDir)

        // 破坏 det（体积 < 阈值）
        File(modelsDir, "det.1.0.0.onnx").writeBytes(ByteArray(1))

        val result = install(assets, modelsDir)
        assertEquals(listOf("det.1.0.0.onnx"), result.installed)
        assertTrue(result.ok)
        assertTrue(ModelBundle.resolve(modelsDir, smallMinBytes).valid)
    }

    @Test
    fun `assets缺文件-对应角色记失败-其余照常`() {
        val assets = assetsDir()
        writeAssets(assets)
        File(assets, "cls.onnx").delete()   // assets 里没有 cls
        val modelsDir = tmp.newFolder("models")

        val result = install(assets, modelsDir)
        assertFalse(result.ok)
        assertEquals(listOf("cls.onnx"), result.failed)
        assertEquals(3, result.installed.size)
        assertTrue(File(modelsDir, "det.1.0.0.onnx").isFile)
    }

    @Test
    fun `assets目录为空-全部失败-不抛异常`() {
        val assets = assetsDir()
        val modelsDir = tmp.newFolder("models")
        val result = install(assets, modelsDir)
        assertFalse(result.ok)
        assertEquals(4, result.failed.size)
    }
}
