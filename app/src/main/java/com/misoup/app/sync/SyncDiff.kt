package com.misoup.app.sync

/**
 * 同步差集引擎（模块 09 T03/T04）：纯逻辑，无 IO，可 JVM 全量单测
 *
 * 输入三方快照（均以「远程相对路径」为键，如 `数学/20260816_140523_二次函数`）：
 * - [local]  本地条目指纹（mtime + size，来自 LibraryIndex + 磁盘扫描）
 * - [remote] 远端条目指纹（PROPFIND mtime + size）
 * - [state]  上次同步快照（决定「谁在同步后变过」）
 *
 * 裁决规则（T03 差集 + T04 冲突）：
 * - 仅在本地 → 上传（新条目推远端）
 * - 仅在远端 → 下载（新条目拉本地）
 * - 双端都有：
 *   - 指纹相同 → 跳过
 *   - 本地变 / 远端未变（相对 state）→ 上传
 *   - 远端变 / 本地未变 → 下载
 *   - 双端都变（或无 state 记录且指纹不同）→ 冲突：保留较新（mtime 大者），
 *     旧文件改名 `xxx.conflict-<ts>`（不删任何一方，T04）
 * - 本地有 + state 有 + 远端无：远端删除 → 本地若未变则跟随删除（删除传播）；
 *   本地若变过 → 本地上传（改后复活优先，不丢数据）
 * - 远端有 + state 有 + 本地无：本地删除 → 远端若未变则跟随删除；
 *   远端若变过 → 下载（改后复活优先）
 * - 仅 state 有：双端都删 → 清理 state
 *
 * 删除传播说明：仅当「被删侧自上次同步后未再修改」时传播删除，否则以修改侧为准，
 * 保证任何情况下不丢数据（文档未规定墓碑机制，此为安全裁决，见报告）。
 */
object SyncDiff {

    /** 本地条目指纹 */
    data class LocalInfo(val path: String, val mtime: Long, val size: Long)

    /** 远端条目指纹 */
    data class RemoteInfo(val path: String, val mtime: Long, val size: Long)

    /** 上次同步快照条目 */
    data class StateInfo(val mtime: Long, val size: Long)

    /** 冲突赢家 */
    enum class Winner { LOCAL, REMOTE }

    /** 单条目同步动作 */
    sealed class Action {
        /** 上传本地条目到远端（整个条目目录） */
        data class Upload(val path: String) : Action()

        /** 下载远端条目到本地 */
        data class Download(val path: String) : Action()

        /**
         * 冲突：保留较新者作为正式条目，败者改名为 [loserRename]（`xxx.conflict-<ts>`）。
         * 若 winner=LOCAL：远端旧文件在远端改名（远程 move）；本地上传作为正式条目。
         * 若 winner=REMOTE：本地旧目录改名 [loserRename]，远端下载作为正式条目。
         */
        data class Conflict(
            val path: String,
            val winner: Winner,
            val loserRename: String
        ) : Action()

        /** 删除传播：删除远端副本（本地删除后远端未变） */
        data class DeleteRemote(val path: String) : Action()

        /** 删除传播：删除本地副本（远端删除后本地未变） */
        data class DeleteLocal(val path: String) : Action()

        /** 双端都删除：仅清理 state，无 IO */
        data class DropState(val path: String) : Action()
    }

    /** 差集计划（按 path 稳定排序，执行顺序确定可测） */
    data class Plan(
        val actions: List<Action>
    ) {
        val uploads: List<Action.Upload> get() = actions.filterIsInstance<Action.Upload>()
        val downloads: List<Action.Download> get() = actions.filterIsInstance<Action.Download>()
        val conflicts: List<Action.Conflict> get() = actions.filterIsInstance<Action.Conflict>()
        val deleteRemote: List<Action.DeleteRemote> get() = actions.filterIsInstance<Action.DeleteRemote>()
        val deleteLocal: List<Action.DeleteLocal> get() = actions.filterIsInstance<Action.DeleteLocal>()
        val skipped: Int get() = actions.count { it is Action.DropState }
    }

    /**
     * 计算差集计划。
     *
     * @param ignoreState true = 全量模式（T02 syncFull）：忽略上次快照，
     *   双端都有的条目一律按「双端都变」处理 → 指纹相同跳过、不同按 mtime 冲突裁决；
     *   删除传播也不参考 state（仅本地/仅远端视为新增，先拉后推保证镜像）。
     */
    fun compute(
        local: Map<String, LocalInfo>,
        remote: Map<String, RemoteInfo>,
        state: Map<String, StateInfo>,
        ignoreState: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): Plan {
        val allPaths = (local.keys + remote.keys + state.keys).toSortedSet()
        val actions = ArrayList<Action>(allPaths.size)

        for (path in allPaths) {
            val l = local[path]
            val r = remote[path]
            val s = state[path]

            when {
                l != null && r == null -> {
                    // 仅本地有：远端无。state 有且本地自上次同步未修改 → 远端删除了它 → 跟随删除本地副本；
                    // 否则视为本地新增/复活 → 上传
                    if (!ignoreState && s != null && l.sameAs(s)) {
                        actions += Action.DeleteLocal(path)
                    } else {
                        actions += Action.Upload(path)
                    }
                }
                r != null && l == null -> {
                    // 仅远端有：本地无。state 有且远端自上次同步未修改 → 本地删除了它 → 跟随删除远端副本；
                    // 否则视为远端新增/复活 → 下载
                    if (!ignoreState && s != null && r.sameAs(s)) {
                        actions += Action.DeleteRemote(path)
                    } else {
                        actions += Action.Download(path)
                    }
                }
                l != null && r != null -> {
                    val localChanged = !l.sameAs(r)
                    if (!localChanged) {
                        actions += Action.DropState(path)   // 指纹相同 → 跳过（并清理 state）
                    } else {
                        val conflict = when {
                            ignoreState -> true
                            s == null -> true              // 无历史记录且指纹不同 → 冲突
                            else -> !l.sameAs(s) && !r.sameAs(s)  // 双端都变 → 冲突
                        }
                        if (conflict) {
                            // T04：保留较新（mtime 大者；相等时本地优先），旧文件改名
                            val winner = if (l.mtime >= r.mtime) Winner.LOCAL else Winner.REMOTE
                            actions += Action.Conflict(path, winner, conflictName(path, now))
                        } else if (s != null && !l.sameAs(s)) {
                            actions += Action.Upload(path)   // 仅本地变
                        } else {
                            actions += Action.Download(path) // 仅远端变
                        }
                    }
                }
                else -> {
                    // 仅 state 有：双端都删 → 清理 state
                    actions += Action.DropState(path)
                }
            }
        }
        return Plan(actions)
    }

    /** 指纹相同判定：mtime 与 size 均一致视为同一版本 */
    private fun LocalInfo.sameAs(other: StateInfo) = mtime == other.mtime && size == other.size
    private fun RemoteInfo.sameAs(other: StateInfo) = mtime == other.mtime && size == other.size
    private fun LocalInfo.sameAs(other: RemoteInfo) = mtime == other.mtime && size == other.size

    /** 冲突旧文件命名：`<dirName>.conflict-<ts>`（ts = 裁决时刻毫秒） */
    fun conflictName(path: String, now: Long): String = "$path.conflict-$now"

    /** 上一版兼容别名：供测试/调用方构造状态 */
    fun stateOf(mtime: Long, size: Long) = StateInfo(mtime, size)
}
