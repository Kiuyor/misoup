package com.misoup.app.recognition.visionllm

/**
 * 视觉识别 Prompt（模块 05 T06 定稿，勿随意改）
 *
 * 文本与 module/05「Prompt（定稿，勿随意改）」逐字一致：
 * - 一张图 → 结构化 JSON（题干/答案/解析，公式转 $LaTeX$）
 * - 只输出 JSON；看不清用 [图] 占位；缺答案/解析填空字符串
 */
object VisionPrompt {

    val PROMPT: String =
        "你是错题录入助手。识别图片中的题目并只输出 JSON：\n" +
            "{\"question\":\"题干，公式用 \$LaTeX\$ 包裹\",\"answer\":\"答案\",\"explanation\":\"解析\"}\n" +
            "规则：中文原文保留；公式/数学符号必须转 \$LaTeX\$；看不清的文字用[图]占位；答案或解析不存在时填空字符串。"
}
