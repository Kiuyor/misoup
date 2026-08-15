# 任务 · 08 输出与导出（M5-A）

> 依据：`doc/module/08-export-output.md`｜骨架功能：LaTeX 级 PDF
> 依赖：02 的详情/选择能力

- [ ] T01 KaTeX 本地化：assets/katex（js+css+fonts，gzip <2MB），file:// 加载验证
- [ ] T02 PDF 模板：paper.html + paper.css（A4、页边距 15mm、题号+科目头、答题留白 60mm），重做卷/答案卷两版
- [ ] T03 渲染与打印：WebView + onPageFinished 注入渲染 + 500ms 等待 + `PrintManager.print`；公式渲染失败显示原样文本
- [ ] T04 选题入口 UI：单选/多选/按标签筛选 → 生成 PDF（保存到 SAF）
- [ ] T05 Markdown 导出：`Exporter.toMarkdown`（结构分区 + 图片相对引用）
- [ ] T06 CSV 导出：UTF-8 BOM，字段引号包裹，Excel 兼容
- [ ] T07 分享：FileProvider + 系统分享（单题图/文本；多选 zip）
- [ ] T08 Anki apkg（V1.5）：collection.anki2（SQLite）+ media + 索引；deck=科目
- [ ] ✅ 验收：含 `\frac`/上下标数学题 PDF 在 PC 渲染正确、重做卷无答案泄露；断网可生成；CSV 无乱码；分享文件可打开

**开工提示**：T01-T03 是核心链（先手工造 HTML 验证 KaTeX 再接入打印）；T08 排期 V1.5 不阻塞主链路验收。
