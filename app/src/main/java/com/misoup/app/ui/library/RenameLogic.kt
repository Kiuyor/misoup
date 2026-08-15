package com.misoup.app.ui.library

import com.misoup.app.data.store.NameGenerator

/**
 * 重命名逻辑（T09）：编辑题干后刷新目录名，id 保持不变。
 *
 * 设计修正（module 02 文档）：meta.id 用 UUID 不可变，目录名 = 展示名可改。
 * 重命名只改目录名（StoreService.rename），meta.id 不变 → 复习记录/引用稳定。
 */
data class RenameResult(
    val id: String,        // 不变
    val oldDirName: String,
    val newDirName: String
)

object RenameLogic {

    /** 路径分隔符（Windows/Unix 都要防） */
    private val PATH_SEPARATORS = setOf('/', '\\')

    /** 目录名合法性校验：非空、不含路径分隔符、不以 "." 开头（隐藏/临时目录） */
    fun isSafeName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith(".")) return false
        if (name.any { it in PATH_SEPARATORS }) return false
        return true
    }

    /**
     * 由题干文本生成新目录名（自动命名规则 yyyyMMdd_HHmmss_<前12字>）。
     * 空/纯公式文本 → 仅时间戳（NameGenerator 已处理）。
     */
    fun computeDirName(timestamp: Long, questionText: String): String =
        NameGenerator.generate(timestamp, questionText)

    /**
     * 组装重命名结果：id 原样保留，仅目录名变化。
     * 新旧目录名相同时返回 null（无需重命名）。
     */
    fun rename(id: String, oldDirName: String, newDirName: String): RenameResult? {
        if (!isSafeName(newDirName)) return null
        if (oldDirName == newDirName) return null
        return RenameResult(id = id, oldDirName = oldDirName, newDirName = newDirName)
    }
}
