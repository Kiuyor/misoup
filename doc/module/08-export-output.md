# 模块 08 · 输出与导出（M5-A）

> 依据：DESIGN.md §6 / DETAILED-DESIGN.md §6。骨架功能：LaTeX 级排版 PDF + 多格式导出。

## 职责
- LaTeX 级 PDF（KaTeX 本地渲染 + WebView + 系统 Print）
- Markdown / CSV 导出、系统分享
- Anki apkg（V1.5，排期靠后）

## PDF 生成（核心交付）
```
选错题(单选/多选/标签筛选) → 生成 HTML → WebView 加载(本地KaTeX) → PrintManager → 保存PDF
```
1. **模板**（`assets/templates/paper.html` + `paper.css`）：A4、页边距 15mm、题号 + 科目/日期头、每题卡片（题干图文 + 答题留白 60mm）
2. **渲染**：KaTeX 本地化（`assets/katex/`：katex.min.js + css + fonts，gzip 后 <2MB）；`file://` 加载；`WebViewClient.onPageFinished` 后注入 `renderMathInElement` → 等待 500ms → 触发打印
3. **打印**：`PrintManager.print("错题卷", WebView.createPrintDocumentAdapter(), attrs)`；用户选"保存为 PDF"（系统打印面板自带）
4. **两种模板**：重做卷（无答案/解析，留作答区）/ 答案卷（含答案+解析，答案区浅色底）
5. 公式兜底：KaTeX 渲染失败（非法 LaTeX）→ 显示 `$...$` 原样文本，不崩

## Markdown / CSV
```kotlin
object Exporter {
  fun toMarkdown(ids: List<String>): String   // 每题: ## 标题 + 题干 + 答案/解析 + ![图](相对路径)
  fun toCsv(ids: List<String>): String        // 题干,科目,题型,错因,知识点,创建时间,复习状态
}
```
- CSV 编码 UTF-8 BOM（Excel 中文兼容）；字段含逗号 → 引号包裹
- 导出位置：SAF `ACTION_CREATE_DOCUMENT`（免权限）

## 分享
- 单题：`FileProvider`（authority `com.misoup.app.fileprovider`）+ 系统分享（图 + Markdown 文本）
- 多选：临时打包 zip → 分享

## Anki apkg（V1.5）
- 结构：`collection.anki2`（SQLite：notes/cards/models）+ `media` 目录 + `media` 索引 json
- 字段：正面=题干图+文本，背面=答案+解析；deck=科目
- 实现自写（apkg 格式公开，约 300 行）；排期 V1.5 不阻塞主链路

## 验收标准
- [ ] 数学题（含 `\frac`、上下标）PDF 在 PC 打开：公式渲染正确、分页正常、重做卷无答案泄露
- [ ] KaTeX 离线可用（断网生成 PDF 成功）；APK 体积增量 <2.5MB
- [ ] CSV Excel 打开中文无乱码；Markdown 预览器打开结构正确
- [ ] 分享到微信/QQ 文件可打开
