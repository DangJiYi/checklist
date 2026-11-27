package local.checklist.data

/**
 * 完成规则的全部定义。刻意不 import 任何 Android API，也不碰数据库，
 * 于是可以在 JVM 上把每个分支都测一遍——规则出错的代价最高。
 *
 * 三条不变量：
 *  1. locked 是派生值，永远等于 isLocked(status, allowEditCompleted)，不是独立真相。
 *  2. 编辑已完成表时，状态只在 completed_full / completed_partial 之间迁移，绝不回到 active。
 *     （回到 active 会让它重新出现在"进行中"列表并再次自动完成，逻辑会绕圈。）
 *  3. 手动完成是幂等的：已经是完成态再点一次，状态不变、也不刷新完成时间。
 */

enum class Status(val id: String) {
    ACTIVE("active"),
    COMPLETED_FULL("completed_full"),
    COMPLETED_PARTIAL("completed_partial");

    val isCompleted: Boolean get() = this != ACTIVE

    companion object {
        fun of(id: String?): Status = entries.firstOrNull { it.id == id } ?: ACTIVE
    }
}

enum class Action { TOGGLE, MANUAL_COMPLETE }

data class Outcome(
    val status: Status,
    val completedAt: Long?,
    val editedAt: Long?,
    val locked: Boolean
)

sealed class StateResult {
    data class Ok(val outcome: Outcome) : StateResult()
    data class Rejected(val code: String, val message: String) : StateResult()
}

object StateMachine {

    fun isLocked(status: Status, allowEditCompleted: Boolean): Boolean =
        status.isCompleted && !allowEditCompleted

    /**
     * @param checkedCount 变更**之后**的已勾选数
     * @param totalCount   检查项总数
     * @param completedAt  记录里已有的完成时间（可能为 null）
     */
    fun apply(
        action: Action,
        status: Status,
        checkedCount: Int,
        totalCount: Int,
        completedAt: Long?,
        now: Long,
        allowEditCompleted: Boolean
    ): StateResult {
        if (totalCount <= 0) {
            return StateResult.Rejected("EMPTY", "这张检查表还没有任何检查项")
        }
        val checked = checkedCount.coerceIn(0, totalCount)
        val allDone = checked >= totalCount

        return when (action) {
            Action.TOGGLE -> if (status == Status.ACTIVE) {
                if (allDone) {
                    ok(Status.COMPLETED_FULL, now, null, allowEditCompleted)
                } else {
                    ok(Status.ACTIVE, null, null, allowEditCompleted)
                }
            } else {
                if (!allowEditCompleted) {
                    return StateResult.Rejected(
                        "LOCKED",
                        "该检查表已完成并锁定；如需修改，请先在设置里打开“允许修改已完成表”"
                    )
                }
                val next = if (allDone) Status.COMPLETED_FULL else Status.COMPLETED_PARTIAL
                ok(next, completedAt ?: now, now, allowEditCompleted)
            }

            Action.MANUAL_COMPLETE -> when {
                status.isCompleted -> ok(status, completedAt, null, allowEditCompleted)
                allDone -> ok(Status.COMPLETED_FULL, now, null, allowEditCompleted)
                else -> ok(Status.COMPLETED_PARTIAL, now, null, allowEditCompleted)
            }
        }
    }

    private fun ok(
        status: Status,
        completedAt: Long?,
        editedAt: Long?,
        allowEditCompleted: Boolean
    ): StateResult = StateResult.Ok(
        Outcome(
            status = status,
            completedAt = completedAt,
            editedAt = editedAt,
            locked = isLocked(status, allowEditCompleted)
        )
    )
}
