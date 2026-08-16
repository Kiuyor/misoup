package com.misoup.app.ui.screens.mine

import com.misoup.app.AppGraph
import com.misoup.app.review.stats.Stats
import com.misoup.app.review.stats.StatsCalculator

/**
 * 「我的」页生产数据源（模块 10 T01）
 *
 * 统计概览：复用模块 03 [StatsCalculator]——读全部正常条目 meta 后聚合。
 * 与 AppGraph 绑定，仅 UI 层工厂调用；ViewModel 本身依赖注入保持可测。
 */
suspend fun loadMineStats(): Stats? {
    val store = AppGraph.store ?: return null
    val index = AppGraph.index
    val metas = index.all()
        .filter { !it.needsRepair }
        .mapNotNull { store.readMetaById(it.id).getOrNull() }
    return StatsCalculator.calculate(metas, System.currentTimeMillis())
}
