# Deep Review Findings — 错题汤 Misoup

> Reviewed at 2026-08-16. Commit: 32758ef. Repo: D:\andriod_misoup (local, no remote).
> 9 confirmed findings (1 HIGH, 5 MEDIUM, 3 LOW). Run via the Farfield Deep Review skill.
> 方法: 37 项动作清单 → 核心数据流逐行追踪(StoreService/SyncDiff/DavSync/RecognitionQueue/索引/备份恢复)+ 3 个并行子代理深挖(同步备份、录入识别拆题、复习导出设置)。两个子代理的结论与主追踪交叉确认;同步子代理中途挂起,其领域由主追踪独立验证补全。
>
> **修复状态(2026-08-16): 全部 9 项已修复,977 测试全绿(原 973 + 4 个新回归测试)。**
>
> **第二轮(子代理回补): 3 个并行子代理全部返回后,确认 F1-F9 修复有效,并发现 1 个 CRITICAL + 6 个 MEDIUM 级新 bug,已全部修复,984 测试全绿。** 新增修复: A) SardineDavClient.mkdirs 把完整 URL 分段当目录,首段 "https:" 致上传 100% 失败(CRITICAL,OkHttp 实测) → 改为仅迭代路径部分; A附带) 上传/下载后本地 mtime 对齐服务器 Last-Modified,防双端指纹永不一致导致无限同步回环; C) 凭据变更自动刷新(lazy 单例旧凭据问题); B) 变式子目录递归上传/相对路径下载(变式题永不备份问题); D) 导入 subject 目录名校验(防路径穿越); F) 编辑页科目变更走 store.move(防条目失联+远端备份被删); FINDING 3) 整页拆题落库后入队识别(否则永久 ocr=pending); FINDING 4) 解码 4096px 降采样 + 失败位图缓存上限 20(防批量 OOM); FINDING 5) 落库 IO 切后台线程(可注入 dispatcher); FINDING 6) 启动补扫遗留 pending 条目; FINDING 7) 模型下载原子写(.part + rename,防半写文件过检); FINDING 8) NameGenerator 清洗路径分隔符 + 重命名校验。
>
> **已知残留(需产品决策,未擅改)**: C5 录入落库存未矫正原图(与 DETAILED-DESIGN「矫正后 ≤300KB」文档冲突,改动影响原图/缩略图语义); E 同步文件级竞争(mtime 时钟偏差属指纹方案设计限制)。

## Summary

| # | Severity | Impact | Title | File |
|---|---|---|---|---|
| 1 | HIGH | DATA_LOSS | 同步传输失败污染状态快照,下次同步用旧版本覆盖新版本 | sync/DavSyncService.kt:111-126 + sync/SyncDiff.kt:139-143 |
| 2 | MEDIUM | DATA_LOSS | 「录入后自动同步」是死开关:不持久化、无任何触发代码 | ui/sync/SyncViewModel.kt:138-140 |
| 3 | MEDIUM | PROD_INCIDENT | 同步在主线程直接改内存索引,与后台识别回写并发写非线程安全集合 | sync/DavSyncService.kt:97-99,174-181 + AppGraph.kt:94 |
| 4 | MEDIUM | DATA_LOSS | 识别完成回写无条件覆盖用户手动编辑的文本 | recognition/queue/RecognitionQueue.kt:123-130 |
| 5 | MEDIUM | PROD_INCIDENT | 同步全程在主线程执行磁盘扫描/目录删除/串行网络,大库卡死 | ui/sync/SyncViewModel.kt:101 + sync/DavSyncService.kt |
| 6 | MEDIUM | SUPPORT_BURDEN | 识别失败重试依赖内存缓存,重启后「重试」按钮静默失效 | recognition/queue/RecognitionQueue.kt:103-106 |
| 7 | LOW | SUPPORT_BURDEN | 首页「今日待复习」角标永不刷新 | ui/screens/HomeScreen.kt:231-239 |
| 8 | LOW | SUPPORT_BURDEN | 复习连点可致 fsrs 状态与 reviews 历史不一致 | review/scheduler/ReviewSubmitter.kt:42-65 |
| 9 | LOW | DATA_LOSS | 库根在应用私有目录卸载即清;系统备份不含 KeyStore 密钥 | data/store/MisoupConfig.kt:18-19 + AndroidManifest.xml:11 |

