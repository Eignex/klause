package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSearchRunTest {
    @Test
    fun `repair graph visits should use their own work price`() {
        val control = LocalSearchRunControl(Cancellation.Never)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        control.repairWork.charge(127_999L)
        assertFalse(control.shouldPause(0L))

        control.repairWork.charge(1L)

        assertTrue(control.shouldPause(0L))
        assertEquals(1L, control.auxiliaryWork)
        assertEquals(128_000L, control.repairVisits)
        assertEquals(128_000L, control.inspectionVisits)
    }

    @Test
    fun `fractional repair and scoring work should combine across slices`() {
        val control = LocalSearchRunControl(Cancellation.Never)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        control.charge(3_999L)
        control.repairWork.charge(31L)
        assertFalse(control.shouldPause(0L))

        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        control.repairWork.charge(1L)

        assertTrue(control.shouldPause(0L))
        assertEquals(1L, control.work)
        assertEquals(1L, control.auxiliaryWork)
        assertEquals(4_031L, control.inspectionVisits)
    }

    @Test
    fun `scheduled slices should retain the configured native instruction price`() {
        val control = LocalSearchRunControl(Cancellation.Never)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 10L, Long.MAX_VALUE, 7L, 1.5)

        assertFalse(control.shouldPause(9L))
        assertTrue(control.shouldPause(10L))
        assertEquals(6L, control.work)
    }

    @Test
    fun `inspection work should pause a node slice before its native instruction grant ends`() {
        val control = LocalSearchRunControl(Cancellation.Never)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 80L, Long.MAX_VALUE, 100L)

        control.charge(400_000L)

        assertTrue(control.shouldPause(1L))
        assertEquals(1L, control.instructions)
    }

    @Test
    fun `inspection overspend should be repaid before later node slices search`() {
        val control = LocalSearchRunControl(Cancellation.Never)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        control.charge(12_000L)
        assertTrue(control.shouldPause(0L))

        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        assertFalse(control.hasSliceAllowance)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        assertFalse(control.hasSliceAllowance)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)

        assertTrue(control.hasSliceAllowance)
        assertFalse(control.shouldPause(0L))
    }

    @Test
    fun `fractional inspection work should remain charged across node slices`() {
        val control = LocalSearchRunControl(Cancellation.Never)
        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        control.charge(3_999L)
        assertFalse(control.shouldPause(0L))

        control.offer(Cancellation.Never, Long.MAX_VALUE, 1L, Long.MAX_VALUE, 1L)
        control.charge(1L)

        assertTrue(control.shouldPause(0L))
        assertEquals(1L, control.work)
    }

    @Test
    fun `native instruction slices should retain their grant despite inspection work`() {
        for (repair in listOf(false, true)) {
            val control = LocalSearchRunControl(Cancellation.Never)
            control.offer(Cancellation.Never, Long.MAX_VALUE, 2L, Long.MAX_VALUE)

            if (repair) control.repairWork.charge(256_000L) else control.charge(20_000L)

            assertFalse(control.shouldPause(1L))
            assertTrue(control.shouldPause(2L))
            assertEquals(2L, control.instructions)
        }
    }

    @Test
    fun `an incumbent consumer failure should close the walk and release the solver`() {
        val solver = LocalSearchSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())
        val params = LocalSearchParams(maxFlips = 1L, randomSeed = 7L)
        val walk = solver.resumable(LinearObjective(), params)

        assertFailsWith<IllegalArgumentException> {
            walk.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { throw IllegalArgumentException("consumer") }
        }

        val next = solver.resumableSolve(params)
        walk.close()
        assertFailsWith<IllegalStateException> { solver.resumableSolve(params) }
        assertFailsWith<IllegalStateException> {
            walk.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}
        }
        assertIs<SolveResult.Sat>(next.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
        next.close()
    }
}
