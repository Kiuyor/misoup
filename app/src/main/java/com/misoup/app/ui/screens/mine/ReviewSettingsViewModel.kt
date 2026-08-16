package com.misoup.app.ui.screens.mine

import androidx.lifecycle.ViewModel
import com.misoup.app.review.fsrs.Fsrs
import com.misoup.app.settings.review.FsrsParamsCodec
import com.misoup.app.settings.review.FsrsSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 复习设置状态（模块 10 T04）
 *
 * - FSRS 参数高级编辑：17 个 w 值（文本编辑，[FsrsParamsCodec] 校验）+ short-term 开关
 * - 复习提醒：时间 + 系统通知开关（V2 占位：仅本地状态，通知接入待 V2）
 */
data class ReviewSettingsUiState(
    /** w 值编辑文本（当前合法则每项 5 位小数展示） */
    val wText: String = "",
    /** 当前 wText 是否合法（可保存） */
    val wValid: Boolean = true,
    /** wText 校验错误（非法时非空） */
    val wError: String? = null,
    val enableShortTerm: Boolean = true,
    /** 提醒时间（HH:mm；V2 通知接入用） */
    val reminderTime: String = "21:00",
    /** 提醒开关（V2 占位） */
    val reminderEnabled: Boolean = false,
    val message: String? = null,
)

class ReviewSettingsViewModel(
    private val store: FsrsSettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewSettingsUiState())
    val state: StateFlow<ReviewSettingsUiState> = _state.asStateFlow()

    init {
        val params = store.load()
        _state.value = ReviewSettingsUiState(
            wText = FsrsParamsCodec.formatW(params.w),
            wValid = FsrsParamsCodec.validate(params.w).isSuccess,
            enableShortTerm = params.enableShortTerm,
        )
    }

    /** 文本编辑（实时校验，非法给出定位错误但不打断编辑） */
    fun onWTextChanged(text: String) {
        val result = FsrsParamsCodec.parseW(text)
        _state.update {
            it.copy(
                wText = text,
                wValid = result.isSuccess,
                wError = result.exceptionOrNull()?.message,
            )
        }
    }

    /** short-term 开关 */
    fun setEnableShortTerm(enabled: Boolean) {
        _state.update { it.copy(enableShortTerm = enabled) }
    }

    /** 保存：校验通过 → 落盘；失败 → message 提示 */
    fun save() {
        val s = _state.value
        val parsed = FsrsParamsCodec.toParams(s.wText, s.enableShortTerm)
        parsed.onSuccess { params ->
            store.save(params)
            _state.update {
                it.copy(
                    wValid = true,
                    wError = null,
                    message = "FSRS 参数已保存（short-term ${if (params.enableShortTerm) "开" else "关"}）",
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(wValid = false, wError = e.message, message = "保存失败：${e.message}") }
        }
    }

    /** 恢复默认参数（FSRS-4.5 初始 17 参数） */
    fun resetDefaults() {
        val params = Fsrs.Params()
        store.save(params)
        _state.update {
            it.copy(
                wText = FsrsParamsCodec.formatW(params.w),
                wValid = true,
                wError = null,
                enableShortTerm = params.enableShortTerm,
                message = "已恢复默认参数",
            )
        }
    }

    // ---------- V2 占位：复习提醒（仅本地状态，通知接入待 V2） ----------

    fun setReminderTime(time: String) {
        _state.update { it.copy(reminderTime = time) }
    }

    fun setReminderEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                reminderEnabled = enabled,
                message = if (enabled) {
                    "复习提醒为 V2 功能占位：通知接入后生效（API 33+ 需授予通知权限）"
                } else {
                    "复习提醒已关闭"
                },
            )
        }
    }
}
