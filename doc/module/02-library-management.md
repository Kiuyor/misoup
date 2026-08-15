# 模块 02 · 错题库管理（M1）

> 依据：DETAILED-DESIGN.md §2.3/§8。列表、筛选、搜索、编辑、移动、删除、自动命名、详情页。

## 职责
- 错题列表（分组/筛选/搜索）
- 详情页（图文展示 + 编辑 + 遮罩开关 + 变式/答疑入口占位）
- 管理操作：编辑 / 移动科目 / 删除 / 自动命名

## 列表页（错题库 tab）
```kotlin
@Composable fun LibraryScreen(
  state: LibraryUiState,          // 由 LibraryViewModel 从 LibraryIndex.query 驱动
  onOpen: (String) -> Unit, onSearch: (String) -> Unit, ...
)
data class LibraryUiState(
  val groups: List<SubjectGroup>,   // 按科目分组，组内按时间倒序
  val filter: Filter,               // 科目/题型/错因/标签 多选
  val keyword: String,
  val total: Int
)
```
- 展示：缩略图（Coil 256px 缓存）+ 摘要（题干前两行 + 标签 chips）+ 识别状态徽标（pending 灰点 / failed 红点可点重试 / done 无）
- 视图切换：列表 / 瀑布流（StaggeredGrid）
- 筛选栏：横向滚动 chips 组（科目→题型→错因→标签），多选叠加
- 搜索：顶部 TextField，防抖 300ms，命中题干全文 + 标签

## 详情页
- 大图（Coil，可双指缩放）+ 下方分区：题干（LaTeX 渲染）/ 答案 / 解析（折叠）
- 顶部操作：编辑 / 移动 / 删除（确认对话框）/ 分享
- **遮罩开关**（本模块实现 UI，答案区默认隐藏逻辑在模块 03）："隐藏答案"toggle → 答案/解析区覆盖半透明遮罩层
- 识别状态条：pending 显示"识别中…"；failed 显示"识别失败 [重试]"；文本.md 可手动编辑（点开编辑页）
- 占位入口：变式 / 答疑按钮（M4 填充）

## 管理操作
- 编辑页：题干/答案/解析 三栏文本编辑（LaTeX 源码可改）+ 科目/题型/错因/标签/知识点/来源/备注 表单
- 移动：改 subject → `StoreService.move`（目录迁移 + 索引更新）
- 删除：双确认（"删除后不可恢复"），删目录 + 索引 + 缩略图
- 自动命名：创建时生成（摘要 12 字），编辑题干后可"重命名"按钮刷新目录名（保持 id 不变，仅目录名变化 → 需要 id 与目录名解耦：id 存 meta.id，目录名可改）

> ⚠️ 设计修正：meta.id 用 UUID 而非目录名（目录名=展示名，可改）。目录名改变时 meta.id 不变，索引以 meta.id 为键。`StoreService.move` 同逻辑。

## 验收标准
- [ ] 造 20 题（脚本/手工）跨 3 科目 → 分组列表、筛选叠加、关键词搜索全部命中
- [ ] 编辑保存 → 详情/列表即时刷新；目录名重命名后重启不丢关联
- [ ] 移动科目 → 目录树迁移正确；删除 → 目录与缩略图清除
- [ ] 识别 failed 条目点重试 → 状态流转正常
