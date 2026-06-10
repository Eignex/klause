package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cumulative
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

    @Test
    fun `immediate lp backjump fires without restarts and preserves the optimum`() {
        // A genuinely LP-only infeasibility, so the LP path (not CP propagation) is what fails the
        // node. var0 = s (the objective, minimised); var1..3 in [0,2]. Three pairwise covers force
        // var1+var2+var3 >= 4.5 — a bound only a *combination* of constraints gives, which per-factor
        // bound propagation cannot derive — while R4 (var1+var2+var3 - s <= 4) ties the surplus to s.
        // Pinning s = 0 leaves the LP infeasible (4.5 > 4) yet bound-consistent (every var stays in
        // [1,2]), so CP cannot fail the node — only the LP can, via a Farkas certificate that names
        // s's seated upper bound. With lpLearn on and NO restarts, the only way that infeasibility
        // shortens the search is the immediate Farkas backjump (#280); the restart-flush pool (#247)
        // never drains without a restart. The optimum is s = 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1, 1, -1), intArrayOf(1, 2, 3, 0), LinearOp.LE, 4),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0))
        val result = BacktrackSolver(problem).minimize(
            obj,
            // InputOrder + IndomainMin branch s first and try s = 0 first, hitting the LP-infeasible node.
            BacktrackParams(
                randomSeed = 1L,
                lpBounding = true,
                lpLearn = true,
                variableHeuristic = InputOrder,
                valueHeuristic = IndomainMin,
            ),
        )
        assertTrue(result is MinimizeResult.Optimal, "immediate-backjump run must prove optimality")
        assertEquals(1.0, result.objectiveValue)
        assertTrue(
            result.stats.lpBackjumps.sum > 0.0,
            "the immediate LP backjump should fire (got ${result.stats.lpBackjumps.sum})",
        )
    }

    @Test
    fun `energetic learning preserves the optimum`() {
        // 4 disjunctive tasks (length 3, capacity 1) over [0,11]; minimize Σ start. The only feasible
        // arrangement spaces them ≥3 apart → optimum 0+3+6+9 = 18. Branches that pack starts create
        // over-subscribed windows, so energetic learning runs; the optimum must be preserved.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 11) },
            factors = arrayOf<Factor>(
                Cumulative(intArrayOf(0, 1, 2, 3), intArrayOf(3, 3, 3, 3), intArrayOf(1, 1, 1, 1), capacity = 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1))
        val baseline = BacktrackSolver(problem)
            .minimize(obj, BacktrackParams(randomSeed = 2L, energeticReasoning = true))
        val learned = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 2L, energeticReasoning = true, lpLearn = true, lubyRestartBase = 12L),
        )
        assertTrue(baseline is MinimizeResult.Optimal)
        assertTrue(learned is MinimizeResult.Optimal, "energetic-learning run must still prove optimality")
        assertEquals(18.0, baseline.objectiveValue)
        assertEquals(18.0, learned.objectiveValue)
    }
}
