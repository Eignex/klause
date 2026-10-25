package com.eignex.klause.backtrack.selector

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaxSdTest {
    @Test
    fun `bounded probing should rank by global propagation when counters decline`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)),
        )
        val session = PropagationSession(problem)

        val values = MaxSd(probeWorkLimit = 10_000L).fresh()
            .values(session, VarRef.IntVar(0), Random(0L)).toList()

        assertEquals(listOf(0L, 1L, 2L), values)
        assertEquals(3L, session.valueOrdering.probes)
        assertEquals(0L, session.valueOrdering.supportOrders)
    }

    @Test
    fun `a local probe cap should restore domains and retain every interrupted alternative`() {
        for (limit in listOf(0L, 1L, 3L)) {
            val problem = Problem(
                0, 3, Array(3) { IntDomain(0, 2) },
                arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
            )
            val session = PropagationSession(problem)
            val before = (0..2).map { session.intDomain(it) }

            val values = MaxSd(probeWorkLimit = limit).values(session, VarRef.IntVar(0), Random(0L)).toSet()

            assertEquals(setOf(0L, 1L, 2L), values)
            assertEquals(before, (0..2).map { session.intDomain(it) })
            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
            assertFalse(session.probeCancelled)
            assertEquals(1L, session.valueOrdering.cappedOrders)
        }
    }

    @Test
    fun `bounded completed refutations should remove only their infeasible alternatives`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2),
                AllDifferent(intArrayOf(1, 2), domainMin = 0, domainSize = 2),
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 2),
            ),
        )
        val session = PropagationSession(problem)

        val values = MaxSd(probeWorkLimit = 10_000L).values(session, VarRef.IntVar(0), Random(0L)).toList()

        assertTrue(values.isEmpty())
        assertEquals(2L, session.valueOrdering.refutations)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `capped ordering should leave subsequent decisions equivalent to a fresh session`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val session = PropagationSession(problem)
        val fresh = PropagationSession(problem)
        MaxSd(probeWorkLimit = 1L).values(session, VarRef.IntVar(0), Random(0L)).toList()

        session.pinInt(0, 0L)
        fresh.pinInt(0, 0L)

        assertEquals((0..2).map { fresh.intDomain(it) }, (0..2).map { session.intDomain(it) })
        assertEquals(fresh.decisionLevel, session.decisionLevel)
        assertFalse(session.fixpointCancelled)
    }

    @Test
    fun `a local probe cap should preserve an external cancellation`() {
        var cancelled = false
        val session = PropagationSession(
            Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray<Factor>()),
            cancellation = Cancellation { cancelled },
        )
        cancelled = true

        MaxSd(probeWorkLimit = 0L).values(session, VarRef.IntVar(0), Random(0L)).toList()

        assertTrue(session.probeCancelled)
    }

    @Test
    fun `support discovery should observe cancellation before an unsupported fallback`() {
        var cancelled = false
        val cancellation = object : Cancellation {
            override fun isCancelled() = cancelled
            override fun workMeter(): WorkMeter = WorkMeter { }
        }
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3)),
        )
        val session = PropagationSession(problem, cancellation = cancellation)
        cancelled = true

        val values = MaxSd(fallback = IndomainRandom).values(session, VarRef.IntVar(0), Random(0L)).toSet()

        assertTrue(session.probeCancelled)
        assertEquals(setOf(0L, 1L, 2L), values)
    }

    @Test
    fun `a cheap fallback should preserve unsupported alternatives without probes`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        val before = session.propagationCount
        val expected = (0L..3L).filter { it in session.intDomain(0) }.toSet()

        val values = MaxSd(fallback = IndomainRandom).fresh()
            .values(session, VarRef.IntVar(0), Random(0L)).toSet()

        assertEquals(expected, values)
        assertEquals(before, session.propagationCount)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `a cheap fallback should avoid probes after the support visit allowance declines`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1))),
        )
        val session = PropagationSession(problem)
        val before = session.propagationCount

        val values = MaxSd(fallback = IndomainRandom, maxSupportVisits = 1)
            .values(session, VarRef.IntVar(0), Random(0L)).toSet()

        assertEquals(setOf(0L, 1L), values)
        assertEquals(before, session.propagationCount)
    }

    @Test
    fun `MaxSd should rank equal mdd root edges by accepted assignments without probes`() {
        val states = intArrayOf(1, 2, 4, 1)
        val starts = intArrayOf(0, 6, 18, 39)
        val transitions = longArrayOf(
            0, 0, 0, 0, 1, 1,
            0, 0, 0, 0, 1, 1, 1, 0, 2, 1, 1, 3,
            0, 0, 0, 0, 1, 0, 1, 1, 0, 2, 0, 0, 2, 1, 0, 3, 0, 0, 3, 1, 0,
        )
        val accepting = intArrayOf(0)
        val problem = Problem(
            0, 6, Array(6) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Mdd(intArrayOf(0, 1, 2), states, starts, transitions, 0, accepting, 3),
                Mdd(intArrayOf(3, 4, 5), states, starts, transitions, 0, accepting, 3),
            ),
        )
        val session = PropagationSession(problem)
        val before = session.propagationCount

        val values = MaxSd().values(session, VarRef.IntVar(3), Random(0L)).toList()

        assertEquals(listOf(1L, 0L), values)
        assertEquals(before, session.propagationCount)
    }

    @Test
    fun `MaxSd should rank equal residual domain products by live table supports without probing`() {
        val rows = listOf(
            longArrayOf(0, 0, 0), longArrayOf(0, 0, 1), longArrayOf(0, 1, 0),
            longArrayOf(1, 0, 0), longArrayOf(1, 0, 1), longArrayOf(1, 1, 0), longArrayOf(1, 1, 1),
        )
        val tuples = LongArray(rows.size * 32 * 3) { rows[(it / 3) % rows.size][it % 3] }
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf<Factor>(Table(intArrayOf(0, 1, 2), tuples)))
        val session = PropagationSession(problem)
        val before = session.propagationCount

        val values = MaxSd().values(session, VarRef.IntVar(0), Random(0L)).toList()

        assertEquals(listOf(1L, 0L), values)
        assertEquals(before, session.propagationCount)
    }

    @Test
    fun `MaxSd should drop infeasible probe values`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        val values = MaxSd().values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertTrue(2 !in values, "MaxSd must drop infeasible value 2; got $values")
        assertEquals(setOf(0L, 1L, 3L), values.toSet())
    }

    @Test
    fun `MaxSd should restore the trail level after probing`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5)),
        )
        val session = PropagationSession(problem)
        val levelBefore = session.decisionLevel
        MaxSd().values(session, VarRef.IntVar(0), Random(11L)).toList()
        assertEquals(levelBefore, session.decisionLevel)
    }
}
