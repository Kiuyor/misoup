package com.misoup.app.recognition.paddle.model

import java.io.File
import java.io.InputStream

/**
 * assets 内置模型 → filesDir/models 首次运行复制（T01）
 *
 * 生产环境 assets 经 AssetManager 读取，无法用 File 直接遍历；
 * 因此本安装器对 assets 侧抽象为「按文件名打开流」的注入点：
 * - 生产：`{ name -> context.assets.open("models/$name") }`
 * - JVM 测试：`{ name -> File(assetsDir, name).inputStream() }`
 *
 * 规则：
 * - 复制 det/rec/cls/字典 四件；assets 内不带版本号 → 按 [bundledVersion] 落地为
 *   `det.<版本>.onnx` 等带版本文件名（文件名即版本约定）
 * - 幂等：目标已存在且体积达标 → 跳过（不覆盖用户自换的模型）
 * - 单文件失败不中断整批（记入 failed），返回实际结果
 */
object AssetModelInstaller {

    data class InstallResult(
        val installed: List<String>,
        val skipped: List<String>,
        val failed: List<String>,
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /**
     * @param openAsset assets 文件打开器（相对 models/ 目录的文件名）
     * @param modelsDir 目标目录（filesDir/models）
     * @param bundledVersion 内置模型版本号
     * @param minBytes 体积阈值（Map 缺省用 PaddleModels.MIN_BYTES）
     */
    fun install(
        openAsset: (String) -> InputStream?,
        modelsDir: File,
        bundledVersion: String = PaddleModels.BUNDLED_MODEL_VERSION,
        minBytes: Map<String, Long> = PaddleModels.MIN_BYTES,
    ): InstallResult {
        modelsDir.mkdirs()
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        PaddleModels.ROLES.forEach { role ->
            val targetName = PaddleModels.fileNameFor(role, bundledVersion)
            val target = File(modelsDir, targetName)
            val min = minBytes[role] ?: 0L
            if (target.isFile && target.length() >= min) {
                skipped += targetName
                return@forEach
            }
            val copied = copyAsset(openAsset, "$role.onnx", target)
            if (copied) installed += targetName else failed += "$role.onnx"
        }

        // 字典（固定名，自带 v1 版本）
        val dictTarget = File(modelsDir, PaddleModels.DICT_FILE)
        val dictMin = minBytes[PaddleModels.DICT_FILE] ?: 0L
        if (dictTarget.isFile && dictTarget.length() >= dictMin) {
            skipped += dictTarget.name
        } else {
            val copied = copyAsset(openAsset, PaddleModels.DICT_FILE, dictTarget)
            if (copied) installed += dictTarget.name else failed += PaddleModels.DICT_FILE
        }

        return InstallResult(installed, skipped, failed)
    }

    private fun copyAsset(openAsset: (String) -> InputStream?, assetName: String, target: File): Boolean =
        try {
            val input = openAsset(assetName) ?: return false
            input.use { ins ->
                target.parentFile?.mkdirs()
                target.outputStream().use { outs -> ins.copyTo(outs) }
            }
            target.length() > 0
        } catch (e: Exception) {
            target.delete()
            false
        }
}
