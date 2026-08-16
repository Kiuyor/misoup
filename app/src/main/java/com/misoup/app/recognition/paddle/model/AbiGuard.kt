package com.misoup.app.recognition.paddle.model

/**
 * ABI 防护（T08，纯逻辑）
 *
 * onnxruntime-android AAR 自带 arm64-v8a / armeabi-v7a / x86 / x86_64 四套 so，
 * 其中 x86_64 保证模拟器可跑（验收标准：模拟器识别可跑、无 ABI 崩溃）。
 * [abiHealth] 仅做告知性检查：设备 ABI 完全不在支持列表 → 明确提示；
 * 其余情况（含 x86_64 模拟器）视为可运行。
 */
object AbiGuard {

    const val ARM64_V8A = "arm64-v8a"
    const val ARMEABI_V7A = "armeabi-v7a"
    const val X86 = "x86"
    const val X86_64 = "x86_64"

    /** ONNX Runtime Android 支持的 ABI */
    val SUPPORTED_ABIS = listOf(ARM64_V8A, ARMEABI_V7A, X86, X86_64)

    data class AbiHealth(
        val supported: Boolean,
        val deviceAbis: List<String>,
        val message: String?,
    )

    /**
     * @param deviceAbis Build.SUPPORTED_ABIS（JVM 测试注入）
     * @return supported=false 仅当设备 ABI 与支持列表零交集（理论上不会发生，
     *         所有 Android 设备至少有一个通用 ABI）；message 为中文提示。
     */
    fun abiHealth(deviceAbis: List<String>): AbiHealth {
        val hit = deviceAbis.any { it in SUPPORTED_ABIS }
        val message = if (hit) {
            null
        } else {
            "当前设备 ABI（${deviceAbis.joinToString("/")}）不在 ONNX Runtime 支持列表" +
                "（${SUPPORTED_ABIS.joinToString("/")}），离线 OCR 不可用。建议换用 arm64 真机或 x86_64 模拟器。"
        }
        return AbiHealth(supported = hit, deviceAbis = deviceAbis, message = message)
    }

    /** x86_64 模拟器判定（用于「模拟器可跑」验收口径） */
    fun isX86_64Emulator(deviceAbis: List<String>): Boolean = X86_64 in deviceAbis
}
