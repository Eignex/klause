package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropagationPreparationTest {
    @Test
    fun `prepared root attribution survives publication without adding propagation work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)))
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false, attribution = true)
        var session: PropagationSession? = null
        while (session == null) session = preparation.advance()

        assertEquals(PropagationSession(problem).work, preparation.work)
        assertEquals(preparation.learning(), session.learnedLifecycle())
        assertTrue(session.learnedLifecycle().getValue("factorLinearFires") > 0)
        assertEquals(IntDomain(0, 3), session.intDomain(0))
    }

    @Test
    fun `a prepared seed conflict should retain its certified named prefix`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        ))
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false)
        var session: PropagationSession? = null
        while (session == null) session = preparation.advance()
        val assumptions = Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false))

        val result = session.prepareSeed(assumptions).asSequence().filterNotNull().last()

        assertEquals(listOf(1, 2, 3), assertIs<PropagationResult.Unsat>(result).conflictLevels.toList())
    }

    @Test
    fun `paused root propagation should preserve deductions and pending wakes`() {
        val variables = 260
        val problem = Problem(
            0,
            variables,
            Array(variables) { IntDomain(0, 10) },
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
    fun `a root conflict should be published only after it is derived`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
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
    fun `cancellation between preparation batches should preserve resumable progress`() {
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
    fun `cancellation before a root fire should preserve the pending queue`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            Array(260) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3) },
        )
        var polls = 0
        var cancelOnPoll = Int.MAX_VALUE
        val preparation = PropagationPreparation(problem, Cancellation { ++polls >= cancelOnPoll }, 0, false)
        while (!preparation.rootStarted) assertNull(preparation.advance())
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

    @Test
    fun `a wide factor occurrence index should match eager wakeups`() {
        val variables = 513
        val problem = Problem(
            1,
            variables,
            Array(variables) { IntDomain(0, 1) },
            arrayOf(
                Linear(IntArray(variables) { 1 }, IntArray(variables) { it }, LinearOp.GE, 1),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val expected = PropagationSession(problem)
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false)
        var session: PropagationSession? = null

        while (session == null) session = preparation.advance()
        session.pinInt(0, 0)
        expected.pinInt(0, 0)

        assertEquals(expected.work, session.work)
        assertEquals(expected.boolValue(0), session.boolValue(0))
        for (v in 0 until variables) assertEquals(expected.intDomain(v), session.intDomain(v))
    }

    @Test
    fun `an assumption fixpoint should pause and resume without restarting its decision`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            Array(260) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1) },
        )
        var cancelled = false
        val preparation = PropagationPreparation(problem, Cancellation { cancelled }, 0, false)
        var ready: PropagationSession? = null
        while (ready == null) ready = preparation.advance()
        val session = assertNotNull(ready)
        val assumptions = Assumptions.None.withInt(0, 1)
        val seed = session.prepareSeed(assumptions)
        val expected = PropagationSession(problem)
        expected.seed(assumptions)

        assertNull(seed.next())
        cancelled = true
        assertNull(seed.next())
        cancelled = false
        do {
            val step = seed.next()
        } while (step == null)

        assertEquals(1, session.decisionLevel)
        assertEquals(expected.work, session.work)
        assertEquals(expected.intDomain(0), session.intDomain(0))
        session.popLast()
        assertEquals(IntDomain(0, 1), session.intDomain(0))
    }

    @Test
    fun `assumptions without wakes should remain pausable between pins`() {
        val problem = Problem(0, 260, Array(260) { IntDomain(0, 1) }, emptyArray())
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false)
        var ready: PropagationSession? = null
        while (ready == null) ready = preparation.advance()
        val session = assertNotNull(ready)
        val ints = Assumptions(intArrayOf(), booleanArrayOf(), IntArray(260) { it }, LongArray(260))
        val seed = session.prepareSeed(ints)

        assertNull(seed.next())
        assertEquals(1, session.decisionLevel)
        do {
            val step = seed.next()
        } while (step == null)

        assertEquals(260, session.decisionLevel)
        assertTrue((0 until 260).all { session.intDomain(it) == IntDomain(0, 0) })
    }

    @Test
    fun `a boolean assumption should preserve watched wakes across a pause`() {
        val problem = Problem(
            261,
            0,
            emptyArray(),
            Array(260) { Clause(intArrayOf(Lit.make(0, false), Lit.make(it + 1, true))) },
        )
        val preparation = PropagationPreparation(problem, Cancellation.Never, 0, false)
        var ready: PropagationSession? = null
        while (ready == null) ready = preparation.advance()
        val session = assertNotNull(ready)
        val assumptions = Assumptions.None.withBool(0, true)
        val seed = session.prepareSeed(assumptions)
        val expected = PropagationSession(problem, nativeSat = false)
        expected.seed(assumptions)

        assertNull(seed.next())
        do {
            val step = seed.next()
        } while (step == null)

        assertEquals(1, session.decisionLevel)
        assertEquals(expected.work, session.work)
        for (v in 0 until 261) assertEquals(expected.boolValue(v), session.boolValue(v))
        session.popLast()
        assertNull(session.boolValue(0))
        assertNull(session.boolValue(260))
    }
}
