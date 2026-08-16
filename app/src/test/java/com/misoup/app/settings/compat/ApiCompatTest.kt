package com.misoup.app.settings.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** API 26-36 行为差异表（模块 10 T09） */
class ApiCompatTest {

    @Test
    fun `Photo Picker - API 33 起可用`() {
        assertFalse(ApiCompat.photoPickerAvailable(32))
        assertTrue(ApiCompat.photoPickerAvailable(33))
        assertTrue(ApiCompat.photoPickerAvailable(34))
        assertTrue(ApiCompat.photoPickerAvailable(36))
    }

    @Test
    fun `ACTION_OPEN_DOCUMENT 回退 - API 26-32`() {
        assertTrue(ApiCompat.useActionOpenDocument(26))
        assertTrue(ApiCompat.useActionOpenDocument(32))
        assertFalse(ApiCompat.useActionOpenDocument(33))
    }

    @Test
    fun `POST_NOTIFICATIONS 运行时权限 - API 33 起需要`() {
        assertFalse(ApiCompat.needsPostNotificationsPermission(32))
        assertTrue(ApiCompat.needsPostNotificationsPermission(33))
        assertTrue(ApiCompat.needsPostNotificationsPermission(36))
    }

    @Test
    fun `支持范围 - minSdk 26 起`() {
        assertFalse(ApiCompat.supported(25))
        assertTrue(ApiCompat.supported(26))
        assertTrue(ApiCompat.supported(36))
    }

    @Test
    fun `选图通道展示名`() {
        assertEquals("ACTION_OPEN_DOCUMENT", ApiCompat.pickerModeName(26))
        assertEquals("ACTION_OPEN_DOCUMENT", ApiCompat.pickerModeName(32))
        assertEquals("Photo Picker", ApiCompat.pickerModeName(33))
        assertEquals("Photo Picker", ApiCompat.pickerModeName(36))
    }

    @Test
    fun `边界 - 33 为分界点两侧行为互斥`() {
        val api = 33
        assertTrue(ApiCompat.photoPickerAvailable(api))
        assertFalse(ApiCompat.useActionOpenDocument(api))
        assertTrue(ApiCompat.needsPostNotificationsPermission(api))
    }
}
