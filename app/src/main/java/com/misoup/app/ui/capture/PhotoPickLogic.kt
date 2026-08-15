package com.misoup.app.ui.capture

import com.misoup.app.recognition.coordinator.CaptureSource

/**
 * T01 Photo Picker 纯逻辑（可 JVM 单测）
 *
 * 采集语义（模块 01 文档）：单张 = 单题，多张 = 批量。
 * - 单张模式：`PickMultipleVisualMedia(maxItems = 1)`（与批量共用同一契约，免双 launcher）
 * - 批量模式：`PickMultipleVisualMedia(maxItems = 50)`（系统硬上限 50）
 *
 * 选择结果转 [CaptureSource] 列表；用户取消（null/空）→ 空列表，不触发采集。
 */
object PhotoPickLogic {

    /** PickMultipleVisualMedia 系统硬上限（AndroidX 文档：最多 50） */
    const val MAX_SELECT = 50

    /** 根据模式决定选择数量上限：单张=1，批量=50 */
    fun maxItems(single: Boolean): Int = if (single) 1 else MAX_SELECT

    /**
     * 选择结果 → 采集来源列表。
     * @param selected 图片 Uri 字符串列表（由 UI 层从 ActivityResult 提取）；
     *                 null/空 = 用户取消或系统异常 → 空列表
     */
    fun toSources(selected: List<String>?): List<CaptureSource> =
        selected.orEmpty().map { CaptureSource(it) }

    /**
     * 单张模式语义：只取第一张（防御系统在 maxItems=1 下仍返回多张的极端情况）。
     * 取消（null/空）→ null。
     */
    fun toSingleSource(selected: List<String>?): CaptureSource? =
        selected.orEmpty().firstOrNull()?.let { CaptureSource(it) }

    /** 批量模式语义：全部入选（上限 50，超出截断防御） */
    fun toBatchSources(selected: List<String>?): List<CaptureSource> =
        toSources(selected).take(MAX_SELECT)
}
