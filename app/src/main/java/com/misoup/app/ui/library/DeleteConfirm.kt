package com.misoup.app.ui.library

/**
 * 删除双确认状态机（T08）："删除后不可恢复"需要两次确认。
 *
 * 状态迁移（事件驱动，纯逻辑可单测）：
 * ```
 * Idle --request--> FirstConfirm --cancel--> Idle
 * FirstConfirm --confirm--> SecondConfirm --cancel--> Idle
 * SecondConfirm --confirm--> Deleting --success--> Done
 * Deleting --fail(msg)--> Error(msg) --reset--> Idle
 * ```
 * 非法事件（如 Idle 直接 confirm）忽略并保持原状态。
 */
sealed class DeleteConfirm {
    /** 未发起删除 */
    data object Idle : DeleteConfirm()
    /** 第一次确认：弹窗提示"将删除该题" */
    data object FirstConfirm : DeleteConfirm()
    /** 第二次确认：弹窗提示"删除后不可恢复" */
    data object SecondConfirm : DeleteConfirm()
    /** 正在删除（防重复点击） */
    data object Deleting : DeleteConfirm()
    /** 删除完成（详情页随后关闭） */
    data object Done : DeleteConfirm()
    /** 删除失败（保留错误信息供展示） */
    data class Error(val message: String) : DeleteConfirm()
}

/** 状态机事件 */
sealed class DeleteEvent {
    /** 用户点击删除按钮（第一次） */
    data object Request : DeleteEvent()
    /** 用户点击取消 */
    data object Cancel : DeleteEvent()
    /** 用户点击确认（第一次→第二次，第二次→执行） */
    data object Confirm : DeleteEvent()
    /** 底层删除成功 */
    data object Success : DeleteEvent()
    /** 底层删除失败 */
    data class Fail(val message: String) : DeleteEvent()
    /** 错误提示关闭后复位 */
    data object Reset : DeleteEvent()
}

object DeleteConfirmMachine {

    /** 事件驱动状态迁移；非法事件返回原状态 */
    fun next(current: DeleteConfirm, event: DeleteEvent): DeleteConfirm = when (current) {
        DeleteConfirm.Idle -> when (event) {
            DeleteEvent.Request -> DeleteConfirm.FirstConfirm
            else -> current
        }

        DeleteConfirm.FirstConfirm -> when (event) {
            DeleteEvent.Confirm -> DeleteConfirm.SecondConfirm
            DeleteEvent.Cancel -> DeleteConfirm.Idle
            else -> current
        }

        DeleteConfirm.SecondConfirm -> when (event) {
            DeleteEvent.Confirm -> DeleteConfirm.Deleting
            DeleteEvent.Cancel -> DeleteConfirm.Idle
            else -> current
        }

        DeleteConfirm.Deleting -> when (event) {
            DeleteEvent.Success -> DeleteConfirm.Done
            is DeleteEvent.Fail -> DeleteConfirm.Error(event.message)
            else -> current
        }

        DeleteConfirm.Done -> current

        is DeleteConfirm.Error -> when (event) {
            DeleteEvent.Reset -> DeleteConfirm.Idle
            else -> current
        }
    }
}
