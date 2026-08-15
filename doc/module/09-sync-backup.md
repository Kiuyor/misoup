# 模块 09 · 同步与备份（贯穿）

> 依据：DETAILED-DESIGN.md §7。WebDAV 无账号同步 + 整库备份恢复 + 导入导出。

## 职责
- WebDAV 双向同步（坚果云等，无账号体系）
- 整库备份（zip）与恢复
- 外部数据导入（兼容自身导出格式）

## WebDAV
- 库：`com.github.thegrizzlylabs:sardine-android`
- 配置（设置页）：地址 / 用户名 / 密码；密码存 `EncryptedSharedPreferences`；"清除凭据"一键
- 远程结构：`<dav根>/misoup-library/<科目>/<题目目录>/...`（与本地镜像）

```kotlin
class DavSyncService(
  private val cfg: () -> DavConfig, private val store: StoreService, private val index: LibraryIndex
) {
  suspend fun syncFull(): SyncReport     // 首次：全量上传
  suspend fun syncIncremental(): SyncReport  // 双向增量
}
data class SyncReport(val uploaded: Int, val downloaded: Int, val conflicts: Int, val errors: List<String>)
```
- 增量算法：PROPFIND 列远端（mtime + size）→ 对比本地索引 → 差集上传/下载
- 冲突策略：同 id 双方都变 → 保留较新；旧文件改名 `xxx.conflict-<ts>`（不删任何一方）
- 触发：设置页手动 / 录入后自动（默认关，极客可开）；串行协程，失败指数退避（30s→2m→10m）
- 安全性：仅 HTTPS（http 拒绝 + 明确警告）；同步内容 = 明文文件（设计如此，数据开放原则）

## 备份 / 恢复
- 备份：整库目录 → zip（条目清单 manifest.json 含 id+hash 校验）→ SAF 保存
- 恢复：SAF 选 zip → 校验 manifest → 解压到新库 → 冲突（同名不同 hash）→ 保留两份（`.imported-<ts>`）
- 一键"恢复上次备份"入口：设置页（可覆盖确认）

## 导入 / 导出（单条目级）
- 导出：单题/多题 → zip（目录结构原样 + manifest）→ 分享/保存
- 导入：识别 manifest 或裸目录 → 校验 meta → 进库（重复 id → 跳过并报告）

## 验收标准
- [ ] 坚果云测试账号：两台设备（模拟器 + 真机）双向同步，新增/修改/删除 3 种变更各自正确传播
- [ ] 冲突场景（同题双端修改）→ 产生 conflict 文件，双方数据都在
- [ ] 备份→清空库→恢复 → 数据与缩略图完整（hash 校验通过）
- [ ] 断网 → 同步按钮明确报错，应用其余功能正常
