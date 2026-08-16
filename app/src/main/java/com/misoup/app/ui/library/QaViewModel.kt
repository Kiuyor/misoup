package com.misoup.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.ai.AiError
import com.misoup.app.ai.TextLlmClient
import com.misoup.app.ai.qanda.QaMessage
import com.misoup.app.ai.qanda.QaPrompt
import com.misoup.app.ai.qanda.QaSession
import com.misoup.app.data.store.StoreService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 题目答疑 ViewModel（模块 07 T04/T05）：
 * 详情页「答疑」底部弹层 → 消息流（错题上下文 + 用户提问）→ 文本 LLM 回答。
 *
 * - 会话仅内存（[QaSession]，关闭弹层/换题即清，不持久化）
 * - 快捷诉求（T05）：重讲 / 举例 / 换一种解法 → 预制提问 → Prompt 追加指令
 * - 失败语义（T07）：未配置 → notReady 引导；限流/断网 → error = 「AI 服务不可用」
 */
class QaViewModel(
    private val id: String,
    private val llm: TextLlmClient,
    private val store: StoreService,
) : ViewModel() {

    /** 快捷诉求（文档定稿：重讲 / 举例 / 换一种解法） */
    enum class QuickAction(val label: String, val promptText: String) {
        RETELL("重讲", "请重新讲解一遍"),
        EXAMPLE("举例", "请举一个具体例子"),
        ALT_METHOD("换一种解法", "请换一种解法"),
    }

    data class QaUiState(
        val sheetVisible: Boolean = false,
        val messages: List<QaMessage> = emptyList(),
        /** 错题上下文已加载（打开弹层时读 meta + 文本.md） */
        val contextLoaded: Boolean = false,
        val sending: Boolean = false,
        /** 发送失败提示（限流/断网 → 「AI 服务不可用」） */
        val error: String? = null,
        /** 无 Key → 引导 */
        val notReady: Boolean = false,
    )

    private val _state = MutableStateFlow(QaUiState())
    val state: StateFlow<QaUiState> = _state.asStateFlow()

    private val session = QaSession()
    private var contextText: String = ""

    // ---------- 弹层 ----------

    fun open() {
        if (_state.value.sheetVisible) return
        _state.update { it.copy(sheetVisible = true, error = null, notReady = false) }
        viewModelScope.launch {
            val meta = store.readMetaById(id).getOrNull()
            val text = store.readTextById(id).getOrDefault("")
            contextText = QaPrompt.buildContext(meta?.knowledgePoints.orEmpty(), text)
            _state.update { it.copy(contextLoaded = true) }
        }
    }

    /** 关闭弹层：会话仅内存 → 清空（再次打开重新开始） */
    fun close() {
        session.clear()
        _state.update {
            it.copy(sheetVisible = false, messages = emptyList(), error = null, sending = false)
        }
    }

    // ---------- 对话 ----------

    /** 快捷诉求：重讲 / 举例 / 换一种解法 */
    fun quickAction(action: QuickAction) {
        send(action.promptText)
    }

    fun send(question: String) {
        val s = _state.value
        if (question.isBlank() || s.sending) return
        if (!llm.isReady()) {
            _state.update { it.copy(notReady = true) }
            return
        }
        session.addUser(question)
        // Prompt 历史 = 最近消息（不含刚加入的当前提问）
        val prompt = QaPrompt.build(contextText, question, session.recent().dropLast(1))
        _state.update {
            it.copy(sending = true, error = null, notReady = false, messages = session.all)
        }
        viewModelScope.launch {
            llm.complete(prompt)
                .onSuccess { answer ->
                    session.addAssistant(answer)
                    _state.update { it.copy(sending = false, messages = session.all) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(sending = false, error = AiError.messageOf(e))
                    }
                }
        }
    }
}
