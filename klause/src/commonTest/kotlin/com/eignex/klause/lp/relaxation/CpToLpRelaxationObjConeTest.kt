package com.eignex.klause.lp.relaxation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #571: the objective-cone / precedence-only sub-relaxation. A scheduling-shaped problem — a
 * precedence chain feeding a makespan objective, plus big-M disjunctive [ReifiedLinear] ordering
 * rows — is relaxed both fully and in cone mode. Cone mode must (a) drop every disjunctive big-M
 * row and the ordering bools they introduce, leaving a strictly smaller model, and (b) still return
 * a valid lower bound: the critical-path length. The bound being a relaxation, it can never exceed
 * the full bound nor the true optimum, which the end-to-end solve confirms.
 */
class CpToLpRelaxationObjConeTest {

    private val eps = 1e-9

    // int vars: 0,1,2 = task starts s0,s1,s2; 3 = makespan M. bools: 0,1 = disjunctive ordering aux.
    private fun problem(): Problem = Problem(
        numBoolVars = 2,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 20), IntDomain(0, 20), IntDomain(0, 40)),
        factors = arrayOf<Factor>(
            // Precedence chain: s1 ≥ s0+3, s2 ≥ s1+4, and the makespan link M ≥ s2+5.
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.GE, 4),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.GE, 5),
            // Big-M disjunctive ordering rows over the same starts — what cone mode drops.
            ReifiedLinear(
                auxBoolVar = 0,
                coeffs = intArrayOf(1, -1),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE,
                bound = -3,
            ),
            ReifiedLinear(
                auxBoolVar = 1,
                coeffs = intArrayOf(1, -1),
                vars = intArrayOf(1, 2),
                op = LinearOp.LE,
                bound = -4,
            ),
        ),
    )

    // Minimize the makespan M (int var 3).
    private val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))

    @Test
    fun `cone drops the big-M rows yet still bounds the critical path`() {
        val p = problem()
        val full = CpToLpRelaxation(p, objective).build(PropagationSession(p))
        val cone = CpToLpRelaxation(p, objective, objectiveCone = true).build(PropagationSession(p))

        // The cone model is strictly smaller: the two ReifiedLinear (2 rows each) and the ordering
        // bools they introduce are gone, leaving only the three precedence/makespan rows over starts.
        assertTrue(cone.model.m < full.model.m, "cone rows ${cone.model.m} should be < full ${full.model.m}")
        assertTrue(cone.model.n < full.model.n, "cone cols ${cone.model.n} should be < full ${full.model.n}")
        assertEquals(3, cone.model.m, "cone keeps exactly the three Linear rows")
        assertTrue(cone.colIsBool.none { it }, "cone has no Boolean columns (the ordering bools are dropped)")

        val coneSol = solveLp(cone.model)
        val fullSol = solveLp(full.model)
        assertEquals(LpVerdict.OPTIMAL, coneSol.status)
        assertEquals(LpVerdict.OPTIMAL, fullSol.status)
        val coneBound = coneSol.objectiveValue
        val fullBound = fullSol.objectiveValue
        // Critical path: s0=0 → s1≥3 → s2≥7 → M≥12.
        assertEquals(12.0, coneBound, eps, "cone bound is the critical-path length")
        // A relaxation can only loosen: the cone bound never exceeds the full bound (≤ the optimum).
        assertTrue(coneBound <= fullBound + eps, "cone bound $coneBound must not exceed full bound $fullBound")
    }

    @Test
    fun `cone-mode LP keeps the optimum correct end to end`() {
        val p = problem()
        val baseline = BacktrackSolver(p.bake()).minimize(objective, BacktrackParams(randomSeed = 1L))
        val cone = BacktrackSolver(p.bake()).minimize(
            objective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveCone = true)),
        )

        assertTrue(baseline is MinimizeResult.Optimal, "baseline should solve, got $baseline")
        assertTrue(cone is MinimizeResult.Optimal, "cone-LP solve should be optimal, got $cone")
        assertEquals(12.0, cone.objective, eps, "minimum makespan is the critical-path length")
        assertEquals(baseline.objective, cone.objective, eps, "the cone relaxation must not change the optimum")
        assertTrue(cone.stats.lp.solves.sum > 0.0, "the cone LP should actually run, got ${cone.stats.lp.solves.sum}")
    }
}
