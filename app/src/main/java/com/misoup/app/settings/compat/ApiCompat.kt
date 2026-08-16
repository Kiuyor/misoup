package com.misoup.app.settings.compat

/**
 * API 26-36 行为差异表（模块 10 T09，纯逻辑）
 *
 * 施工时核对的差异点（module/10「兼容性」）：
 * - API 33+ Photo Picker；26-32 用 ACTION_OPEN_DOCUMENT 回退（模块 01 已落地）
 * - API 33+ POST_NOTIFICATIONS 运行时权限（V2 复习提醒用）
 * - minSdk 26 起（26 以下不支持，防御性判定）
 */
object ApiCompat {

    const val MIN_SDK = 26

    /** Photo Picker 可用版本下限（API 33 = Android 13） */
    const val PHOTO_PICKER_API = 33

    /** POST_NOTIFICATIONS 运行时权限版本下限（API 33） */
    const val POST_NOTIFICATIONS_API = 33

    /** API ≥33：系统 Photo Picker 可用 */
    fun photoPickerAvailable(apiLevel: Int): Boolean = apiLevel >= PHOTO_PICKER_API

    /** API <33：回退 ACTION_OPEN_DOCUMENT（模块 01 已落地） */
    fun useActionOpenDocument(apiLevel: Int): Boolean = apiLevel < PHOTO_PICKER_API

    /** API ≥33：需要运行时申请 POST_NOTIFICATIONS */
    fun needsPostNotificationsPermission(apiLevel: Int): Boolean = apiLevel >= POST_NOTIFICATIONS_API

    /** 是否在支持范围内（minSdk 26 起；26 以下返回 false，防御） */
    fun supported(apiLevel: Int): Boolean = apiLevel >= MIN_SDK

    /** 选图通道展示名（设置页/诊断用）：Photo Picker 或 ACTION_OPEN_DOCUMENT */
    fun pickerModeName(apiLevel: Int): String =
        if (photoPickerAvailable(apiLevel)) "Photo Picker" else "ACTION_OPEN_DOCUMENT"
}
