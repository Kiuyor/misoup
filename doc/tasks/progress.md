# 总体进度 · 错题汤 Misoup

> 用法：每完成一个模块文件里的全部任务 + 模块验收标准，把对应 `[ ]` 改为 `[x]`
> 里程碑对应：M0=00｜M1=01,02｜M2=03｜M3=04,05,06｜M4=07｜M5=08（09/10 贯穿）

## 模块总览

| 模块 | 任务数 | 里程碑 | 状态 | 任务清单 |
|---|---|---|---|---|
| 00 工程骨架与数据基座 | 10 | M0 | - [x] | [00-project-setup.md](00-project-setup.md) |
| 01 录入管线 | 10 | M1 | - [ ] | [01-capture-pipeline.md](01-capture-pipeline.md) |
| 02 错题库管理 | 10 | M1 | - [ ] | [02-library-management.md](02-library-management.md) |
| 03 复习调度 | 10 | M2 | - [ ] | [03-review-scheduler.md](03-review-scheduler.md) |
| 04 离线 OCR | 8 | M3 | - [ ] | [04-offline-ocr.md](04-offline-ocr.md) |
| 05 视觉 LLM 识别 | 9 | M3 | - [ ] | [05-vision-llm.md](05-vision-llm.md) |
| 06 整页拆题 | 5 | M3末 | - [ ] | [06-page-splitting.md](06-page-splitting.md) |
| 07 AI 增强 | 7 | M4 | - [ ] | [07-ai-enhance.md](07-ai-enhance.md) |
| 08 输出与导出 | 8 | M5 | - [ ] | [08-export-output.md](08-export-output.md) |
| 09 同步与备份 | 8 | 贯穿 | - [ ] | [09-sync-backup.md](09-sync-backup.md) |
| 10 设置与安全 | 10 | 贯穿 | - [ ] | [10-settings-security.md](10-settings-security.md) |

## 进行中

- **当前模块**：01（P1 批：01/02/03 并行）
- **上次更新**：2026-08-16

## 里程碑状态

- [x] **M0 骨架**（00）
- [ ] **M1 录入闭环**（01, 02）
- [ ] **M2 复习闭环**（03）
- [ ] **M3 识别增强**（04, 05, 06）
- [ ] **M4 AI 增强**（07）
- [ ] **M5 输出收尾**（08, 09, 10）

## 环境与前置（人肉清单）

- [x] 模拟器 AVD misoup（Android 16 / WHPX）
- [ ] iQOO Z6 真机 USB 调试
- [ ] 智谱免费 Key（05 引导联调）
- [ ] 百度 Key（05 擦除）
- [ ] 坚果云测试账号（09）
- [ ] 爱发电开通（10 捐赠页）

## M0 验收记录（2026-08-16）

- 构建：`./gradlew :app:testDebugUnitTest :app:assembleDebug` 全绿（87 测试：meta 往返/StoreService 事务/索引/损坏容错/命名/缩放）
- 模拟器：安装成功 → MainActivity 前台 → uiautomator 确认 4 tab（首页/错题库/复习/我的）+ 空态文案正确 → 无崩溃；截图 doc/verify/m0-home.png
- 库树/索引列出：数据层单测覆盖 rebuild 扫描/损坏容错；列表 UI 展示在模块 02 验收
- ⚠ 待协调：`Review.rating` 编码 0-3，模块 03 定稿 1-4 → 03 派发时强制统一
