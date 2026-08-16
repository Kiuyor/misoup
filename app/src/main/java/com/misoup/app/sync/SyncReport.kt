package com.misoup.app.sync

/**
 * 同步报告（模块 09 同步引擎输出，UI 展示/测试断言用）
 *
 * @param uploaded   上传条目数
 * @param downloaded 下载条目数
 * @param conflicts  冲突条目数（双端变更，保留较新 + 旧文件改名 xxx.conflict-<ts>）
 * @param deleted    删除传播条目数（一端删除、另一端跟随删除）
 * @param skipped    无变化跳过条目数
 * @param errors     错误明细（HTTPS 拒绝 / 网络失败 / 单条目失败等；空 = 全成功）
 * @param lastSyncAt 本次同步完成时间（毫秒；失败时为最近成功时间或 0）
 */
data class SyncReport(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val deleted: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList(),
    val lastSyncAt: Long = 0L
) {
    val isSuccess: Boolean get() = errors.isEmpty()

    companion object {
        /** 空报告（未执行/被拒前） */
        fun empty() = SyncReport()
    }
}
