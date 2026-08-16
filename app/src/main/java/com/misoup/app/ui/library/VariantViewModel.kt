package com.misoup.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.ai.AiError
import com.misoup.app.ai.variants.Variant
import com.misoup.app.ai.variants.VariantFileName
import com.misoup.app.ai.variants.VariantGenerator
import com.misoup.app.ai.variants.VariantMarkdown
import com.misoup.app.data.store.StoreService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 变式出题 ViewModel（模块 07 T03）：
 * 详情页「变式」弹窗（数量 1-3）→ 生成 → 保存 `变式/yyyyMMdd_HHmmss_变式N.md`
 * → 列表展示（答案可展开）。
 *
 * 失败语义（T07）：未配置 → notReady（引导，不报错）；限流/断网 → error = 「AI 服务不可用」。
 */
class VariantViewModel(
    private val id: String,
    private val generator: VariantGenerator,
    private val store: StoreService,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {

    /** 已保存变式（列表项：文件名 + 解析出的内容） */
    data class SavedVariant(
        val fileName: String,
        val variant: Variant
    )

    data class VariantUiState(
        val dialogVisible: Boolean = false,
        val count: Int = 2,
        val generating: Boolean = false,
        val saved: List<SavedVariant> = emptyList(),
        /** 展开答案的文件名集合 */
        val expanded: Set<String> = emptySet(),
        /** 生成失败提示（限流/断网 → 「AI 服务不可用」） */
        val error: String? = null,
        /** 无 Key → 引导（不当作错误） */
        val notReady: Boolean = false,
        val loaded: Boolean = false
    )

    private val _state = MutableStateFlow(VariantUiState())
    val state: StateFlow<VariantUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    // ---------- 弹窗 ----------

    fun openDialog() {
        _state.update { it.copy(dialogVisible = true, error = null, notReady = false) }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialogVisible = false, generating = false) }
    }

    /** 数量 1-3（越界忽略，保持当前值） */
    fun setCount(n: Int) {
        if (n !in 1..3) return
        _state.update { it.copy(count = n) }
    }

    // ---------- 生成 + 保存 ----------

    fun generateAndSave() {
        val s = _state.value
        if (s.generating) return
        if (!generator.isReady()) {
            _state.update { it.copy(notReady = true) }
            return
        }
        _state.update { it.copy(generating = true, error = null, notReady = false) }
        viewModelScope.launch {
            generator.generateVariants(id, s.count)
                .onSuccess { variants -> saveVariants(variants) }
                .onFailure { e ->
                    _state.update {
                        it.copy(generating = false, error = AiError.messageOf(e))
                    }
                }
        }
    }

    private fun saveVariants(variants: List<Variant>) {
        viewModelScope.launch {
            val now = nowMillis()
            val files = variants.mapIndexed { i, v ->
                VariantFileName.generate(now, i + 1) to VariantMarkdown.render(v)
            }.toMap()
            val saved = store.saveVariantFiles(id, files)
            if (saved.isFailure) {
                _state.update {
                    it.copy(generating = false, error = AiError.UNAVAILABLE_MESSAGE)
                }
                return@launch
            }
            refresh()
            _state.update {
                it.copy(generating = false, dialogVisible = false, error = null)
            }
        }
    }

    // ---------- 列表（可展开答案） ----------

    /** 重新加载已保存变式（进页面 / 生成成功后调用） */
    fun refresh() {
        viewModelScope.launch {
            val names = store.listVariantFiles(id).getOrDefault(emptyList())
            val saved = names.mapNotNull { name ->
                store.readVariantFile(id, name).getOrNull()
                    ?.let { VariantMarkdown.parse(it) }
                    ?.let { SavedVariant(name, it) }
            }
            _state.update { it.copy(saved = saved, loaded = true) }
        }
    }

    fun toggleExpand(fileName: String) {
        _state.update {
            val expanded = it.expanded.toMutableSet()
            if (!expanded.add(fileName)) expanded.remove(fileName)
            it.copy(expanded = expanded)
        }
    }
}
