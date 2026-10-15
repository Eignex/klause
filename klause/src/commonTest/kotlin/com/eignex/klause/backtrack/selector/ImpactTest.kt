package com.eignex.klause.backtrack.selector

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpactTest {
    @Test
    fun `a fired deadline should stop probing without dropping any value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem, Cancellation { true })
        val values = Impact(maxProbes = 8).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals((0L..5L).toSet(), values.toSet(), "every value must still be offered; got $values")
    }

    @Test
    fun `a deadline inside a probe fixpoint should leave values unranked and the session uncancelled`() {
        // A cut probe reverts to the previous level's complete fixpoint: truncating the ranking is fine,
        // losing a value is not.
        val session = probeCancellingSession()
        val values = Impact(maxProbes = 8).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(0L, 1L, 2L, 3L), values)
        assertTrue(!session.fixpointCancelled)
    }

    // The token fires on its fourth read: the loop poll and fixpoint of the first probe, the loop poll
    // of the second, then that second probe's fixpoint.
    private fun probeCancellingSession(): PropagationSession {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 3),
            ),
        )
        var armed = false
        var polls = 0
        val session = PropagationSession(problem, Cancellation { armed && polls++ >= 3 })
        armed = true
        return session
    }

    @Test
    fun `impact should drop infeasible probe values from the returned order`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        val values = Impact(maxProbes = 8)
            .values(session, VarRef.IntVar(0), Random(0L))
            .toList()
        assertTrue(2 !in values, "Impact should drop infeasible value 2; got $values")
        assertEquals(
            setOf(0L, 1L, 3L),
            values.toSet(),
            "Impact should yield exactly the feasible values; got $values",
        )
    }

    @Test
    fun `impact should cap probes on large domains and still cover the full domain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 99)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val values = Impact(maxProbes = 4)
            .values(session, VarRef.IntVar(0), Random(13L))
            .toList()
        assertEquals(100, values.size, "expected full coverage; got ${values.size}")
        assertEquals((0L..99L).toSet(), values.toSet())
    }

    @Test
    fun `a domain too wide to walk should still offer a value when every sampled probe is refuted`() {
        // `v0 = 2e9 * v1` refutes every random probe without refuting the domain.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4_000_000_000L), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -2_000_000_000), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
            ),
        )
        val session = PropagationSession(problem)
        val values = Impact(maxProbes = 4).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertTrue(values.isNotEmpty())
    }

    @Test
    fun `impact probes should not leak a stale pin on an infeasible value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        Impact().values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(0, session.decisionLevel)
        val r = session.pinInt(0, 1)
        assertTrue(r !is PropagationResult.Unsat, "post-probe pin must succeed; got $r")
    }
}