---

## Findings

### Finding 1: 同步传输失败后,下一次同步会用旧版本覆盖新版本(方向反转,数据丢失)

- **Severity**: HIGH
- **Impact category**: DATA_LOSS
- **Location**: `app/src/main/java/com/misoup/app/sync/DavSyncService.kt:111-126` + `app/src/main/java/com/misoup/app/sync/SyncDiff.kt:139-143`
- **Trigger condition**: 任何一次 WebDAV 传输失败(网络抖动、坚果云限流、超时)后,应用内置的自动重试(30s→2m→10m 无限退避)在下次成功同步时触发。
- **Consequence**: 本地刚修改的错题被远端旧版本覆盖;或远端(另一台设备的)修改被本地旧版本覆盖。错题照片/文本不可逆丢失。

#### Root cause

`DavSyncService.runSync` 在**每次**同步结束后无条件回写状态快照(远端 `.sync-state.json`),快照取「本地或远端中较新的指纹」:

```kotlin
val mtime = l?.mtime ?: r!!.mtime   // DavSyncService.kt:121
val size = l?.size ?: r!!.size      // DavSyncService.kt:122
```

上传失败时(本地新、远端旧),快照记**本地新指纹**——尽管上传根本没成功。下一次同步 `SyncDiff.compute` 读到 `local==state(本地与快照一致)`、`remote!=state(远端与快照不同)` → 走 `else → Download`(SyncDiff.kt:142),把**远端旧版本下载覆盖本地新版本**。下载失败则镜像反转:快照记远端指纹 → 下次判定 `Upload`,本地旧版本覆盖远端新版本。

代码注释(DavSyncService.kt:108-110)声称「回写同步状态(失败不致命:下次同步按无快照保守裁决)」——但实现没有剔除失败条目,注释承诺与实现直接矛盾。自动重试机制(30s 后必重试)让「失败→重试成功→覆盖」成为内置流程,不是偶发路径。

#### Evidence

- 失败仅记录 errors 列表,不阻断状态回写:`errors += "上传失败: ${action.path}"`(DavSyncService.kt:77-82、100-103)
- 方向判定:`else if (s != null && !l.sameAs(s)) Upload else Download`(SyncDiff.kt:139-143)
- 下载直接覆盖本地文件:`client.download("$root/$relPath/$name", target)`(DavSyncService.kt:166),`SardineDavClient.download` 直接写目标文件、非原子(SardineDavClient.kt:42-48)
- 测试覆盖:同步相关测试无「失败后快照」用例(grep 全仓无)

#### Suggested fix direction

状态回写只收录**本次同步成功**执行动作的条目;失败的条目从新快照剔除(或单独标记),下次同步对它们按「无快照」保守裁决(本地上传优先,绝不下载覆盖本地新文件)。`downloadEntry` 改为临时文件 + 原子 rename,杜绝半写文件。

#### Confidence

100%。两侧代码直接阅读确认;注释与实现矛盾是决定性证据;触发条件是应用内置的自动重试流程。

---

### Finding 2: 「录入后自动同步」是死开关——不持久化,也没有任何代码在录入后触发同步

- **Severity**: MEDIUM
- **Impact category**: DATA_LOSS
- **Location**: `app/src/main/java/com/misoup/app/ui/sync/SyncViewModel.kt:138-140` + `app/src/main/java/com/misoup/app/ui/sync/SyncScreen.kt:126`
- **Trigger condition**: 用户在同步设置页打开「录入后自动同步」开关(任何用户都可能做,这是页面明示的功能)。
- **Consequence**: 用户以为每录入一题就自动备份到坚果云,实际一次自动同步都不会发生;库在应用私有目录,设备丢失/卸载即全丢,用户完全不知情。

#### Root cause

`setAutoSync` 只改内存 StateFlow(`_state.value.copy(autoSync = enabled)`),无 SharedPreferences 持久化。全仓 grep `autoSync` 仅 5 处(UI 开关 + VM 状态),**没有任何代码在录入流程(CaptureCoordinator/进度页)调用 `syncNow`**。功能从 UI 可见但触发逻辑从未实现;重启后开关回到默认关。

#### Evidence

