package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * LP-relaxation harvest ([lpHarvest]) — folding the LP's proven variable-bound tightenings into the
 * problem's domains permanently. Asserts the gate (no shaving ⇒ no change) and the soundness invariant
 * (the harvested domains never exclude a feasible assignment), exercised over randomized linear systems
 * so the shave actually engages.
 */
class LpHarvestTest {

    private val shavingParams = BacktrackParams(lpPlan = LpPlan(bounding = true, variableShaving = true))

    @Test
    fun `harvest is a no-op when variable shaving is off`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        assertSame(
            problem,
            lpHarvest(problem, obj, BacktrackParams(lpPlan = LpPlan(bounding = true))),
            "with variable shaving disabled the harvest must return the problem unchanged",
        )
    }

    @Test
    fun `harvest tightens domains without excluding any feasible assignment`() {
        val rng = Random(20260701)
        var engaged = 0
        repeat(300) { _ ->
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 5)
            val domains = Array(n) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val coeffs = IntArray(n) { rng.nextInt(-2, 3) }
                if (coeffs.all { it == 0 }) return@repeat
                val rel = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
                factors.add(Linear(coeffs, IntArray(n) { it }, rel, rng.nextInt(-hi, hi * n + 1)))
            }
            val problem = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
            val harvested = lpHarvest(problem, obj, shavingParams, Cancellation.Never)
            if (harvested === problem) return@repeat
            engaged++
            // Every assignment feasible under the original constraints must still lie inside the
            // harvested domains — the harvest may only remove proven-infeasible values.
            val point = IntArray(n)
            fun feasible(): Boolean = factors.filterIsInstance<Linear>().all { f ->
                var s = 0L
                for (i in f.vars.indices) s += f.coeffs[i].toLong() * point[f.vars[i]]
                when (f.op) {
                    LinearOp.LE -> s <= f.bound
                    LinearOp.GE -> s >= f.bound
                    else -> true
                }
            }
            fun rec(idx: Int) {
                if (idx == n) {
                    if (feasible()) {
                        for (v in 0 until n) {
                            assertTrue(
                                point[v] in harvested.intDomains[v],
                                "harvest excluded feasible x$v=${point[v]} from ${harvested.intDomains[v].min}..${
                                    harvested.intDomains[v].max
                                }",
                            )
                        }
                    }
                    return
                }
                for (value in 0..hi) {
                    point[idx] = value
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(engaged > 0, "variable-shaving harvest never engaged across 300 instances")
    }

    @Test
    fun `harvested domains are no wider than the original`() {
        // A direct narrowing check on a small system: harvested bounds are always within the originals.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(
                Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val harvested = lpHarvest(problem, obj, shavingParams, Cancellation.Never)
        for (v in 0 until problem.numIntVars) {
            assertTrue(harvested.intDomains[v].min >= problem.intDomains[v].min, "lower bound widened for x$v")
            assertTrue(harvested.intDomains[v].max <= problem.intDomains[v].max, "upper bound widened for x$v")
        }
    }
}
