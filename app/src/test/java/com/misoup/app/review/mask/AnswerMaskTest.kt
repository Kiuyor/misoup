package com.misoup.app.review.mask

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T05 手动框选答案区：MaskRect 归一化/钳制/交换、MaskSelectionState 状态机、
 * MaskStore 持久化（正常 / 失败路径）
 */
class AnswerMaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private lateinit var maskStore: MaskStore
    private val img = FakeImageAsset(100, 100)

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
        maskStore = MaskStore(root, index)
    }

    // ---------- MaskRect 纯逻辑 ----------

    @Test
    fun `normalized-坐标钳制到0到1且上下颠倒交换`() {
        // 越界输入：left=-0.5→0、right=2→1；top=1.5→1、bottom=-1→0，颠倒后交换
        val r = MaskRect(-0.5f, 1.5f, 2f, -1f).normalized()
        assertEquals(0f, r.left, 1e-6f)
        assertEquals(0f, r.top, 1e-6f)
        assertEquals(1f, r.right, 1e-6f)
        assertEquals(1f, r.bottom, 1e-6f)
    }

    @Test
    fun `normalized-左右或上下颠倒时交换`() {
        val r = MaskRect(0.8f, 0.1f, 0.2f, 0.9f).normalized()
        assertEquals(0.2f, r.left, 1e-6f)
        assertEquals(0.8f, r.right, 1e-6f)
        assertEquals(0.1f, r.top, 1e-6f)
        assertEquals(0.9f, r.bottom, 1e-6f)
    }

    @Test
    fun `isFull与isEmpty判定`() {
        assertTrue(MaskRect.FULL.isFull())
        assertTrue(MaskRect(0f, 0f, 1f, 1f).isFull())
        assertFalse(MaskRect(0.1f, 0.1f, 0.9f, 0.9f).isFull())
        assertTrue(MaskRect(0.3f, 0.3f, 0.3f, 0.9f).isEmpty())   // 零宽
        assertTrue(MaskRect(0.3f, 0.5f, 0.9f, 0.5f).isEmpty())   // 零高
        assertFalse(MaskRect(0.3f, 0.3f, 0.9f, 0.9f).isEmpty())
    }

    @Test
    fun `fromPixels-像素转归一化`() {
        // 1000x2000 图上框选 (100,200)-(900,1200)
        val r = MaskRect.fromPixels(100f, 200f, 900f, 1200f, 1000, 2000)
        assertEquals(0.1f, r.left, 1e-4f)
        assertEquals(0.1f, r.top, 1e-4f)
        assertEquals(0.9f, r.right, 1e-4f)
        assertEquals(0.6f, r.bottom, 1e-4f)
    }

    @Test
    fun `fromPixels-非法尺寸回退整图`() {
        assertEquals(MaskRect.FULL, MaskRect.fromPixels(1f, 1f, 2f, 2f, 0, 0))
        assertEquals(MaskRect.FULL, MaskRect.fromPixels(1f, 1f, 2f, 2f, -5, 10))
    }

    // ---------- MaskSelectionState 状态机 ----------

    @Test
    fun `框选状态机-进入拖动取消`() {
        val idle = MaskSelectionState()
        assertNull(idle.committed)
        assertEquals(MaskRect.FULL, idle.effective())   // 未自定义 → 整图

        val selecting = idle.enter()
        assertTrue(selecting.selecting)

        val dragged = selecting.update(MaskRect(0.2f, 0.2f, 0.8f, 0.8f))
        assertEquals(0.8f, dragged.current!!.right, 1e-6f)

        val cancelled = dragged.cancel()
        assertFalse(cancelled.selecting)
        assertNull(cancelled.committed)
        assertEquals(MaskRect.FULL, cancelled.effective())
    }

    @Test
    fun `框选状态机-提交定稿与生效遮罩`() {
        val committed = MaskSelectionState()
            .enter()
            .update(MaskRect(0.2f, 0.2f, 0.8f, 0.8f))
            .commit()
        assertFalse(committed.selecting)
        assertEquals(MaskRect(0.2f, 0.2f, 0.8f, 0.8f), committed.committed)
        assertEquals(committed.committed, committed.effective())
    }

    @Test
    fun `框选状态机-空矩形提交视为取消`() {
        val s = MaskSelectionState()
            .enter()
            .update(MaskRect(0.5f, 0.5f, 0.5f, 0.9f))   // 零宽
            .commit()
        assertFalse(s.selecting)
        assertNull(s.committed)
    }

    @Test
    fun `框选状态机-非框选态操作被忽略`() {
        val s = MaskSelectionState()
        assertEquals(s, s.update(MaskRect(0.1f, 0.1f, 0.9f, 0.9f)))
        assertEquals(s, s.commit())
    }

    // ---------- MaskJson 序列化 ----------

    @Test
    fun `序列化往返`() {
        val r = MaskRect(0.1f, 0.2f, 0.8f, 0.9f)
        assertEquals(r, MaskJson.parse(MaskJson.serialize(r)))
    }

    @Test
    fun `解析损坏JSON-返回null不崩`() {
        assertNull(MaskJson.parse("{bad json"))
        assertNull(MaskJson.parse(""))
    }

    // ---------- MaskStore 持久化 ----------

    @Test
    fun `保存读取往返-进程杀后不丢`() = runTest {
        store.create(makeMeta(id = "m1"), img, "题干").getOrThrow()
        assertTrue(maskStore.save("m1", MaskRect(0.1f, 0.2f, 0.7f, 0.8f)))
        assertEquals(MaskRect(0.1f, 0.2f, 0.7f, 0.8f), maskStore.load("m1"))

        // 模拟进程被杀：新 index 从磁盘重建
        val index2 = LibraryIndex()
        index2.rebuild(root)
        val maskStore2 = MaskStore(root, index2)
        assertEquals(MaskRect(0.1f, 0.2f, 0.7f, 0.8f), maskStore2.load("m1"))
    }

    @Test
    fun `保存时归一化-越界值落盘前钳制`() = runTest {
        store.create(makeMeta(id = "m2"), img, "题干").getOrThrow()
        maskStore.save("m2", MaskRect(-1f, 0f, 2f, 1f))
        assertEquals(MaskRect(0f, 0f, 1f, 1f), maskStore.load("m2"))
    }

    @Test
    fun `无文件或条目不存在-返回null或false`() = runTest {
        assertNull(maskStore.load("no-such"))      // 条目不存在
        assertFalse(maskStore.save("no-such", MaskRect.FULL))
        store.create(makeMeta(id = "m3"), img, "题干").getOrThrow()
        assertNull(maskStore.load("m3"))           // 从未保存
    }

    @Test
    fun `clear-删除自定义遮罩`() = runTest {
        store.create(makeMeta(id = "m4"), img, "题干").getOrThrow()
        maskStore.save("m4", MaskRect(0.1f, 0.1f, 0.5f, 0.5f))
        assertTrue(maskStore.clear("m4"))
        assertNull(maskStore.load("m4"))
        assertFalse(maskStore.clear("no-such"))   // 条目不存在 → false 不抛
    }

    @Test
    fun `损坏mask文件-加载回退null`() = runTest {
        store.create(makeMeta(id = "m5"), img, "题干").getOrThrow()
        val entry = index.get("m5")!!
        File(File(File(root, "数学"), entry.dirName), MaskStore.MASK_FILE_NAME).writeText("{bad")
        assertNull(maskStore.load("m5"))
    }
}