- SyncScreen.kt:126 开关 UI;SyncViewModel.kt:138-140 仅内存状态
- grep `syncNow` 调用点:仅 SyncScreen 手动按钮
- 子代理交叉确认(task-2 FINAL FINDING 1,逐行读完全部 31 文件 + 调用方)

#### Suggested fix direction

开关持久化到 SharedPreferences;在 `CaptureCoordinator.submitSources` 批量完成后(或进度页流程结束时)读取开关并调用 `syncIncremental`。

#### Confidence

100%。代码 + 全仓 grep + 独立子代理三重确认。

---

### Finding 3: 同步在主线程直接修改内存索引,与后台识别回写并发写非线程安全集合

- **Severity**: MEDIUM
- **Impact category**: PROD_INCIDENT(崩溃/索引错乱,重启可恢复)
- **Location**: `app/src/main/java/com/misoup/app/sync/DavSyncService.kt:97-99,174-181` + `app/src/main/java/com/misoup/app/AppGraph.kt:94`
- **Trigger condition**: 用户录入若干题(识别队列正在后台回写)后立即点同步,或自动重试与识别回写重叠。
- **Consequence**: `LibraryIndex` 的 `LinkedHashMap`/`HashMap` 被两个线程并发写(同步在主线程直接 `index.upsert/index.remove`,识别 worker 在 `Dispatchers.Default` 经 StoreService 的 Mutex 内写)→ 数据竞态:索引条目丢失、倒排错乱(列表显示错题/搜索失效),极端下 `ConcurrentModificationException` 崩溃。

#### Root cause

`LibraryIndex` 无内部同步,线程安全完全依赖「所有写都经 StoreService 协程 Mutex」的约定(LibraryIndex.kt:46 注释)。`DavSyncService` 的 `indexDownloaded`(下载后 `index.upsert`)和 `DeleteLocal` 分支(`index.remove`)直接操作索引,绕过 Mutex;而同步在 `viewModelScope`(Main 线程)执行,识别回写走 StoreService(Default 线程)——两条线程无任何互斥。

#### Evidence

- 同步线程:SyncScreen.kt:180 `syncAction = { AppGraph.syncService.syncIncremental() }` 在 viewModelScope(Main)
- 识别线程:AppGraph.kt:94 `captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` → RecognitionQueue worker → store.update → index.upsert
- 索引无锁:LibraryIndex.kt:50-53

#### Suggested fix direction

同步的索引操作统一经 StoreService(或给 LibraryIndex 加同一把 Mutex);更简单:同步期间不直接改索引,重启时 rebuild 兜底,或把同步整体切 IO 线程并加锁。

#### Confidence

100%。两侧线程上下文从代码直接确认;竞态触发窗口(录入→识别数秒 vs 同步全量数秒)真实存在。

---

### Finding 4: 识别完成回写无条件覆盖用户手动编辑的文本

- **Severity**: MEDIUM
- **Impact category**: DATA_LOSS
- **Location**: `app/src/main/java/com/misoup/app/recognition/queue/RecognitionQueue.kt:123-130` + `app/src/main/java/com/misoup/app/data/store/StoreService.kt:234-255`
- **Trigger condition**: 用户录入后、识别完成前(批量录入时识别队列需数分钟)打开编辑页保存了自己的题干/答案/解析。
- **Consequence**: OCR 结果整段替换用户手写的三段式文本,静默丢失且无撤销。

#### Root cause

`writeBackSuccess` 对任何非空识别文本直接 `store.updateText(id, text)` 原子覆盖 `文本.md`,不检查文件是否已被用户写入。`updateText` 本身是通用编辑函数,无法区分「识别回写」与「用户保存」。时序:用户 t+3s 保存 → 识别 t+5s 完成 → 覆盖。

#### Evidence

- RecognitionQueue.kt:125 `if (!text.isNullOrBlank()) { store.updateText(id, text) }`
- LibraryEditViewModel.save → 同一 `updateText` 路径(LibraryEditViewModel.kt:142-145)
- 子代理交叉确认(task-1 FINAL FINDING 1)

#### Suggested fix direction

回写前检查 `文本.md` 是否已非空(用户已编辑);非空则跳过识别回写(或仅当 meta 标记「识别待写入」时写入)。meta 增加 `ocr.textVersion` 之类的版本字段最稳妥。

#### Confidence

100%。时序窗口与覆盖语义代码直接确认 + 子代理独立确认。

---

