package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.lp.CpToLpRelaxation
import com.eignex.klause.solver.lp.DualSimplex
import com.eignex.klause.solver.lp.LpExplanation
import com.eignex.klause.solver.lp.LpStatus
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #247: learn a clause from an infeasible node LP (Farkas certificate → bound-atom nogood). */
class LpLearnTest {

    @Test
    fun `infeasible node lp yields a bound-atom nogood`() {
        // x in [2,5] with x <= 1: the LP is infeasible and the load-bearing reason is x's lower
        // bound, so the Farkas certificate names x and the clause is the single literal ¬(x ≥ 2).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0)))
            .build(session)
        val solution = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.INFEASIBLE, solution.status)
        assertTrue(0 in solution.certCols, "x's seated bound must be in the certificate")

        val clause = LpExplanation.infeasibilityClause(relaxation, solution, session)
        assertNotNull(clause, "an infeasible LP must produce a Farkas explanation clause")
        assertEquals(listOf(session.boundGeLit(0, 2, positive = false)), clause.toList())
    }

    @Test
    fun `constraint-only infeasibility produces no node nogood`() {
        // Three pairwise covers force Σx ≥ 3, contradicting Σx ≤ 2 — infeasible regardless of any
        // branch. The dual ray is over constraint rows alone, so there is no bound to blame: null.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 2),
            ),
        )
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 0)))
            .build(session)
        val solution = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.INFEASIBLE, solution.status)
        assertNull(LpExplanation.infeasibilityClause(relaxation, solution, session))
    }

    @Test
    fun `lp learning preserves the optimum`() {
        // minimize Σx s.t. Σx ≥ 5 over [0,2]^3; optimum 5. Branching that drives the reachable sum
        // below 5 makes the node LP infeasible, exercising the learned-clause path under restarts.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val baseline = BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 3L, lpBounding = true))
        val learned = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 3L, lpBounding = true, lpLearn = true, lubyRestartBase = 8L),
        )
        assertTrue(baseline is MinimizeResult.Optimal)
        assertTrue(learned is MinimizeResult.Optimal, "lp-learning run must still prove optimality")
        assertEquals(5.0, baseline.objectiveValue)
        assertEquals(5.0, learned.objectiveValue)
    }
}
