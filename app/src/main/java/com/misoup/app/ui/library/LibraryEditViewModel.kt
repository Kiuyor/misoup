package com.misoup.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 编辑页 ViewModel（T07/T09）
 *
 * 三栏文本（题干/答案/解析）+ 表单（科目/题型/错因/标签/知识点/来源/备注）。
 * 保存 = StoreService.update（meta）+ StoreService.updateText（文本.md + 倒排）。
 * 重命名（T09）= StoreService.rename（id 不变，仅目录名变化）。
 */
class LibraryEditViewModel(
    private val index: LibraryIndex,
    private val store: StoreService?
) : ViewModel() {

    data class EditState(
        val id: String = "",
        val loaded: Boolean = false,
        val question: String = "",
        val answer: String = "",
        val explanation: String = "",
        val subject: String = "",
        /** 进入编辑页时的磁盘科目（save 时与 [subject] 比较，变化则走 move 搬目录，F 修复） */
        val originalSubject: String = "",
        val questionType: String = "",
        val errorReasons: Set<String> = emptySet(),
        val tags: List<String> = emptyList(),
        val knowledgePoints: List<String> = emptyList(),
        val source: String = "",
        val note: String = "",
        /** 当前题干对应的新目录名预览（T09） */
        val renamePreview: String? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    /** 可快速点选的现有标签（出现频次降序，去重后并入标签输入） */
    val existingTags: List<String> get() = index.all().flatMap { it.tags }.distinct()

    fun load(id: String) {
        _state.update { EditState(id = id) }
        viewModelScope.launch {
            val entry = index.get(id)
            val meta = store?.readMetaById(id)?.getOrNull()
            val raw = store?.readTextById(id)?.getOrNull().orEmpty()
            val text = LibraryTextFormat.parse(raw)
            _state.update {
                it.copy(
                    loaded = true,
                    question = text.question,
                    answer = text.answer,
                    explanation = text.explanation,
                    subject = meta?.subject ?: entry?.subject ?: "",
                    originalSubject = meta?.subject ?: entry?.subject ?: "",
                    questionType = meta?.questionType ?: entry?.questionType ?: "",
                    errorReasons = meta?.errorReasons?.toSet() ?: emptySet(),
                    tags = meta?.tags ?: emptyList(),
                    knowledgePoints = meta?.knowledgePoints ?: emptyList(),
                    source = meta?.source ?: "",
                    note = meta?.note ?: "",
                    renamePreview = RenameLogic.computeDirName(System.currentTimeMillis(), text.question)
                )
            }
        }
    }

    // ---------- 字段编辑 ----------

    fun onQuestionChange(value: String) {
        _state.update {
            it.copy(
                question = value,
                renamePreview = RenameLogic.computeDirName(System.currentTimeMillis(), value)
            )
        }
    }

    fun onAnswerChange(value: String) = _state.update { it.copy(answer = value) }

    fun onExplanationChange(value: String) = _state.update { it.copy(explanation = value) }

    fun onSubjectChange(value: String) = _state.update { it.copy(subject = value) }

    fun onQuestionTypeChange(value: String) = _state.update { it.copy(questionType = value) }

    fun toggleErrorReason(value: String) {
        _state.update {
            it.copy(errorReasons = if (value in it.errorReasons) it.errorReasons - value else it.errorReasons + value)
        }
    }

    /** 标签输入：逗号/顿号分隔文本 ↔ List */
    fun onTagsTextChange(value: String) = _state.update { it.copy(tags = splitList(value)) }

    fun tagsText(): String = _state.value.tags.joinToString("，")

    fun onKnowledgeTextChange(value: String) = _state.update { it.copy(knowledgePoints = splitList(value)) }

    fun knowledgeText(): String = _state.value.knowledgePoints.joinToString("，")

    fun onSourceChange(value: String) = _state.update { it.copy(source = value) }

    fun onNoteChange(value: String) = _state.update { it.copy(note = value) }

    fun addTag(tag: String) {
        if (tag.isBlank() || tag in _state.value.tags) return
        _state.update { it.copy(tags = it.tags + tag) }
    }

    // ---------- 保存（T07） ----------

    fun save() {
        if (_state.value.saving) return
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(saving = true, message = null) }
            val now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            // F 修复：科目变更必须走 store.move（搬目录 + meta.subject 同步），
            // 否则索引 subject 与磁盘目录解耦 → 同步层定位不到条目 → 远端备份被删除。
            // 科目未变 → 常规 update。
            val subjectChanged = s.originalSubject.isNotBlank() && s.subject != s.originalSubject
            val metaResult: Result<Unit> = if (subjectChanged) {
                val moved = store?.move(s.id, s.subject)
                    ?: Result.failure(IllegalStateException("存储未初始化"))
                // move 已更新 meta.subject，其余字段再 update 一次补齐
                when {
                    moved.isSuccess -> store?.update(s.id) { meta ->
                        meta.copy(
                            questionType = s.questionType,
                            errorReasons = s.errorReasons.toList(),
                            tags = s.tags,
                            knowledgePoints = s.knowledgePoints,
                            source = s.source,
                            note = s.note,
                            updatedAt = now
                        )
                    } ?: Result.failure(IllegalStateException("存储未初始化"))
                    else -> moved
                }
            } else {
                store?.update(s.id) { meta ->
                    meta.copy(
                        subject = s.subject,
                        questionType = s.questionType,
                        errorReasons = s.errorReasons.toList(),
                        tags = s.tags,
                        knowledgePoints = s.knowledgePoints,
                        source = s.source,
                        note = s.note,
                        updatedAt = now
                    )
                } ?: Result.failure(IllegalStateException("存储未初始化"))
            }
            val textResult = store?.updateText(
                s.id,
                LibraryTextFormat.toMarkdown(QuestionText(s.question, s.answer, s.explanation))
            ) ?: Result.failure(IllegalStateException("存储未初始化"))

            val success = metaResult.isSuccess && textResult.isSuccess
            _state.update {
                it.copy(
                    saving = false,
                    saved = success,
                    message = when {
                        success -> "已保存" + if (subjectChanged) "（科目已移动）" else ""
                        else -> "保存失败：${(metaResult.exceptionOrNull() ?: textResult.exceptionOrNull())?.message}"
                    }
                )
            }
        }
    }

    // ---------- 重命名目录（T09） ----------

    /** 按当前题干自动生成目录名并重命名；id 不变 */
    fun renameDir() {
        val s = _state.value
        val newName = RenameLogic.computeDirName(System.currentTimeMillis(), s.question)
        // FINDING 8：目录名必须合法（不含路径分隔符），非法则明确提示不执行
        if (!RenameLogic.isSafeName(newName)) {
            _state.update { it.copy(message = "重命名失败：目录名含非法字符（/ 或 \\）") }
            return
        }
        val entry = index.get(s.id)
        if (entry == null) {
            _state.update { it.copy(message = "条目不存在，无法重命名") }
            return
        }
        viewModelScope.launch {
            val result = store?.rename(s.id, newName)
                ?: Result.failure(IllegalStateException("存储未初始化"))
            _state.update {
                it.copy(
                    message = if (result.isSuccess) {
                        if (newName == entry.dirName) "目录名未变化" else "目录已重命名为「$newName」（题目 ID 不变）"
                    } else {
                        "重命名失败：${result.exceptionOrNull()?.message}"
                    }
                )
            }
        }
    }

    /** 保存成功后由 UI 回调返回列表 */
    fun onSavedHandled() = _state.update { it.copy(saved = false) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** 逗号/顿号/分号分隔 → 去空白去重列表 */
        fun splitList(text: String): List<String> =
            text.split(',', '，', ';', '；', '、')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
    }
}