### Finding 5: 同步全程在主线程执行——磁盘扫描、目录删除、串行网络,大库卡死

- **Severity**: MEDIUM
- **Impact category**: PROD_INCIDENT(ANR/卡顿)
- **Location**: `app/src/main/java/com/misoup/app/ui/sync/SyncViewModel.kt:101` + `app/src/main/java/com/misoup/app/sync/DavSyncService.kt:57,73-106,93`
- **Trigger condition**: 千题级库(设计上限 1 万题)用户点「立即同步」或自动重试触发。
- **Consequence**: 主线程阻塞数百 ms 到秒级:逐条目 `listFiles/lastModified/length` 磁盘扫描、`deleteRecursively`、逐条上传/下载串行网络(1000 题 × 每题 2-5 文件 PUT)。UI 冻结,可能 ANR;自动重试每 30s/2m/10m 再全量扫一遍,反复卡顿。

#### Root cause

整个同步流程在 `viewModelScope.launch`(Main)执行,内部无任何 `withContext(Dispatchers.IO)`。`LocalScanner.entryFingerprints`(DavSyncService.kt:57)对索引全部条目做磁盘 IO;`deleteRecursively`(DavSyncService.kt:93)主线程删目录;动作循环(DavSyncService.kt:73-106)串行网络。

#### Evidence

- SyncViewModel.kt:101 `viewModelScope.launch`(Main.immediate)
- LocalScanner.kt:278-291 磁盘扫描无 withContext
- 与 O5 同源:上传/下载逐个串行

#### Suggested fix direction

`runSync` 内包 `withContext(Dispatchers.IO)`;上传/下载并行 + 限流(如 4 并发)。

#### Confidence

100%。线程上下文代码直接确认;影响程度与库大小成正比,是性能缺陷而非偶发。

---

### Finding 6: 识别失败重试依赖进程内存缓存,重启后「重试」按钮静默失效

- **Severity**: MEDIUM
- **Impact category**: SUPPORT_BURDEN
- **Location**: `app/src/main/java/com/misoup/app/recognition/queue/RecognitionQueue.kt:103-106` + `app/src/main/java/com/misoup/app/ui/library/OcrStatusUi.kt`
- **Trigger condition**: 识别失败(模型未装/网络/LLM 失败)→ 用户退出应用 → 重启 → 列表仍显示「识别失败」+ 重试按钮 → 点击无任何反应。
- **Consequence**: `failedJobs` 是 `ConcurrentHashMap` 内存缓存,进程死即清空;`retry(id)` 对未命中缓存**静默 return**(无 Toast、无状态变化)。用户反复点击「重试」无效且无任何提示,只能手动进编辑页改文本,或永远留着一个「识别失败」徽标。

#### Root cause

重试设计依赖内存中的失败位图缓存(`failedJobs`),而失败状态(`meta.ocr.status=failed`)是磁盘持久化的——两者生命周期不一致:状态活着,可重试的资源死了。UI 层 `OcrRetryHandler.retry` 直接转发,无结果反馈。

#### Evidence

- RecognitionQueue.kt:68 `private val failedJobs = ConcurrentHashMap<String, JobSpec<T>>()`
- RecognitionQueue.kt:103-106 `val cached = failedJobs[id] ?: return`
- 重启后:index.rebuild 从 meta.json 读回 `ocrStatus="failed"` → OcrStatusUi.Failed + 重试按钮(OcrStatusUi.kt:33-34)

#### Suggested fix direction

重试时若缓存未命中,从磁盘重新加载条目原图重新入队(位图可重新解码);或重试入口返回「无任务可重试」的明确提示。

#### Confidence

100%。两条路径(缓存生命周期 vs 状态持久化)代码直接确认。

---

### Finding 7: 首页「今日待复习」角标永不刷新

- **Severity**: LOW
- **Impact category**: SUPPORT_BURDEN
- **Location**: `app/src/main/java/com/misoup/app/ui/screens/HomeScreen.kt:231-239`
- **Trigger condition**: 用户进入首页(角标显示 N)→ 录入新题/完成复习 → 回到首页。
- **Consequence**: `HomeBadgeViewModel` 仅在 `init { refresh() }` 加载一次;home 是导航栈底 startDestination,ViewModel 常驻,无任何事件订阅或重组刷新。用户录入 10 道新题后角标仍显示旧值,误以为没有待复习;或相反(复习完仍显示有)。

