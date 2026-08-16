package com.misoup.app.sync

import com.misoup.app.sync.SyncDiff.Action
import com.misoup.app.sync.SyncDiff.LocalInfo
import com.misoup.app.sync.SyncDiff.Plan
import com.misoup.app.sync.SyncDiff.RemoteInfo
import com.misoup.app.sync.SyncDiff.StateInfo
import com.misoup.app.sync.SyncDiff.Winner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 差集引擎单测（模块 09 T03 增量双向 + T04 冲突裁决）：纯逻辑全覆盖
 *
 * 指纹约定：mtime + size 相同 = 同一版本；path = `<科目>/<条目目录>`
 */
class SyncDiffTest {

    private fun local(path: String, mtime: Long, size: Long = 100) = LocalInfo(path, mtime, size)
    private fun remote(path: String, mtime: Long, size: Long = 100) = RemoteInfo(path, mtime, size)
    private fun state(path: String, mtime: Long, size: Long = 100) = StateInfo(mtime, size)

    private fun actionsOf(plan: Plan) = plan.actions

    // ---------- T03 差集：新增 ----------

    @Test
    fun `仅本地有条目 - 上传`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000)),
            remote = emptyMap(),
            state = emptyMap()
        )
        assertEquals(listOf(Action.Upload("数学/A")), actionsOf(plan))
    }

    @Test
    fun `仅远端有条目 - 下载`() {
        val plan = SyncDiff.compute(
            local = emptyMap(),
            remote = mapOf("数学/A" to remote("数学/A", 2000)),
            state = emptyMap()
        )
        assertEquals(listOf(Action.Download("数学/A")), actionsOf(plan))
    }

    @Test
    fun `双端相同 - 跳过（DropState 无 IO 动作）`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 200)),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 200)),
            state = emptyMap()
        )
        assertEquals(listOf(Action.DropState("数学/A")), actionsOf(plan))
    }

    @Test
    fun `双端相同且 state 有残留 - 仅清理 state`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 200)),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 200)),
            state = mapOf("数学/A" to state("数学/A", 1000, 200))
        )
        assertEquals(listOf(Action.DropState("数学/A")), actionsOf(plan))
    }

    @Test
    fun `仅 state 有条目 - 双端删除清理`() {
        val plan = SyncDiff.compute(
            local = emptyMap(),
            remote = emptyMap(),
            state = mapOf("数学/A" to state("数学/A", 1000))
        )
        assertEquals(listOf(Action.DropState("数学/A")), actionsOf(plan))
    }

    // ---------- T03 增量：单端变更 ----------

    @Test
    fun `本地变远端未变 - 上传`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 3000, 150)),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 100)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.Upload("数学/A")), actionsOf(plan))
    }

    @Test
    fun `远端变本地未变 - 下载`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = mapOf("数学/A" to remote("数学/A", 3000, 150)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.Download("数学/A")), actionsOf(plan))
    }

    @Test
    fun `双端未变 - 跳过（DropState）`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 100)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.DropState("数学/A")), actionsOf(plan))
    }

    // ---------- T04 冲突：双端变更 ----------

    @Test
    fun `双端都变 - 本地较新保留本地 远端旧文件改名 conflict`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 5000, 200)),
            remote = mapOf("数学/A" to remote("数学/A", 4000, 120)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100)),
            now = 999_999L
        )
        val c = plan.conflicts.single()
        assertEquals(Winner.LOCAL, c.winner)
        assertEquals("数学/A.conflict-999999", c.loserRename)
        assertTrue(plan.uploads.isEmpty() && plan.downloads.isEmpty())
    }

    @Test
    fun `双端都变 - 远端较新保留远端 本地旧文件改名 conflict`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 4000, 120)),
            remote = mapOf("数学/A" to remote("数学/A", 5000, 200)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100)),
            now = 888_888L
        )
        val c = plan.conflicts.single()
        assertEquals(Winner.REMOTE, c.winner)
        assertEquals("数学/A.conflict-888888", c.loserRename)
    }

    @Test
    fun `双端都变且 mtime 相同 - 本地优先`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 5000, 100)),
            remote = mapOf("数学/A" to remote("数学/A", 5000, 200)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(Winner.LOCAL, plan.conflicts.single().winner)
    }

    @Test
    fun `无 state 记录且指纹不同 - 判冲突而非静默覆盖`() {
        // 首次增量（无快照）双端内容不同 → 冲突保护，不覆盖任何一方
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = mapOf("数学/A" to remote("数学/A", 2000, 200)),
            state = emptyMap()
        )
        assertEquals(1, plan.conflicts.size)
    }

    @Test
    fun `无 state 记录且指纹相同 - 跳过（DropState）`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 100)),
            state = emptyMap()
        )
        assertEquals(listOf(Action.DropState("数学/A")), actionsOf(plan))
    }

    // ---------- T03 删除传播 ----------

    @Test
    fun `远端删除且本地未变 - 删除本地副本`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = emptyMap(),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.DeleteLocal("数学/A")), actionsOf(plan))
    }

    @Test
    fun `本地删除且远端未变 - 删除远端副本`() {
        val plan = SyncDiff.compute(
            local = emptyMap(),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 100)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.DeleteRemote("数学/A")), actionsOf(plan))
    }

    @Test
    fun `远端删除但本地已改 - 本地复活上传 不丢数据`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 5000, 300)),
            remote = emptyMap(),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.Upload("数学/A")), actionsOf(plan))
    }

    @Test
    fun `本地删除但远端已改 - 远端复活下载 不丢数据`() {
        val plan = SyncDiff.compute(
            local = emptyMap(),
            remote = mapOf("数学/A" to remote("数学/A", 5000, 300)),
            state = mapOf("数学/A" to state("数学/A", 1000, 100))
        )
        assertEquals(listOf(Action.Download("数学/A")), actionsOf(plan))
    }

    @Test
    fun `多条目混合 - 计划含全部动作类型且按路径排序`() {
        val plan = SyncDiff.compute(
            local = mapOf(
                "数学/A" to local("数学/A", 5000, 200),   // 冲突(本地赢)
                "数学/B" to local("数学/B", 3000, 150),   // 仅本地 → 上传
                "英语/C" to local("英语/C", 1000, 100)    // 双端同 → 跳过
            ),
            remote = mapOf(
                "数学/A" to remote("数学/A", 4000, 120),
                "英语/C" to remote("英语/C", 1000, 100),
                "物理/D" to remote("物理/D", 2000, 80)    // 仅远端 → 下载
            ),
            state = mapOf(
                "数学/A" to state("数学/A", 1000, 100),
                "数学/B" to state("数学/B", 1000, 100),
                "英语/C" to state("英语/C", 1000, 100),
                "物理/D" to state("物理/D", 1000, 100)
            ),
            now = 123L
        )
        assertEquals(
            listOf(
                Action.Conflict("数学/A", Winner.LOCAL, "数学/A.conflict-123"),
                Action.Upload("数学/B"),
                Action.Download("物理/D"),
                Action.DropState("英语/C")
            ),
            actionsOf(plan)
        )
    }

    // ---------- T02 全量模式（ignoreState） ----------

    @Test
    fun `全量模式忽略 state - 双端不同按冲突 不参考快照`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 5000, 200)),
            remote = mapOf("数学/A" to remote("数学/A", 4000, 120)),
            state = mapOf("数学/A" to state("数学/A", 3000, 100)),  // state 显示远端也变过，但全量忽略
            ignoreState = true,
            now = 1L
        )
        assertEquals(1, plan.conflicts.size)
        assertEquals(Winner.LOCAL, plan.conflicts.single().winner)
    }

    @Test
    fun `全量模式忽略 state - 远端删除不传播 一律视为新增`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = emptyMap(),
            state = mapOf("数学/A" to state("数学/A", 1000, 100)),
            ignoreState = true
        )
        assertEquals(listOf(Action.Upload("数学/A")), actionsOf(plan))
    }

    @Test
    fun `全量模式忽略 state - 双端相同仍跳过`() {
        val plan = SyncDiff.compute(
            local = mapOf("数学/A" to local("数学/A", 1000, 100)),
            remote = mapOf("数学/A" to remote("数学/A", 1000, 100)),
            state = emptyMap(),
            ignoreState = true
        )
        assertEquals(listOf(Action.DropState("数学/A")), actionsOf(plan))
    }

    @Test
    fun `冲突命名 - dirName-conflict-ts 格式`() {
        assertEquals("物理/牛顿第二定律.conflict-42", SyncDiff.conflictName("物理/牛顿第二定律", 42))
    }
}
