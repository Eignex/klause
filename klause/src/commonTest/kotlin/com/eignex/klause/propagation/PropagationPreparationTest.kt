package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropagationPreparationTest {
    @Test
    fun `paused root propagation preserves deductions and pending wakes`() {
        val variables = 260
        val problem = Problem(
            0, variables, Array(variables) { IntDomain(0, 10) },
            Array(variables) { v ->
                if (v == variables - 1) {
                    Linear(intArrayOf(1), intArrayOf(v), LinearOp.LE, 3)
                } else {
                    Linear(intArrayOf(1, -1), intArrayOf(v, v + 1), LinearOp.LE, 0)
                }
            },
        )
        val expected = PropagationSession(problem)
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false)
        var session: PropagationSession? = null

        assertNull(preparation.advance())
        while (session == null) session = preparation.advance()

        assertTrue(preparation.work > 0L)
        assertEquals(expected.work, preparation.work)
        for (v in 0 until variables) assertEquals(expected.intDomain(v), session.intDomain(v))
        assertFalse(session.fixpointCancelled)
    }

    @Test
    fun `root conflict is published only after it is derived`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            Array(260) { v ->
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, if (v == 259) -1 else 10)
            },
        )
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false)
        var session: PropagationSession? = null

        assertNull(preparation.advance())
        while (session == null) session = preparation.advance()

        assertTrue(session.isUnsatAtRoot)
        assertEquals(PropagationSession(problem).work, session.work)
    }

    @Test
    fun `cancellation between preparation batches preserves resumable private progress`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        var cancelled = false
        val preparation = PropagationPreparation(problem, Cancellation { cancelled }, 0, false)
        preparation.advance()
        cancelled = true

        assertNull(preparation.advance())

        cancelled = false
        var session: PropagationSession? = null
        while (session == null) session = preparation.advance()
        assertEquals(IntDomain(0, 10), assertNotNull(session).intDomain(0))
    }

    @Test
    fun `cancellation before a root fire preserves the pending queue`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            Array(260) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3) },
        )
        var polls = 0
        var cancelOnPoll = Int.MAX_VALUE
        val preparation = PropagationPreparation(problem, Cancellation { ++polls >= cancelOnPoll }, 0, false)
        repeat(4) { assertNull(preparation.advance()) }
        cancelOnPoll = polls + 2

        assertNull(preparation.advance())
        assertEquals(0L, preparation.work)

        cancelOnPoll = Int.MAX_VALUE
        var session: PropagationSession? = null
        while (session == null) session = preparation.advance()
        assertEquals(PropagationSession(problem).work, session.work)
        assertEquals(IntDomain(0, 3), session.intDomain(0))
        assertFalse(session.fixpointCancelled)
    }
}