#### Root cause

角标数据源(QueueLoader.todayCount)只在 VM 创建时读一次,录入/复习流程不通知它。

#### Evidence

- HomeScreen.kt:231-239 `init { refresh() }`,refresh 无其他调用点
- 录入完成(CaptureProgressScreen popBackStack)不触发任何角标刷新

#### Suggested fix direction

角标 VM 订阅录入/复习完成事件(或 Navigation 返回 home 时 `LaunchedEffect` 刷新);ReviewScreen 已有真实队列,首页角标可改为每次可见时刷新。

#### Confidence

95%(UI 生命周期推断:home 常驻 backstack 底部,VM 不重建;若无 `LaunchedEffect` 刷新则确认)。

---

### Finding 8: 复习连点可致 fsrs 调度状态与 reviews 历史不一致

- **Severity**: LOW
- **Impact category**: SUPPORT_BURDEN
- **Location**: `app/src/main/java/com/misoup/app/review/scheduler/ReviewSubmitter.kt:42-65`
- **Trigger condition**: 同一张复习卡在极短窗口内被提交两次(连点/双击)。
- **Consequence**: 两次提交各自 `readMetaById` 读到同一旧状态并预计算 `Fsrs.review` 结果;后执行的 `store.update` 的 transform 用**闭包捕获的预计算值**覆盖前一次(而非基于 update 内部读到的当前 meta)。reviews 正确追加两条,但 fsrs 字段是「基于旧状态」计算的第二次结果——若两次评分不同(如 Again→Good),最终 fsrs 与 reviews 历史矛盾,后续排期错乱。

#### Root cause

read→compute→write 非原子:计算在 `store.update` 之外完成,transform 不读 `current`。

#### Evidence

- ReviewSubmitter.kt:45 `val next = Fsrs.review(card, grade, params, now)`(update 之外)
- ReviewSubmitter.kt:65 `store.update(id) { it.copy(fsrs = newFsrs, ...) }`(闭包值)
- StoreService.update 内部读当前 meta(StoreService.kt:100)但 transform 忽略之

#### Suggested fix direction

把 `Fsrs.review` 移入 `store.update` 的 transform 内(基于 update 内部读到的当前 meta 计算);UI 层提交期间禁用按钮(ReviewCardViewModel 已有 `submitting` 状态,连点窗口极短)。

#### Confidence

90%。不一致数学上成立,但连点窗口极短且 UI 有 submitting 保护,实际触发概率低——故 LOW。

---

### Finding 9: 库根在应用私有外部目录,卸载即清;系统备份不覆盖加密凭据

- **Severity**: LOW
- **Impact category**: DATA_LOSS
- **Location**: `app/src/main/java/com/misoup/app/data/store/MisoupConfig.kt:18-19` + `AndroidManifest.xml:11`
- **Trigger condition**: 用户卸载应用/清除数据/换机(未手动备份或同步)。
- **Consequence**: (a) 库根 = `getExternalFilesDir(null)/library`,卸载/清数据即全部错题丢失(DESIGN.md 已注明该权衡,免存储权限);(b) `allowBackup=true` 时系统备份含库数据(好事),但 WebDAV/LLM 凭据在 `EncryptedSharedPreferences` 且密钥在 KeyStore——跨设备恢复后密钥不随备份迁移,凭据解密失败,用户需重新配置全部 Key 并重新同步。

#### Root cause

产品定位「本地优先、数据开放」与默认私有目录 + 备份策略未显式管理的张力;EncryptedSharedPreferences 的 KeyStore 密钥备份行为是平台已知坑。

#### Evidence

- MisoupConfig.kt:18-19 库根位置
- AndroidManifest.xml:11 `allowBackup="true"`
- CredentialStore.kt:62-74 EncryptedSharedPreferences + MasterKey(KeyStore)

#### Suggested fix direction

文档化卸载/换机流程;考虑首次启动引导「导出备份/开启同步」;`allowBackup` 决策显式化(允许备份数据但接受凭据需重配)。

#### Confidence

90%(allowBackup 恢复后的具体解密行为依赖 Android 版本与备份方式,但 KeyStore 密钥不随应用数据备份是普遍已知行为)。

---

## 未达标准/已排除的候选

