package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaxSdSelectorTest {

    @Test
    fun `MaxSd drops infeasible probe values just like Impact`() {
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
    fun `MaxSd order is the reverse of Impact when both probe the same domain`() {
        // Construct a problem where probe-induced post-products differ across values:
        // 4 vars, AllDifferent. Pinning v0 = 0 leaves {1,2,3} for the others; pinning
        // v0 = 1 leaves {0,2,3}; etc. All four candidates have identical post-product
        // (3 × 3 × 3 = 27), so Impact and MaxSd happen to return identical orders here.
        // We instead use a Linear constraint that creates asymmetric pruning per value.
        // Constraint v0 + v1 + v2 ≤ 6 over [0..4]^3. Pin v0=k tightens v1 + v2 ≤ 6 - k:
        // k=0..2 → domains stay [0..4] (size 25 product); k=3 → [0..3]^2 (size 16);
        // k=4 → [0..2]^2 (size 9). So MaxSd's first should be among {0,1,2} (largest
        // residual), Impact's first should be 4 (smallest residual).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1, 1),
                    vars = intArrayOf(0, 1, 2),
                    op = LinearOp.LE,
                    bound = 6,
                ),
            ),
        )
        val s1 = PropagationSession(problem)
        val impactOrder = Impact().values(s1, VarRef.IntVar(0), Random(0L)).toList()
        val s2 = PropagationSession(problem)
        val maxSdOrder = MaxSd().values(s2, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(
            impactOrder.size,
            maxSdOrder.size,
            "both heuristics should yield the same set; got $impactOrder vs $maxSdOrder",
        )
        assertTrue(
            maxSdOrder.first() in setOf(0L, 1L, 2L),
            "MaxSd should prefer a v0 ∈ {0,1,2} (largest residual); got ${maxSdOrder.first()}",
        )
        assertEquals(
            4L,
            impactOrder.first(),
            "Impact should prefer v0 = 4 (smallest residual); got ${impactOrder.first()}",
        )
    }

    @Test
    fun `MaxSd finds a solution in the engine`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5)),
        )
        val r = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                variableSelector = SmallestDomain,
                valueSelector = MaxSd(),
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0L..4L).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `MaxSd restores trail level after probing`() {
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
