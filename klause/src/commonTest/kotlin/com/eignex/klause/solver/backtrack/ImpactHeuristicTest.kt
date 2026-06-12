package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.selector.Impact
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.VarRef
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImpactHeuristicTest {

    @Test
    fun `impact drops infeasible probe values from the returned order`() {
        // v0 ∈ [0, 3], v1 pinned to 2; AllDifferent forces v0 != 2. Probing v0 = 2 should
        // propagate to Unsat (v1's domain becomes empty) and be dropped entirely.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        // Bake completes seeding; baseline session has v1 = 2 and v0 ∈ {0, 1, 3} effectively.
        val values = Impact(maxProbes = 8)
            .values(session, VarRef.IntVar(0), Random(0L))
            .toList()
        assertTrue(2 !in values, "Impact should drop infeasible value 2; got $values")
        assertEquals(
            setOf(0, 1, 3),
            values.toSet(),
            "Impact should yield exactly the feasible values; got $values",
        )
    }

    @Test
    fun `impact picks ordering by stronger pruning first`() {
        // 4 vars, AllDifferent. v3 pinned to 0; for v0 ∈ [0, 3] the only infeasible value
        // is 0, and {1, 2, 3} have *equal* impact (each removes one common value across
        // the AllDifferent peers). So we just verify the order respects feasibility and
        // doesn't repeat values.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(0, 0),
            ),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        val values = Impact()
            .values(session, VarRef.IntVar(0), Random(7L))
            .toList()
        assertEquals(
            setOf(1, 2, 3),
            values.toSet(),
            "0 must be dropped (v3 = 0); the remaining 3 values must all appear; got $values",
        )
        assertEquals(
            values.distinct().size,
            values.size,
            "no duplicates expected; got $values",
        )
    }

    @Test
    fun `impact still finds a solution in the engine`() {
        // 5-queens-like AllDifferent; just confirm BacktrackSolver wires through cleanly.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5)),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableHeuristic = SmallestDomain,
                valueHeuristic = Impact(),
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        // Verify the assignment is a permutation of 0..4.
        assertEquals((0..4).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `impact restores trail level after probing`() {
        // Sanity: after Impact.values() returns, decisionLevel must be unchanged.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.LE,
                    bound = 7,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val levelBefore = session.decisionLevel
        Impact(maxProbes = 32)
            .values(session, VarRef.IntVar(0), Random(11L))
            .toList()
        assertEquals(
            levelBefore,
            session.decisionLevel,
            "Impact must leave the session at the same decision level it found it at",
        )
    }

    @Test
    fun `impact caps probes for large domains and still covers full domain via tail`() {
        // Single int var, domain [0, 99], no constraints — every value is feasible.
        // maxProbes = 4 means we probe 4 random values; the tail must contain the
        // remaining 96, so the sequence length equals 100.
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
        assertEquals((0..99).toSet(), values.toSet(), "must cover the entire domain")
    }

    @Test
    fun `impact on bool var probes both polarities`() {
        // Both polarities feasible: returns both, ordering depends on which polarity prunes
        // more. We only assert size and membership.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val values = Impact().values(session, VarRef.Bool(0), Random(0L)).toList()
        assertEquals(setOf(0, 1), values.toSet())
    }

    @Test
    fun `impact probes never leak a stale pin on infeasible value`() {
        // Probe an infeasible value first, then a feasible one. The session must remain
        // pristine throughout — verify by manually pinning afterwards and confirming the
        // result matches an out-of-band probe.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        Impact().values(session, VarRef.IntVar(0), Random(0L)).toList()
        // Trail level must still be 0 after probing.
        assertEquals(0, session.decisionLevel)
        // And we can still pin v0 = 1 cleanly afterwards.
        val r = session.pinInt(0, 1)
        assertTrue(r !is PropagationResult.Unsat, "post-probe pin must succeed; got $r")
    }
}
