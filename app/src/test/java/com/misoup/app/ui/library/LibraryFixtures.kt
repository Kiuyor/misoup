package com.misoup.app.ui.library

import com.misoup.app.data.index.IndexedEntry

/** 测试用索引条目构造器 */
fun makeEntry(
    id: String,
    subject: String = "数学",
    dirName: String = id,
    mtime: Long = 0L,
    tags: List<String> = emptyList(),
    errorReasons: List<String> = emptyList(),
    ocrStatus: String = "done",
    needsRepair: Boolean = false,
    summary: String = "",
    thumbPath: String? = null
) = IndexedEntry(
    id = id,
    dirName = dirName,
    subject = subject,
    questionType = "解答题",
    errorReasons = errorReasons,
    tags = tags,
    knowledgePoints = emptyList(),
    createdAt = "",
    updatedAt = "",
    textFile = "文本.md",
    ocrStatus = ocrStatus,
    thumbPath = thumbPath,
    mtime = mtime,
    summary = summary,
    needsRepair = needsRepair
)
