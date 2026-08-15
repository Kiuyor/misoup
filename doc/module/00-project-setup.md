# 模块 00 · 工程骨架与数据基座（M0）

> 依据：DETAILED-DESIGN.md §1/§2/§8。本模块是其他一切的地基，验收不过不开下一模块。

## 职责
- Gradle 工程建立（含中国镜像模板）
- 主题与导航壳（4 tab）
- 文件系统存储基座：库根、目录树、meta.json 读写、内存索引

## 工程基线
- 位置 `D:\andriod_misoup`，单 `app` 模块，Kotlin DSL
- 镜像：抄 `D:\Android\templates`（gradle-wrapper.properties 腾讯源 + settings.gradle.kts 阿里云仓库）
- `minSdk 26 / targetSdk 36`，Kotlin 2.x，Compose BOM 最新稳定
- 依赖基线（版本取当时最新稳定，锁版本号进 gradle/libs.versions.toml）：
  compose-bom / navigation-compose / coil-compose / kotlinx-serialization-json / okhttp / lifecycle-viewmodel-compose / work-runtime / opencv / onnxruntime-android（后两个本模块只声明不实现）

## 主题（色板定稿前先用临时值，UI 轮次再定）
- 暗色为默认，Material3 darkColorScheme
- 主色候选：暖汤橙 `#D97A38` 系；确认前用默认 Material 紫（避免影响其他模块开发）

## 导航壳
- 底部 4 tab：首页(录入+今日队列) / 错题库 / 复习 / 我的
- `NavHost` + `NavigationBar`；首页为 start destination
- 各 tab 页签先放空态占位（图标+中文文案），不阻塞后续模块

## 数据基座（本模块核心交付）

### 常量与配置
- `MisoupConfig`：库根路径（默认 `context.getExternalFilesDir(null)/library`）、科目/题型/错因枚举、命名格式 `yyyyMMdd_HHmmss_摘要`
- 库根迁移（SAF 选目录）接口预留：`LibraryRoot.get/set`

### meta.json 读写（定稿 Schema 见 DETAILED-DESIGN §2.2）
```kotlin
@Serializable data class MisoupMeta(
  val schemaVersion: Int = 1,
  val id: String, val subject: String, val questionType: String,
  val errorReasons: List<String>, val tags: List<String>,
  val knowledgePoints: List<String>, val note: String = "", val source: String = "",
  val createdAt: String, val updatedAt: String,
  val images: Images, val textFile: String = "文本.md",
  val ocr: OcrState, val fsrs: FsrsState, val reviews: List<Review> = emptyList()
)
```

### StoreService（唯一写入口，协程 Mutex 串行）
```kotlin
class StoreService(private val root: File, private val index: LibraryIndex) {
  suspend fun create(meta: MisoupMeta, originalImage: ImageAsset, text: String?): Result<String>
  suspend fun update(id: String, transform: (MisoupMeta) -> MisoupMeta): Result<Unit>
  suspend fun delete(id: String): Result<Unit>
  suspend fun move(id: String, newSubject: String): Result<Unit>
}
```
- 写流程：临时目录（`.tmp-<ts>`）→ 写文件 → 校验 meta 可解析 → rename 进库 → 更新索引
- 图片落盘：WebP 编码（quality 80，长边 2048 封顶）；原图存 `原图.webp`，原始 jpg 可省

### LibraryIndex（内存索引）
```kotlin
class LibraryIndex {
  fun rebuild(root: File)                    // 启动全量扫描
  fun upsert(meta: MisoupMeta, thumb: String)
  fun remove(id: String)
  fun query(filter: Filter, keyword: String?): List<IndexedEntry>
}
```
- `IndexedEntry = (meta 摘要, thumbPath, mtime)`；倒排：题干文本分词 → id 列表（识别完成后填充）
- 缩略图：首次访问生成 256px WebP 到 `cacheDir/thumbs/<id>.webp`

## 验收标准
- [ ] `gradle build` 全绿（模拟器/真机均无 ABI 报错）
- [ ] 启动进首页 4 tab，空态文案正确
- [ ] adb 进入 app 私有目录可见 `library/` 树；手工放置合法 meta.json 后重启 → 索引正确列出
- [ ] 损坏 meta.json → 出现"待修复"条目且不崩溃
