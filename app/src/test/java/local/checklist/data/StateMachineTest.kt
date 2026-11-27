package local.checklist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完成规则的单测。这部分是纯 JVM 逻辑，不需要 Android，也不需要真机。
 *
 * 之所以盯得这么紧：锁定规则一旦算错，表现是"界面看起来正常，但数据已经错了"，
 * 靠手点界面几乎测不出来。
 */
class StateMachineTest {

    private val now = 1_700_000_000_000L

    private fun ok(r: StateResult): Outcome {
        assertTrue("期望成功，实际是 $r", r is StateResult.Ok)
        return (r as StateResult.Ok).outcome
    }

    private fun rejected(r: StateResult): StateResult.Rejected {
        assertTrue("期望被拒绝，实际是 $r", r is StateResult.Rejected)
        return r as StateResult.Rejected
    }

    // ---------------------------------------------------------- 基本约束

    @Test
    fun `空表不允许做任何事`() {
        val r = StateMachine.apply(
            Action.TOGGLE, Status.ACTIVE, 0, 0, null, now, false
        )
        assertEquals("EMPTY", rejected(r).code)
    }

    @Test
    fun `locked 永远是派生值`() {
        assertFalse(StateMachine.isLocked(Status.ACTIVE, false))
        assertFalse(StateMachine.isLocked(Status.ACTIVE, true))
        assertTrue(StateMachine.isLocked(Status.COMPLETED_FULL, false))
        assertTrue(StateMachine.isLocked(Status.COMPLETED_PARTIAL, false))
        assertFalse(StateMachine.isLocked(Status.COMPLETED_FULL, true))
        assertFalse(StateMachine.isLocked(Status.COMPLETED_PARTIAL, true))
    }

    // ---------------------------------------------------------- 进行中

    @Test
    fun `部分勾选仍是进行中`() {
        val o = ok(StateMachine.apply(Action.TOGGLE, Status.ACTIVE, 3, 5, null, now, false))
        assertEquals(Status.ACTIVE, o.status)
        assertNull(o.completedAt)
        assertFalse(o.locked)
    }

    @Test
    fun `全部勾选自动完成并锁定`() {
        val o = ok(StateMachine.apply(Action.TOGGLE, Status.ACTIVE, 5, 5, null, now, false))
        assertEquals(Status.COMPLETED_FULL, o.status)
        assertEquals(now, o.completedAt)
        assertTrue(o.locked)
    }

    @Test
    fun `开了开关时自动完成但不锁定`() {
        val o = ok(StateMachine.apply(Action.TOGGLE, Status.ACTIVE, 5, 5, null, now, true))
        assertEquals(Status.COMPLETED_FULL, o.status)
        assertFalse(o.locked)
    }

    // ---------------------------------------------------------- 锁定

    @Test
    fun `锁定后勾选被拒绝`() {
        val r = StateMachine.apply(
            Action.TOGGLE, Status.COMPLETED_FULL, 4, 5, now, now + 1, false
        )
        assertEquals("LOCKED", rejected(r).code)
    }

    @Test
    fun `部分完成锁定后同样拒绝`() {
        val r = StateMachine.apply(
            Action.TOGGLE, Status.COMPLETED_PARTIAL, 2, 5, now, now + 1, false
        )
        assertEquals("LOCKED", rejected(r).code)
    }

    // ---------------------------------------------------------- 修改已完成

    @Test
    fun `改已完成表时保留原完成时间并记录编辑时间`() {
        val o = ok(
            StateMachine.apply(
                Action.TOGGLE, Status.COMPLETED_FULL, 4, 5, now, now + 1000, true
            )
        )
        assertEquals(Status.COMPLETED_PARTIAL, o.status)
        assertEquals("完成时间不该被覆盖", now, o.completedAt)
        assertEquals(now + 1000, o.editedAt)
        assertFalse(o.locked)
    }

    @Test
    fun `改已完成后可以重新勾满`() {
        val o = ok(
            StateMachine.apply(
                Action.TOGGLE, Status.COMPLETED_PARTIAL, 5, 5, now, now + 1000, true
            )
        )
        assertEquals(Status.COMPLETED_FULL, o.status)
        assertEquals(now, o.completedAt)
    }

    @Test
    fun `已完成绝不允许退回 active`() {
        // 取消勾选后仍是完成态（部分完成），否则它会重新出现在进行中列表并再次自动完成
        val o = ok(
            StateMachine.apply(
                Action.TOGGLE, Status.COMPLETED_FULL, 0, 5, now, now + 1, true
            )
        )
        assertEquals(Status.COMPLETED_PARTIAL, o.status)
        assertTrue(o.status.isCompleted)
    }

    // ---------------------------------------------------------- 手动完成

    @Test
    fun `手动完成记录部分完成度`() {
        val o = ok(StateMachine.apply(Action.MANUAL_COMPLETE, Status.ACTIVE, 3, 5, null, now, false))
        assertEquals(Status.COMPLETED_PARTIAL, o.status)
        assertEquals(now, o.completedAt)
        assertTrue(o.locked)
    }

    @Test
    fun `手动完成且全勾时算全完成`() {
        val o = ok(StateMachine.apply(Action.MANUAL_COMPLETE, Status.ACTIVE, 5, 5, null, now, false))
        assertEquals(Status.COMPLETED_FULL, o.status)
        assertTrue(o.locked)
    }

    @Test
    fun `手动完成是幂等的`() {
        val o = ok(
            StateMachine.apply(
                Action.MANUAL_COMPLETE, Status.COMPLETED_PARTIAL, 3, 5, now, now + 999, false
            )
        )
        assertEquals(Status.COMPLETED_PARTIAL, o.status)
        assertEquals("重复点击不该刷新完成时间", now, o.completedAt)
        assertNull(o.editedAt)
    }

    @Test
    fun `全勾后重复手动完成不降级`() {
        val o = ok(
            StateMachine.apply(
                Action.MANUAL_COMPLETE, Status.COMPLETED_FULL, 5, 5, now, now + 999, false
            )
        )
        assertEquals(Status.COMPLETED_FULL, o.status)
        assertEquals(now, o.completedAt)
    }

    // ---------------------------------------------------------- 越界输入

    @Test
    fun `勾选数越界会被夹紧而不是误判`() {
        val o = ok(StateMachine.apply(Action.TOGGLE, Status.ACTIVE, 99, 5, null, now, false))
        assertEquals(Status.COMPLETED_FULL, o.status)

        val p = ok(StateMachine.apply(Action.TOGGLE, Status.ACTIVE, -3, 5, null, now, false))
        assertEquals(Status.ACTIVE, p.status)
    }
}
