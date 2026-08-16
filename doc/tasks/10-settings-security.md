# 任务 · 10 设置与安全（贯穿）

> 依据：`doc/module/10-settings-security.md`｜极客向，技术细节可见
> 依赖：贯穿，各设置项随对应模块落地

- [x] T01 设置页框架："我的"tab 分组列表（AI 增强/识别模型/复习/数据/关于）
- [x] T02 AI 增强组：视觉/文本模型配置入口（复用 05 的引导+BYOK）、识别策略三档、限速滑块
- [x] T03 识别模型组：OCR 引擎状态/版本/检查更新（04 下载入口）、自换模型说明
- [x] T04 复习组：FSRS 参数高级编辑（17 个 w + short-term 开关）、提醒时间（V2 通知开关占位）
- [x] T05 数据组：库目录（当前路径 + SAF 迁移）、WebDAV 配置（09）、备份/恢复/导入/导出（09）、清理缓存
- [x] T06 关于组：版本号、GitHub 链接、GPL-3.0、隐私政策（链接+摘要）、捐赠（爱发电）
- [x] T07 凭据安全：EncryptedSharedPreferences 统一封装（WebDAV/百度/BYOK Key）
- [x] T08 网络与日志：HTTPS 强制拦截（http 请求拒绝+提示）；日志脱敏（无 Key/密码/内容）
- [x] T09 兼容适配：API 26-36 差异（Photo Picker 回退 ACTION_OPEN_DOCUMENT、POST_NOTIFICATIONS）；vivo/小米/华为白名单引导页（Build.MANUFACTURER 检测）
- [x] T10 合规文件：PRIVACY.md（数据本地/无遥测/BYOK 说明）、LICENSE（GPL-3.0）、THIRD-PARTY-NOTICES
- [ ] ✅ 验收：凭据全加密、卸载无明文残留；三档策略生效（隐私模式不发图）；真机 vivo 引导文案正确；捐赠链接可达

**开工提示**：T01/T06 随时可做；T07 最先做（后续所有凭据都走它）；T09 的 Photo Picker 回退在 01 里一并落地。
