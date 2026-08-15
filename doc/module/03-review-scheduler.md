# 模块 03 · 复习调度（M2）

> 依据：DETAILED-DESIGN.md §5。FSRS-4.5 自实现、遮罩重做、今日队列、学情统计。

## 职责
- FSRS-4.5 调度算法（自实现，无外部依赖）
- 重做流程（遮罩隐藏答案 → 作答 → 自评 → 更新排期）
- 今日待复习队列 + 首页角标
- 学情统计（三数字 + 科目分布 + 知识点聚合）

## FSRS-4.5 实现
```kotlin
object Fsrs {
  // 默认参数 17 个（fsrs4anki 初始值），存 settings，可调
  data class Params(val w: DoubleArray = DEFAULT_W, val enableShortTerm: Boolean = true)
  data class Card(val state: Int, val due: Long, val stability: Double,
                  val difficulty: Double, val reps: Int, val lapses: Int)
  fun review(card: Card, grade: Int, params: Params, now: Long): Card
  // grade: 1=Again 2=Hard 3=Good 4=Easy；state: 0新 1学 2熟 3重学
}
```
- 算法要点（照抄 fsrs4anki 公式，勿自创）：initial stability、recall stability（含 short-term 间隔）、difficulty 更新、interval = stability × interval modifier（Good 1.0/Hard 0.8/Easy 1.3）、due = now + interval × 86400s
- 幂等：同一 grade 重复调用结果一致（纯函数），便于测试

## 重做流程
```
今日队列 → 卡片页(题干+原图, 答案区遮罩) → [显示答案] → 自评 4 按钮(Again/Hard/Good/Easy)
→ Fsrs.review → StoreService.update(reviews+fsrs) → 下一张
```
- 遮罩实现：答案/解析区 Canvas 覆盖层（不透明色块），"显示答案"动画揭开；遮罩区位置默认 = 结构拆分的 answer 段，未拆分时用户可长按框选
- 队列顺序：due 升序；同 due 按 stability 升序（先复习最不稳定的）
- 中断恢复：进程杀后重进 → 队列从当前 due 重建（review 记录已持久化，不丢）

## 今日队列与提醒
- 队列来源：`index.query { fsrs.due <= now }`；首页角标 = 数量
- 提醒（V2 简化版本模块先做应用内，不做系统通知）：
  - 首页 + 复习 tab 顶部横幅"今日待复习 N 题"
  - 系统通知 WorkManager 放 V2（模块 10 排期），本模块留 `ReminderScheduler` 空接口

## 学情统计
```kotlin
data class Stats(
  val total: Int, val bySubject: Map<String, Int>,
  val reviewRate: Double,       // due 内已完成 / 应完成
  val accuracy: Double,         // reviews 中 Good+Easy 占比
  val trend30d: List<Pair<LocalDate, Int>>,  // 每日复习数
  val mastery: Map<String, Double>  // 知识点: 0~1（错题数×正确率加权，<0.4 红 / <0.7 黄 / 其余绿）
)
```
- 统计页：复习 tab 内嵌（或"我的"tab 入口，定：复习 tab 顶部卡片 + 展开详情）

## 验收标准
- [ ] FSRS 单测：新卡 4 连 Good → due 间隔单调递增；Again → state=1(学) 且 interval 缩短；20 组序列与 fsrs4anki 参考值误差 <5%
- [ ] 模拟器造 10 题 → 复习 3 题 → 队列数量与 due 变化符合预期
- [ ] 进程杀掉重开 → 队列恢复，无重复复习记录
- [ ] 统计数字与手工核算一致
