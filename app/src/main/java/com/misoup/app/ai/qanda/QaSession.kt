package com.misoup.app.ai.qanda

/**
 * 答疑会话消息（模块 07 T04/T05）
 */
data class QaMessage(
    val role: Role,
    val text: String
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * 答疑会话（模块 07 T05）：仅内存，不持久化（极客隐私友好）。
 *
 * - [addUser]/[addAssistant] 追加消息；[all] 全量只读快照
 * - [recent] 最近 N 条（Prompt 历史拼接用，控制 token 成本）
 * - 单题单会话：换题重新 open → [clear] 重置
 */
class QaSession(private val maxHistory: Int = QaPrompt.HISTORY_TURNS) {

    private val messages = mutableListOf<QaMessage>()

    /** 全部消息快照（UI 消息流渲染用） */
    val all: List<QaMessage> get() = messages.toList()

    val size: Int get() = messages.size

    fun addUser(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) messages += QaMessage(QaMessage.Role.USER, t)
    }

    fun addAssistant(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) messages += QaMessage(QaMessage.Role.ASSISTANT, t)
    }

    /** 最近 [maxHistory] 条（不含更早消息） */
    fun recent(): List<QaMessage> = messages.takeLast(maxHistory)

    /** 清空会话（关闭答疑/切换题目时） */
    fun clear() {
        messages.clear()
    }
}
