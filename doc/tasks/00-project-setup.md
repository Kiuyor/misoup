# 任务 · 00 工程骨架与数据基座（M0）

> 依据：`doc/module/00-project-setup.md`｜开工顺序：本模块第一个
> 完成标准：全部勾选 + 模块验收标准通过

- [ ] T01 初始化 Gradle 工程：`settings.gradle.kts`/`build.gradle.kts`/`gradle/libs.versions.toml`，套用 `D:\Android\templates` 镜像模板，minSdk 26 / targetSdk 36，Kotlin 2.x + Compose BOM
- [ ] T02 依赖基线入 toml：navigation-compose / coil-compose / kotlinx-serialization-json / okhttp / lifecycle-viewmodel-compose / work-runtime（opencv、onnxruntime 仅声明）
- [ ] T03 主题与资源：Material3 暗色默认 + 字符串资源全中文；`MisoupApp.kt` 挂主题
- [ ] T04 导航壳：NavHost + NavigationBar 4 tab（首页/错题库/复习/我的），各 tab 空态占位页（图标+中文文案）
- [ ] T05 数据模型：`MisoupMeta` @Serializable（§2.2 Schema）+ `Images/OcrState/FsrsState/Review` + 枚举（科目/题型/错因）
- [ ] T06 `MisoupConfig`：库根默认 `getExternalFilesDir(null)/library`、命名格式、枚举常量、SAF 迁移接口预留
- [ ] T07 `StoreService`：create/update/delete/move，临时目录→校验→原子 rename→索引更新，协程 Mutex 串行
- [ ] T08 `LibraryIndex`：rebuild/upsert/remove/query（Filter + keyword），倒排（题干文本→id）
- [ ] T09 缩略图服务：256px WebP 生成到 `cacheDir/thumbs/<id>.webp`
- [ ] T10 损坏 meta 容错：扫描遇解析失败→标"待修复"条目（展示原始 JSON），不崩溃
- [ ] ✅ 验收：`gradle build` 绿；模拟器启动进首页 4 tab；adb 验证库目录树；手工放合法 meta 重启后索引列出

**开工提示**：T01-T02 是纯构建配置；T05-T06 是类型骨架，先于 UI 完成；T07-T09 依赖 T05。
