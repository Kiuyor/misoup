package com.misoup.app.recognition.paddle.model

import java.io.File

/**
 * 模型三件套 + 字典的文件名/版本约定（T01）
 *
 * 文档约定「文件名即版本约定」：下载/自换的模型文件名为
 * `det.<版本>.onnx` / `rec.<版本>.onnx` / `cls.<版本>.onnx`；
 * [ModelBundle.resolve] 选取 det/rec/cls 三者共有的最高版本，杜绝
 * 新旧版本混搭（A 版本 det + B 版本 rec 属损坏态，判定缺失）。
 *
 * 字典 `ppocr_keys_v1.txt` 文件名自带版本（v1），固定名不参与版本协商。
 */
object PaddleModels {

    const val MODEL_TAG = "ppocr-v4-mobile"

    /** assets 内置模型的打包版本（模型文件 ⏸ 待人工前置，见 assets/models/README.txt） */
    const val BUNDLED_MODEL_VERSION = "1.0.0"

    const val FILE_DET = "det"
    const val FILE_REC = "rec"
    const val FILE_CLS = "cls"
    const val DICT_FILE = "ppocr_keys_v1.txt"

    /** 模型目录名（filesDir 下） */
    const val MODELS_DIR_NAME = "models"

    /** 角色列表（顺序即安装/校验顺序） */
    val ROLES = listOf(FILE_DET, FILE_REC, FILE_CLS)

    /** 角色 → 安装/校验最小体积（远低于真实体积，仅挡空文件/截断文件） */
    val MIN_BYTES: Map<String, Long> = mapOf(
        FILE_DET to 1L * 1024 * 1024,   // 真实 ≈4MB
        FILE_REC to 1L * 1024 * 1024,   // 真实 ≈9MB
        FILE_CLS to 10L * 1024,         // 真实 ≈0.5MB
        DICT_FILE to 1L * 1024,         // 真实 ≈几十 KB
    )

    /** 带版本文件名：det.1.0.0.onnx */
    fun fileNameFor(role: String, version: String): String = "$role.$version.onnx"

    /** 从文件名解析版本：det.1.0.0.onnx → 1.0.0；不匹配 → null */
    fun versionFromFileName(fileName: String, role: String): String? {
        val prefix = "$role."
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".onnx")) return null
        val version = fileName.removePrefix(prefix).removeSuffix(".onnx")
        return version.takeIf { ModelVersion.isValid(it) }
    }

    /** models 目录（filesDir/models） */
    fun modelsDir(baseDir: File): File = File(baseDir, MODELS_DIR_NAME)
}

/** 语义化版本号（major.minor.patch），纯字符串比较不可靠，显式解析 */
data class ModelVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ModelVersion> {

    override fun compareTo(other: ModelVersion): Int = compareValuesBy(
        this, other, { it.major }, { it.minor }, { it.patch }
    )

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val RE = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** 解析；非法（空/非数字段/段数≠3）→ null */
        fun parse(text: String): ModelVersion? {
            val m = RE.matchEntire(text.trim()) ?: return null
            return ModelVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

        fun isValid(text: String): Boolean = parse(text) != null
    }
}

/**
 * 模型包状态（T08 容错的基础）：对 modelsDir 的一次只读盘点。
 * - [missing]：det/rec/cls 任一角色无任何版本文件（或三件套版本不一致）
 * - [corrupt]：文件存在但体积低于最小阈值（空文件/截断下载）
 * - [version]：三件套共有的最高版本；无 → null
 */
data class ModelBundle(
    val det: File?,
    val rec: File?,
    val cls: File?,
    val dict: File?,
    val version: String?,
    val missing: List<String>,
    val corrupt: List<String>,
) {
    val valid: Boolean get() = missing.isEmpty() && corrupt.isEmpty() && version != null

    companion object {
        /**
         * 盘点模型目录。规则：
         * 1. 每个角色扫描 `role.*.onnx`，收集全部版本
         * 2. 三件套共有的最高版本 = 当前模型版本；某角色缺失/版本不齐 → 判定缺失
         * 3. 存在但体积 < minBytes → 判定损坏
         */
        fun resolve(modelsDir: File, minBytes: Map<String, Long> = PaddleModels.MIN_BYTES): ModelBundle {
            if (!modelsDir.isDirectory) {
                return ModelBundle(null, null, null, null, null, PaddleModels.ROLES.toList(), emptyList())
            }
            val versionsByRole = PaddleModels.ROLES.associateWith { role ->
                modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }
                    .orEmpty()
                    .mapNotNull { f -> PaddleModels.versionFromFileName(f.name, role)?.let { v -> v to f } }
            }

            // 三件套共有版本（取最高）
            val common = versionsByRole.values
                .map { m -> m.map { it.first }.toSet() }
                .reduce { acc, set -> acc.intersect(set) }
                .mapNotNull { ModelVersion.parse(it) }
                .maxOrNull()

            val version = common?.toString()
            val missing = PaddleModels.ROLES
                .filter { versionsByRole[it].orEmpty().isEmpty() }
                .toMutableList()
            val corrupt = mutableListOf<String>()

            if (version != null) {
                // 版本一致：校验选中版本的文件体积
                PaddleModels.ROLES.forEach { role ->
                    val f = versionsByRole.getValue(role).first { it.first == version }.second
                    val min = minBytes[role] ?: 0L
                    if (f.length() < min) corrupt += role
                }
            } else if (missing.isEmpty()) {
                // 各角色都有文件但版本不齐 → 按缺失处理（版本混搭不可用）
                missing += "模型三件套版本不一致，需重新下载（文件名即版本约定）"
            }

            val dict = File(modelsDir, PaddleModels.DICT_FILE)
            if (!dict.isFile) {
                corrupt += PaddleModels.DICT_FILE
            } else if (dict.length() < (minBytes[PaddleModels.DICT_FILE] ?: 0L)) {
                corrupt += PaddleModels.DICT_FILE
            }

            val det = version?.let { v -> versionsByRole.getValue(PaddleModels.FILE_DET).firstOrNull { it.first == v }?.second }
            val rec = version?.let { v -> versionsByRole.getValue(PaddleModels.FILE_REC).firstOrNull { it.first == v }?.second }
            val cls = version?.let { v -> versionsByRole.getValue(PaddleModels.FILE_CLS).firstOrNull { it.first == v }?.second }

            return ModelBundle(det, rec, cls, dict, version, missing, corrupt)
        }
    }
}
