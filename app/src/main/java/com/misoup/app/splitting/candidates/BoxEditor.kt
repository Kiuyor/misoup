package com.misoup.app.splitting.candidates

/**
 * T03 拆题页框编辑纯逻辑（拖拽/新增/删除/合并/撤销全部），JVM 可测。
 *
 * 状态模型：
 * - [boxes] 当前候选框列表（原图坐标）
 * - [selected] 选中框下标（null = 未选中）
 * - [initial] 进入页面时的初始框快照（「撤销全部」恢复目标）
 */
class BoxEditor(
    initial: List<BoundingBox>,
) {
    var boxes: List<BoundingBox> = initial.toList()
        private set
    var selected: Int? = null
        private set

    /** 撤销全部：恢复初始快照（T05 提交前可撤销全部） */
    fun undoAll() {
        boxes = initialBoxes
        selected = null
    }

    private val initialBoxes: List<BoundingBox> = initial.toList()

    fun select(index: Int) {
        selected = index.takeIf { it in boxes.indices }
    }

    /** 命中测试：返回包含 (x, y) 的最上层框下标（绘制顺序 = 列表顺序，后画者在上）；未命中 → null */
    fun pickAt(x: Float, y: Float): Int? =
        boxes.indices.lastOrNull { i ->
            val b = boxes[i]
            x >= b.left && x <= b.right && y >= b.top && y <= b.bottom
        }

    /** 拖拽选中框（像素位移；未选中/越界忽略） */
    fun moveSelected(dx: Float, dy: Float) {
        val i = selected ?: return
        if (i !in boxes.indices) return
        val b = boxes[i]
        val moved = BoundingBox(b.left + dx, b.top + dy, b.width, b.height)
        boxes = boxes.toMutableList().also { it[i] = moved }
    }

    /** 新增空框（页面中心附近；不选中既有框时用） */
    fun addBox(pageWidth: Float, pageHeight: Float, size: Float = DEFAULT_NEW_BOX_SIZE) {
        val s = size.coerceAtLeast(1f)
        val x = (pageWidth - s) / 2f
        val y = (pageHeight - s) / 2f
        val added = BoundingBox(x, y, s, s)
        boxes = boxes + added
        selected = boxes.lastIndex
    }

    /** 删除选中框（未选中忽略） */
    fun deleteSelected() {
        val i = selected ?: return
        if (i !in boxes.indices) return
        boxes = boxes.filterIndexed { idx, _ -> idx != i }
        selected = null
    }

    /**
     * 合并选中框与「交集面积最大」的框（T03 合并手势）。
     * 无重叠框 → 不操作。合并结果放在原最小下标处。
     * @return 合并后的下标（null = 未合并）
     */
    fun mergeSelected(): Int? {
        val i = selected ?: return null
        if (i !in boxes.indices) return null
        val target = pickMergeTarget(boxes, i) ?: return null
        val merged = boxes[i].union(boxes[target])
        val minIdx = minOf(i, target)
        val keep = boxes.filterIndexed { idx, _ -> idx != i && idx != target }
        val out = keep.toMutableList()
        out.add(minIdx, merged)
        boxes = out
        selected = minIdx
        return minIdx
    }

    companion object {
        const val DEFAULT_NEW_BOX_SIZE = 100f

        /** 选目标：与 [index] 框交集面积最大的框（排除自身）；无交集 → null */
        fun pickMergeTarget(boxes: List<BoundingBox>, index: Int): Int? {
            if (index !in boxes.indices) return null
            val me = boxes[index]
            return boxes.indices
                .filter { it != index }
                .maxByOrNull { boxes[it].intersectionArea(me) }
                ?.takeIf { boxes[it].intersectionArea(me) > 0f }
        }
    }
}
