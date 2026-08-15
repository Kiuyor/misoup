# 任务 · 03 复习调度（M2）

> 依据：`doc/module/03-review-scheduler.md`｜FSRS 纯函数先行，UI 后置
> 依赖：00 完成

- [ ] T01 `Fsrs` 算法实现：Card/Params/review()，照抄 fsrs4anki 公式（纯函数、幂等）
- [ ] T02 FSRS 单测：新卡 4 连 Good 间隔递增；Again→state=1；20 组序列与参考值误差 <5%
- [ ] T03 队列构建：`query { fsrs.due <= now }` 排序（due 升序 + stability 升序），进程杀后重建不丢记录
- [ ] T04 重做卡片页：题干+原图展示，答案区遮罩，"显示答案"动画，4 档自评按钮（Again/Hard/Good/Easy）
- [ ] T05 手动框选答案区：长按进入框选模式，遮罩定位自定义（未结构拆分时兜底）
- [ ] T06 复习提交：`Fsrs.review` → `StoreService.update`（reviews[] + fsrs 字段）
- [ ] T07 首页角标 + 复习 tab 横幅："今日待复习 N 题"
- [ ] T08 统计聚合：`Stats` 计算（总数/科目分布/复习率/正确率/30 天趋势）
- [ ] T09 统计 UI：复习 tab 顶部卡片 + 展开详情
- [ ] T10 知识点掌握度基础版：`mastery(kp)` 聚合 + 红黄绿三档（M4 深化 UI）
- [ ] ✅ 验收：FSRS 单测过；10 题复习 3 题 due 变化符合预期；杀进程重开队列恢复；统计手工核算一致

**开工提示**：T01-T02 无 UI 依赖，可最先做；T04-T06 是核心交互；T08 依赖 reviews 数据。
