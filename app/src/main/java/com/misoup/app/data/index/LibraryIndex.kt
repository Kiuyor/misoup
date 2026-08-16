package com.misoup.app.data.index

import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.store.MetaJson
import java.io.File

/** 索引条目（meta 摘要 + 缩略图路径 + mtime；损坏条目带原始 JSON） */
data class IndexedEntry(
    val id: String,
    val dirName: String,          // 目录名（展示名，可重命名；id 不变）
    val subject: String,
    val questionType: String,
    val errorReasons: List<String>,
    val tags: List<String>,
    val knowledgePoints: List<String>,
    val createdAt: String,
    val updatedAt: String,
    val textFile: String,
    val ocrStatus: String,
    val thumbPath: String? = null,
    val mtime: Long = 0L,
    val summary: String = "",     // 题干首行摘要（列表展示用）
    /** 损坏标记：meta.json 解析失败或缺失 → 待修复（T10） */
    val needsRepair: Boolean = false,
    /** 损坏条目的原始 meta.json 内容（"待修复"卡片展示用；缺失时为 null） */
    val repairJson: String? = null
)

/** 查询过滤条件；空列表 = 不过滤该维度 */
data class Filter(
    val subjects: List<String> = emptyList(),
    val questionTypes: List<String> = emptyList(),
    val errorReasons: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** true=仅待修复 false=仅正常 null=全部 */
    val needsRepair: Boolean? = null
)

/**
 * 内存索引（启动全量扫描构建；1 万题量级毫秒级）
 *
 * - entries: id → IndexedEntry
 * - inverted: 分词 token → id 集合（题干文本 OCR 结果，识别完成后填充）
 *
 * 线程模型（F3 修复）：全部公开方法 @Synchronized（对象监视器锁）。
 * 写方来自 StoreService（协程 Mutex 串行）与 DavSyncService（同步直接调用，Main 线程），
 * 读方来自 UI 查询——多线程并发访问 LinkedHashMap/HashMap 必须互斥，
 * 否则数据竞态导致索引条目丢失/倒排错乱/ConcurrentModificationException。
 * 锁粒度毫秒级，1 万题量级无感知。
 */
class LibraryIndex {

    private val entries = LinkedHashMap<String, IndexedEntry>()
    private val inverted = HashMap<String, MutableSet<String>>()
    /** id → 该 id 已入倒排的 token（用于覆盖/删除时清理旧倒排） */
    private val idTokens = HashMap<String, MutableSet<String>>()

    /**
     * 启动全量扫描库根：<root>/<科目>/<条目目录>/meta.json
     * - 解析成功 → 正常条目（并尝试读 文本.md 建倒排）
     * - 解析失败/缺失 → "待修复"条目（保留原始 JSON，不丢文件，不崩溃）
     *
     * @param thumbDir 缩略图目录（cacheDir/thumbs），仅用于探测已生成的缩略图；null 则 thumbPath 为空
     */
    @Synchronized
    fun rebuild(root: File, thumbDir: File? = null) {
        entries.clear()
        inverted.clear()
        if (!root.isDirectory) return

        root.listFiles()?.forEach { subjectDir ->
            if (!subjectDir.isDirectory || subjectDir.name.startsWith(".")) return@forEach
            subjectDir.listFiles()?.forEach { entryDir ->
                if (!entryDir.isDirectory || entryDir.name.startsWith(".")) return@forEach
                scanEntry(entryDir, subjectDir.name, thumbDir)
            }
        }
    }

    /** 扫描单个条目目录 */
    private fun scanEntry(entryDir: File, subject: String, thumbDir: File?) {
        val metaFile = File(entryDir, MisoupConfigConst.META_FILE_NAME)
        val mtime = entryDir.lastModified()
        if (!metaFile.isFile) {
            // 缺 meta.json：按"待修复"收录（数据不丢）
            upsertRepair(entryDir.name, subject, mtime, repairJson = null)
            return
        }
        val raw = runCatching { metaFile.readText() }.getOrNull()
        val parsed = raw?.let { MetaJson.parse(it).getOrNull() }
        if (parsed == null) {
            upsertRepair(entryDir.name, subject, mtime, repairJson = raw)
            return
        }
        // 正常条目
        val thumb = thumbDir?.let { d ->
            val f = File(d, "${parsed.id}.webp")
            if (f.isFile) f.absolutePath else null
        }
        val summary = readSummary(entryDir, parsed.textFile)
        upsert(parsed, thumb, dirName = entryDir.name, mtime = mtime, summary = summary)
        // 题干文本倒排
        val text = runCatching { File(entryDir, parsed.textFile).readText() }.getOrNull()
        if (!text.isNullOrBlank()) indexText(parsed.id, text)
        // 标签倒排（T03：关键词搜索命中标签）
        indexTags(parsed.id, parsed.tags)
    }

    /** 读文本.md 首行作为摘要（列表展示） */
    private fun readSummary(entryDir: File, textFile: String): String {
        val f = File(entryDir, textFile)
        if (!f.isFile) return ""
        return runCatching {
            f.readLines().firstOrNull { it.isNotBlank() }?.take(30) ?: ""
        }.getOrNull() ?: ""
    }

