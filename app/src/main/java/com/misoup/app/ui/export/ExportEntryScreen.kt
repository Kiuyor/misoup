package com.misoup.app.ui.export

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.misoup.app.AppGraph
import com.misoup.app.data.index.Filter
import com.misoup.app.data.index.IndexedEntry
import com.misoup.app.output.ExportItem
import com.misoup.app.output.Exporter
import com.misoup.app.output.StoreExportDataSource
import com.misoup.app.output.pdf.ExportFileName
import com.misoup.app.output.pdf.PaperMode
import com.misoup.app.output.pdf.PaperRenderer
import com.misoup.app.output.pdf.PdfTemplate
import com.misoup.app.output.saver.ExportSaver
import com.misoup.app.output.share.ShareIntents
import com.misoup.app.output.share.SharePacker
import com.misoup.app.ui.screens.library.LibraryScreen
import java.io.File
import java.time.LocalDateTime

/**
 * 导出入口页（tasks T04 选题入口 UI 的最小实现）：
 * 单选/多选/按科目筛选 → 生成 PDF（重做卷/答案卷）→ 系统打印面板存 PDF；
 * Markdown/CSV 走 SAF 保存；多选打包 zip 分享。
 *
 * 数据：AppGraph（内存索引 + StoreExportDataSource）。
 * ⚠️ 依赖 Compose/Activity Result/WebView，需模拟器或真机验证（JVM 单测覆盖纯逻辑层）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportEntryScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val source = remember { StoreExportDataSource(checkNotNull(AppGraph.store) { "AppGraph 未初始化" }) }

    var entries by remember { mutableStateOf<List<IndexedEntry>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var subjectFilter by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(PaperMode.REDO) }
    var lastMd by remember { mutableStateOf("") }
    var lastCsv by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { entries = AppGraph.index.all() }

    val subjects = remember(entries) { entries.map { it.subject }.distinct().sorted() }
    val visible = remember(entries, subjectFilter) {
        entries.filter { subjectFilter == null || it.subject == subjectFilter }
    }
    val selectedItems: List<ExportItem> = remember(selectedIds, entries) {
        selectedIds.toList().mapNotNull { source.get(it) }
    }

    // SAF 保存（免权限）
    val mdSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportSaver.MIME_MARKDOWN)
    ) { uri ->
        uri?.let { ExportSaver.writeUri(context, it, lastMd) }
    }
    val csvSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportSaver.MIME_CSV)
    ) { uri ->
        uri?.let { ExportSaver.writeUri(context, it, lastCsv) }
    }

    fun exportPdf() {
        if (selectedItems.isEmpty()) return
        val template = runCatching {
            context.assets.open("templates/paper.html").bufferedReader().readText()
        }.getOrNull() ?: return
        val questions = PdfTemplate.buildQuestionsHtml(selectedItems, mode)
        val html = PdfTemplate.render(
            template = template,
            questionsHtml = questions,
            title = "错题卷（${mode.displayName}）",
            subject = selectedItems.first().meta.subject,
            date = PdfTemplate.defaultDate(),
            count = selectedItems.size,
            mode = mode
        )
        PaperRenderer(context).print(html, "错题卷")
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("导出错题") }, navigationIcon = { }) },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { exportPdf() },
                    enabled = selectedItems.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("生成 PDF（${mode.displayName}）") }
                OutlinedButton(
                    onClick = {
                        lastMd = Exporter.toMarkdown(selectedIds.toList(), source)
                        mdSaver.launch(ExportFileName.suggest(selectedItems.first().meta.subject, "md"))
                    },
                    enabled = selectedItems.isNotEmpty()
                ) { Text("MD") }
                OutlinedButton(
                    onClick = {
                        lastCsv = Exporter.toCsv(selectedIds.toList(), source)
                        csvSaver.launch(ExportFileName.suggest(selectedItems.first().meta.subject, "csv"))
                    },
                    enabled = selectedItems.isNotEmpty()
                ) { Text("CSV") }
                OutlinedButton(
                    onClick = {
                        val intent = if (selectedItems.size == 1) {
                            ShareIntents.shareSingle(context, selectedItems.first())
                        } else {
                            ShareIntents.shareZip(
                                context, selectedItems,
                                ExportFileName.suggest(selectedItems.first().meta.subject, "zip")
                            )
                        }
                        intent?.let { context.startActivity(Intent.createChooser(it, "分享错题")) }
                    },
                    enabled = selectedItems.isNotEmpty()
                ) { Text("分享") }
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
            // 科目筛选 + 全选
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                FilterChip(
                    selected = subjectFilter == null,
                    onClick = { subjectFilter = null },
                    label = { Text("全部") }
                )
                subjects.take(6).forEach { s ->
                    FilterChip(
                        selected = subjectFilter == s,
                        onClick = { subjectFilter = if (subjectFilter == s) null else s },
                        label = { Text(s) }
                    )
                }
                Spacer(Modifier.weight(1f))
                // 模式切换：重做卷 / 答案卷
                FilterChip(
                    selected = mode == PaperMode.REDO,
                    onClick = { mode = PaperMode.REDO },
                    label = { Text("重做卷") }
                )
                FilterChip(
                    selected = mode == PaperMode.ANSWER,
                    onClick = { mode = PaperMode.ANSWER },
                    label = { Text("答案卷") }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                Text(
                    "已选 ${selectedIds.size} 题",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    selectedIds = if (selectedIds.size == visible.size) emptySet() else visible.map { it.id }.toSet()
                }) {
                    Text(if (selectedIds.size == visible.size) "全不选" else "全选")
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = entry.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + entry.id else selectedIds - entry.id
                            }
                        )
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                entry.summary.ifBlank { entry.dirName },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                "${entry.subject} · ${entry.questionType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 预览/复用：LibraryScreen 的导出入口参数见 [LibraryScreen]（onExport） */
