package com.misoup.app.recognition.paddle.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T08 ABI 防护：x86_64 模拟器可跑、不支持的 ABI 明确提示 */
class AbiGuardTest {

    @Test
    fun `arm64真机-支持-无提示`() {
        val h = AbiGuard.abiHealth(listOf(AbiGuard.ARM64_V8A))
        assertTrue(h.supported)
        assertNull(h.message)
    }

    @Test
    fun `x86_64模拟器-支持-可跑`() {
        val h = AbiGuard.abiHealth(listOf(AbiGuard.X86_64))
        assertTrue(h.supported)
        assertTrue(AbiGuard.isX86_64Emulator(listOf(AbiGuard.X86_64)))
        assertFalse(AbiGuard.isX86_64Emulator(listOf(AbiGuard.ARM64_V8A)))
    }

    @Test
    fun `多ABI-任一命中即支持`() {
        assertTrue(AbiGuard.abiHealth(listOf("arm64-v8a", "x86_64")).supported)
        assertTrue(AbiGuard.abiHealth(listOf(AbiGuard.X86)).supported)
        assertTrue(AbiGuard.abiHealth(listOf(AbiGuard.ARMEABI_V7A)).supported)
    }

    @Test
    fun `完全不支持的ABI-明确中文提示`() {
        val h = AbiGuard.abiHealth(listOf("mips64", "unknown-abi"))
        assertFalse(h.supported)
        assertEquals(
            "当前设备 ABI（mips64/unknown-abi）不在 ONNX Runtime 支持列表" +
                "（arm64-v8a/armeabi-v7a/x86/x86_64），离线 OCR 不可用。建议换用 arm64 真机或 x86_64 模拟器。",
            h.message
        )
    }

    @Test
    fun `空ABI列表-不支持`() {
        assertFalse(AbiGuard.abiHealth(emptyList()).supported)
    }
}
