package com.misoup.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T08 删除双确认状态机：正常路径 / 取消 / 失败 / 非法事件 */
class DeleteConfirmMachineTest {

    @Test
    fun `正常路径-两次确认后删除成功`() {
        var state: DeleteConfirm = DeleteConfirm.Idle
        state = DeleteConfirmMachine.next(state, DeleteEvent.Request)
        assertEquals(DeleteConfirm.FirstConfirm, state)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Confirm)
        assertEquals(DeleteConfirm.SecondConfirm, state)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Confirm)
        assertEquals(DeleteConfirm.Deleting, state)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Success)
        assertEquals(DeleteConfirm.Done, state)
    }

    @Test
    fun `任一步取消-回到 Idle`() {
        // 第一次确认后取消
        var state = DeleteConfirmMachine.next(DeleteConfirm.Idle, DeleteEvent.Request)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Cancel)
        assertEquals(DeleteConfirm.Idle, state)
        // 第二次确认后取消
        state = DeleteConfirmMachine.next(DeleteConfirm.Idle, DeleteEvent.Request)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Confirm)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Cancel)
        assertEquals(DeleteConfirm.Idle, state)
    }

    @Test
    fun `删除失败-进入 Error-复位回 Idle`() {
        var state: DeleteConfirm = DeleteConfirm.Idle
        state = DeleteConfirmMachine.next(state, DeleteEvent.Request)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Confirm)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Confirm)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Fail("磁盘错误"))
        assertEquals(DeleteConfirm.Error("磁盘错误"), state)
        state = DeleteConfirmMachine.next(state, DeleteEvent.Reset)
        assertEquals(DeleteConfirm.Idle, state)
    }

    @Test
    fun `非法事件-保持原状态`() {
        // Idle 直接确认/取消无效
        assertEquals(DeleteConfirm.Idle, DeleteConfirmMachine.next(DeleteConfirm.Idle, DeleteEvent.Confirm))
        assertEquals(DeleteConfirm.Idle, DeleteConfirmMachine.next(DeleteConfirm.Idle, DeleteEvent.Cancel))
        // FirstConfirm 阶段 Success/Fail 无效
        val first = DeleteConfirmMachine.next(DeleteConfirm.Idle, DeleteEvent.Request)
        assertEquals(first, DeleteConfirmMachine.next(first, DeleteEvent.Success))
        // Done 之后任何事件无效
        val done = DeleteConfirmMachine.next(DeleteConfirm.Deleting, DeleteEvent.Success)
        assertEquals(done, DeleteConfirmMachine.next(done, DeleteEvent.Request))
        assertEquals(done, DeleteConfirmMachine.next(done, DeleteEvent.Confirm))
    }

    @Test
    fun `Deleting 阶段不可再确认-防重复点击`() {
        val deleting = DeleteConfirmMachine.next(DeleteConfirm.SecondConfirm, DeleteEvent.Confirm)
        assertEquals(DeleteConfirm.Deleting, deleting)
        // 重复 Confirm 不回到 First/继续推进
        assertEquals(DeleteConfirm.Deleting, DeleteConfirmMachine.next(deleting, DeleteEvent.Confirm))
        assertEquals(DeleteConfirm.Deleting, DeleteConfirmMachine.next(deleting, DeleteEvent.Request))
    }
}