    /** 新增/覆盖正常条目（dirName 为空时沿用旧条目目录名，兜底用 meta.id） */
    @Synchronized
    fun upsert(
        meta: MisoupMeta,
        thumb: String?,
        dirName: String? = null,
        mtime: Long = System.currentTimeMillis(),
        summary: String = ""
    ) {
        val old = entries[meta.id]
        entries[meta.id] = IndexedEntry(
            id = meta.id,
            dirName = dirName ?: old?.dirName ?: meta.id,
            subject = meta.subject,
            questionType = meta.questionType,
            errorReasons = meta.errorReasons,
            tags = meta.tags,
            knowledgePoints = meta.knowledgePoints,
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt,
            textFile = meta.textFile,
            ocrStatus = meta.ocr.status,
            thumbPath = thumb ?: old?.thumbPath,
            mtime = mtime,
            summary = summary.ifEmpty { old?.summary ?: "" },
            needsRepair = false,
            repairJson = null
        )
        // 旧文本倒排失效，等待重新 indexText
        clearTokens(meta.id)
    }

    /** 新增"待修复"条目（损坏容错，T10） */
    @Synchronized
    fun upsertRepair(dirName: String, subject: String, mtime: Long, repairJson: String?) {
        val id = "repair:$subject:$dirName"
        entries[id] = IndexedEntry(
            id = id,
            dirName = dirName,
            subject = subject,
            questionType = "",
            errorReasons = emptyList(),
            tags = emptyList(),
            knowledgePoints = emptyList(),
            createdAt = "",
            updatedAt = "",
            textFile = MisoupConfigConst.TEXT_FILE_NAME,
            ocrStatus = "repair",
            mtime = mtime,
            needsRepair = true,
            repairJson = repairJson
        )
    }

    /** 删除条目（含其倒排） */
    @Synchronized
    fun remove(id: String) {
        entries.remove(id)
        clearTokens(id)
    }

    /** 题干文本分词 → 倒排（识别完成后填充；覆盖式：先清旧 token） */
    @Synchronized
    fun indexText(id: String, text: String) {
        if (!entries.containsKey(id)) return
        clearTokens(id)
        val tokens = TextTokenizer.tokenize(text)
        tokens.forEach { token ->
            inverted.getOrPut(token) { mutableSetOf() }.add(id)
        }
        idTokens[id] = tokens.toMutableSet()
    }

    /**
     * 标签分词 → 倒排（T03 关键词搜索命中标签）。
     * 追加式：与 [indexText] 的题干 token 并存，token 汇总进 idTokens 便于清理。
     */
    @Synchronized
    fun indexTags(id: String, tags: List<String>) {
        if (!entries.containsKey(id)) return
        val tokens = tags.flatMap { TextTokenizer.tokenize(it) }
        tokens.forEach { token ->
            inverted.getOrPut(token) { mutableSetOf() }.add(id)
        }
        idTokens.getOrPut(id) { mutableSetOf() }.addAll(tokens)
    }

    /** 清理某 id 的全部倒排关联（覆盖/删除时保证倒排一致） */
    private fun clearTokens(id: String) {
        idTokens.remove(id)?.forEach { token ->
            inverted[token]?.remove(id)
            if (inverted[token].isNullOrEmpty()) inverted.remove(token)
        }
    }

    /**
     * 查询：Filter 各维度 AND；keyword 分词后每个 token 必须命中倒排（AND 语义）；
     * keyword 为 null/空白时仅按 Filter 过滤。
     * 结果按 mtime 降序（最近更新的在前）。
     */
    @Synchronized
    fun query(filter: Filter, keyword: String?): List<IndexedEntry> {
        var result = entries.values.filter { entry ->
            (filter.subjects.isEmpty() || entry.subject in filter.subjects) &&
                (filter.questionTypes.isEmpty() || entry.questionType in filter.questionTypes) &&
                (filter.errorReasons.isEmpty() || entry.errorReasons.any { it in filter.errorReasons }) &&
                (filter.tags.isEmpty() || entry.tags.any { it in filter.tags }) &&
                (filter.needsRepair == null || entry.needsRepair == filter.needsRepair)
        }
        if (!keyword.isNullOrBlank()) {
            val tokens = TextTokenizer.tokenize(keyword)
            if (tokens.isNotEmpty()) {
                result = result.filter { entry ->
                    tokens.all { token -> inverted[token]?.contains(entry.id) == true }
                }
            }
        }
        return result.sortedByDescending { it.mtime }
    }

    /** 全部条目（含待修复） */
    @Synchronized
    fun all(): List<IndexedEntry> = entries.values.toList()

    @Synchronized
    fun get(id: String): IndexedEntry? = entries[id]

    @Synchronized
    fun size(): Int = entries.size

    /** 刷新条目 mtime（列表按最近编辑排序；文本更新/重命名后调用） */
    @Synchronized
    fun touch(id: String, mtime: Long = System.currentTimeMillis()) {
        val e = entries[id] ?: return
        entries[id] = e.copy(mtime = mtime)
    }

    /** 科目 → 条目数（学情统计/分组用） */
    @Synchronized
    fun countBySubject(): Map<String, Int> = entries.values.groupingBy { it.subject }.eachCount()

    /** 全部倒排 token（调试/测试用） */
    @Synchronized
    fun tokenCount(): Int = inverted.size
}
