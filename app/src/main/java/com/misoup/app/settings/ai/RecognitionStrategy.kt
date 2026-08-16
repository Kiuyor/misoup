package com.misoup.app.settings.ai

/**
 * 识别策略三档（模块 10 T02，纯逻辑）
 *
 * 文档裁决（module/10 与 module/05 用词一致，覆盖 DETAILED-DESIGN §123 的旧三档命名）：
 * - 视觉优先（默认）：视觉 LLM 优先，失败自动降级离线 OCR
 * - 离线优先（隐私模式）：离线 OCR 优先，不发图到云端
 * - 仅离线：完全离线，不使用任何云端识别
 *
 * 持久化走 [StrategySettingsStore]（生产 = SharedPreferences 明文，非敏感配置）。
 */
enum class RecognitionStrategy(
    val id: String,
    val displayName: String,
    val description: String,
) {
    VISION_FIRST(
        id = "vision_first",
        displayName = "视觉优先",
        description = "默认：视觉 LLM 优先，失败自动降级离线 OCR（题目会发送到自配的第三方 API）",
    ),
    OFFLINE_FIRST(
        id = "offline_first",
        displayName = "离线优先（隐私模式）",
        description = "离线 OCR 优先：识别更快、不发图到云端",
    ),
    OFFLINE_ONLY(
        id = "offline_only",
        displayName = "仅离线",
        description = "完全离线：不使用任何云端识别",
    );

    companion object {
        /** 默认策略：视觉优先（module/05「默认视觉优先，离线兜底」） */
        val DEFAULT: RecognitionStrategy = VISION_FIRST

        /** 按持久化 id 反查；未知 id → null（调用方回退默认） */
        fun byId(id: String?): RecognitionStrategy? = entries.firstOrNull { it.id == id }
    }
}

/** 识别策略持久化抽象（生产 = SharedPreferences；测试 = 内存实现） */
interface StrategySettingsStore {
    /** 读取当前策略；无记录 → [RecognitionStrategy.DEFAULT] */
    fun load(): RecognitionStrategy

    /** 保存策略 */
    fun save(strategy: RecognitionStrategy)
}
