# 任务 · 09 同步与备份（贯穿）

> 依据：`doc/module/09-sync-backup.md`｜WebDAV 无账号 + 备份恢复
> 依赖：00 存储基座

- [ ] T01 依赖接入：sardine-android + `DavConfig`（地址/用户名/密码，密码进 EncryptedSharedPreferences）
- [ ] T02 全量上传：首次 syncFull，镜像目录到 `<dav根>/misoup-library/`
- [ ] T03 增量双向：PROPFIND 列远端（mtime+size）→ 对比本地索引 → 差集上传/下载
- [ ] T04 冲突处理：同 id 双端变更→保留较新，旧文件 `xxx.conflict-<ts>`；HTTPS 强制（http 拒绝）
- [ ] T05 同步 UI：设置页配置 + 手动触发 + 报告（上传/下载/冲突/错误），失败指数退避
- [ ] T06 备份 zip：整库 + manifest.json（id+hash 校验）→ SAF 保存
- [ ] T07 恢复：SAF 选 zip → 校验 → 解压新库 → 同名不同 hash 保留两份
- [ ] T08 单条目导入/导出：zip（目录原样 + manifest），重复 id 跳过并报告
- [ ] ✅ 验收：模拟器+真机双向同步 3 种变更传播正确；冲突双方数据都在；备份→清库→恢复 hash 校验过；断网报错不崩

**开工提示**：T02-T04 先本地起个测试 WebDAV（或直接用坚果云测试账号，人肉前置③）；T06-T08 不依赖网络可先行。
