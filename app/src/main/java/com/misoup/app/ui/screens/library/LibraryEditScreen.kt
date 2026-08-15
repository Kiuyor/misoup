package com.misoup.app.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.AppGraph
import com.misoup.app.data.store.MisoupConfig
import com.misoup.app.ui.library.LibraryEditViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 编辑页（T07/T09）：
 * - 题干/答案/解析 三栏文本（LaTeX 源码可直接改）
 * - 科目/题型/错因/标签/知识点/来源/备注 表单
 * - 保存 = StoreService.update（meta）+ updateText（文本.md + 搜索倒排）
 * - 「重命名目录」= 按当前题干自动生成目录名，id 不变（T09）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryEditScreen(
    id: String,
    onDone: () -> Unit = {},
    vm: LibraryEditViewModel = viewModel(factory = editViewModelFactory())
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(id) { vm.load(id) }

    // 保存成功 → 返回列表（列表页 LaunchedEffect 会重新查询，即时刷新）
    LaunchedEffect(state.saved) {
        if (state.saved) {
            vm.onSavedHandled()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑错题") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Button(
                        onClick = vm::save,
                        enabled = !state.saving && state.loaded,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.saving) "保存中…" else "保存")
                    }
                    // 重命名目录（T09）
                    OutlinedButton(
                        onClick = vm::renameDir,
                        enabled = !state.saving && state.loaded,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重命名目录（ID 不变）")
                    }
                    state.renamePreview?.let { preview ->
                        Text(
                            text = "重命名后将变为：$preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    state.message?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (msg.startsWith("保存失败") || msg.startsWith("重命名失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 三栏文本（T07）
            OutlinedTextField(
                value = state.question,
                onValueChange = vm::onQuestionChange,
                label = { Text("题干（LaTeX 可用 \$...\$）") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.answer,
                onValueChange = vm::onAnswerChange,
                label = { Text("答案") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.explanation,
                onValueChange = vm::onExplanationChange,
                label = { Text("解析") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            // 科目
            FormLabel("科目")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MisoupConfig.presetSubjects.forEach { subject ->
                    FilterChip(
                        selected = state.subject == subject,
                        onClick = { vm.onSubjectChange(subject) },
                        label = { Text(subject) }
                    )
                }
            }

            // 题型
            FormLabel("题型")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MisoupConfig.presetQuestionTypes.forEach { type ->
                    FilterChip(
                        selected = state.questionType == type,
                        onClick = { vm.onQuestionTypeChange(type) },
                        label = { Text(type) }
                    )
                }
            }

            // 错因（多选）
            FormLabel("错因（可多选）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MisoupConfig.presetErrorReasons.forEach { reason ->
                    FilterChip(
                        selected = reason in state.errorReasons,
                        onClick = { vm.toggleErrorReason(reason) },
                        label = { Text(reason) }
                    )
                }
            }

            // 标签
            FormLabel("标签（逗号分隔，可点选已有标签）")
            OutlinedTextField(
                value = state.tags.joinToString("，"),
                onValueChange = vm::onTagsTextChange,
                label = { Text("如：二次函数，月考") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                vm.existingTags.take(8).forEach { tag ->
                    TextButton(onClick = { vm.addTag(tag) }) { Text(tag) }
                }
            }

            // 知识点
            FormLabel("知识点（逗号分隔）")
            OutlinedTextField(
                value = state.knowledgePoints.joinToString("，"),
                onValueChange = vm::onKnowledgeTextChange,
                label = { Text("如：二次函数区间最值") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 来源
            FormLabel("来源")
            OutlinedTextField(
                value = state.source,
                onValueChange = vm::onSourceChange,
                label = { Text("如：2026 年 5 月月考") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 备注
            FormLabel("备注")
            OutlinedTextField(
                value = state.note,
                onValueChange = vm::onNoteChange,
                label = { Text("补充说明") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 编辑页默认工厂 */
@Composable
private fun editViewModelFactory(): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            LibraryEditViewModel(
                index = AppGraph.index,
                store = AppGraph.store
            )
        }
    }
}
