package com.misoup.app.output.anki

import com.misoup.app.output.ExportItem
import java.io.File

/**
 * Anki apkg 导出（父任务书 T08 / tasks T08）—— ⏸ 排期 V1.5，本版本仅占位。
 *
 * ## apkg 格式（公开规范，V1.5 实现自写，约 300 行）
 * apkg = zip，内含：
 * - `collection.anki2`：SQLite 数据库
 *   - `notes`：id / guid / mid(模型) / mod / usn / tags / flds(字段, \u001F 分隔)
 *   - `cards`：id / nid / did(牌组) / ord / type / queue / due / ...
 *   - `models`：模板 JSON（正面=题干图+文本，背面=答案+解析）
 * - `media`：资源目录（题干图片）
 * - `media` 索引 json：{"<文件名>": <序号>}
 *
 * ## 字段映射（模块 08 定稿）
 * - 正面 = 题干图 + 题干文本（公式 $LaTeX$ 需转 KaTeX 占位，V1.5 评估）
 * - 背面 = 答案 + 解析
 * - deck = 科目（meta.subject）
 *
 * ## 不阻塞原则
 * V1.5 排期靠后；主链路（PDF/Markdown/CSV/分享）不依赖本接口。
 */
object AnkiApkg {

    /** 计划版本 */
    const val PLANNED_VERSION = "V1.5"

    /** apkg 内固定文件名 */
    const val COLLECTION_FILE = "collection.anki2"
    const val MEDIA_DIR = "media"
    const val MEDIA_INDEX_FILE = "media"

    /**
     * 导出 apkg（V1.5 实现）。
     * 当前占位：明确失败并提示排期，不产生半成品文件。
     */
    fun export(items: List<ExportItem>, deck: String, outDir: File): Result<File> =
        Result.failure(
            UnsupportedOperationException(
                "Anki apkg 导出排期 $PLANNED_VERSION，当前版本未实现"
            )
        )
}