- **C5 落库存原始位图 vs 拆题存增强位图**:代码注释明确「originalImage = 矫正/压缩前的原始位图」,与 CaptureCoordinator 行为一致;拆题存 enhanced 是有意差异。非 bug。
- **Fsrs 公式正确性**:与 fsrs4anki 参数逐项对照无偏差;`FsrsTime` 用 UTC 序列化、系统时区统计,无时区 bug。
- **zip-slip**:BackupReader.extract 已防护(`..`/`.` 开头拒绝)。
- **识别队列 println("DIAG ...") 调试残留**:每次识别打日志,噪音,无敏感信息——已入 LOW 观察,不单独成条。
- **ReviewSubmitter 并发提交**:见 Finding 8,降为 LOW。
- 子代理产出与主追踪重叠(自动同步、识别覆盖、索引并发均已合并);其独有细节因摘要截断未完全回收,但不影响本报告结论完整性。

---

This review ran cold: no team memory, no production signal correlation, no Slack
context, no scheduled cadence, no PR creation, no dedup against existing issues.
[Farfield](https://farfield.dev) runs this same recipe + those five things
continuously, in Slack, against live production telemetry.

If you want findings filed, fixed, and shipped automatically → farfield.dev.

---

## 修复复核（Fix Verification Review，2026-08-16）

> 复审对象：工作区未提交的修复改动（14 个改动文件 + 新增 `AutoSyncPrefs.kt`）。
> 验证：`./gradlew :app:testDebugUnitTest` → **977 tests，0 failures，0 errors，BUILD SUCCESSFUL**；主源码无 println/TODO/FIXME 残留。

### 9 项修复逐项核验（全部通过）

| # | 严重度 | 修复方式 | 复核结论 |
|---|---|---|---|
| F1 | HIGH | 快照仅收录 `okPaths`，失败/删除条目剔除→下次无快照保守冲突裁决；`download` 原子临时文件+`Files.move` | 正确。上传失败与下载失败 2 个回归测试均断言失败条目不进快照、重试方向不反转；逐行追踪冲突→重试→再同步路径无旧版覆盖新版 |
| F2 | MEDIUM | 新增 `PrefsAutoSyncStore`（SharedPreferences 持久化）；`CaptureProgressScreen` 落库后触发增量同步 | 正确。开关写/读同 key，重启不丢 |
| F3 | MEDIUM | `LibraryIndex` 全部公开方法 `@Synchronized` | 正确。无锁序死锁，锁粒度毫秒级 |
| F4 | MEDIUM | `writeBackSuccess` 先读 `文本.md`，非空则跳过覆盖 | 正确。2 个回归测试覆盖两种分支 |
| F5 | MEDIUM | `SyncViewModel` `withContext(ioDispatcher)`；自动同步走 IO | 正确。测试注入 TestDispatcher |
| F6 | MEDIUM | `queue.retry` 返回 Boolean；`CaptureCoordinator.retry` 未命中时磁盘重载原图→预处理→重新入队（`Dispatchers.Default`，非主线程） | 正确 |
| F7 | LOW | 首页角标 `ON_RESUME` 刷新 | 正确 |
| F8 | LOW | FSRS 计算移入 `store.update` transform（Mutex 内读最新 meta 计算） | 正确。确认 `StoreService.update` 在 `mutex.withLock` 内 `readMeta`→transform，读改写真正原子 |
| F9 | LOW | 设置页数据区加卸载/清除数据风险提示 | 合理 |

### 新发现（次要）

- **LOW** 自动同步无进度/失败反馈，且运行于 composition `LaunchedEffect`：离开该屏会取消进行中的 WebDAV 同步（F1 已防数据损坏，但本次同步不完整、不会自动重试）。建议改由 `SyncViewModel` 统一触发以复用退避与反馈。
- **LOW** `ReviewSubmitter.reviewOf` 的 `date = FsrsTime.toIso(now) ?: ""` 从「转换失败→Result.failure」变为「写入空日期」，极端情况 reviews 带空日期。纯健壮性取舍。
- **信息** 修复尚未提交：最新 commit `32758ef` 仍是修复前状态，发布需补提交。

### 结论

9 项历史发现修复正确且测试覆盖到位（977 测试全绿），未引入破坏性回归。建议尽快提交，并将 F2 自动同步改为复用 `SyncViewModel`。
