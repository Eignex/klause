package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #602/#705: LP bounding activates whenever the model fits the single relaxation-size cap, and the
 *  sparse revised-simplex path (the only LP engine) stays sound across minimize / single-objective
 *  propagation / Farkas infeasibility / hull carrying. */
class LpLargeRelaxationTest {

    private fun linearProblem(n: Int): Problem {
        val domains = Array(n) { IntDomain(0, 4) }
        val factors = arrayOf<Factor>(Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.GE, n))
        return Problem(0, n, domains, factors)
    }

    @Test
    fun `the relaxation-size ceiling gates lp activation`() {
        val p = linearProblem(4)
        var config = KlauseConfig.DEFAULT
        // Over the base cap but within the ceiling ⇒ LP still on (the hull budget shrinks, not LP).
        config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = 1L, lpCeilingTableauCells = Long.MAX_VALUE)
        val r = LpAutoConfig.resolve(p.withSettings(config.problemSettings()), LpConfig.AGGRESSIVE)
        assertTrue(r.bounding, "lpBounding should be on within the ceiling")

        // Ceiling = 1 cell ⇒ nothing fits ⇒ LP off.
        config = KlauseConfig.DEFAULT.copy(lpCeilingTableauCells = 1L)
        val off = LpAutoConfig.resolve(p.withSettings(config.problemSettings()), LpConfig.AGGRESSIVE)
        assertFalse(off.bounding)

    }

    @Test
    fun `a starved root LP budget degrades gracefully without losing the optimum`() {
        // #31: the pre-search root LP work (cut harvest + root-bound + probe) is time-boxed so a slow
        // root relaxation can't starve search. A zero budget cancels every root step immediately; the
        // solve must still reach the true optimum from search alone (graceful, sound degradation).
        val rng = Random(31_31_31)
        val config = KlauseConfig.DEFAULT.copy(lpMaxTableauCells = Long.MAX_VALUE)
        repeat(40) { _ ->
            val n = rng.nextInt(3, 6)
            val ub = IntArray(n) { rng.nextInt(2, 6) }
            val cost = LongArray(n) { rng.nextLong(-6, 7) }
            val cons = ArrayList<Pair<LongArray, Long>>()
            repeat(rng.nextInt(1, 4)) { _ -> cons.add(LongArray(n) { rng.nextLong(-3, 4) } to rng.nextLong(0, 15)) }
            val brute = bruteMin(n, ub, cost, cons)

            val domains = Array(n) { IntDomain(0, ub[it].toLong()) }
            val factors = cons.map { (c, r) ->
                Linear(c.map { it.toInt() }.toIntArray(), IntArray(n) { it }, LinearOp.LE, r.toInt())
            }.toTypedArray<Factor>()
            val problem = Problem(0, n, domains, factors)
            val obj = LinearObjective(intCoefficients = cost)
            val resolved = BacktrackParams(
                lpPlan = LpAutoConfig.resolve(problem.withSettings(config.problemSettings()), LpConfig.AGGRESSIVE),
                randomSeed = 7L,
            )
            assertTrue(resolved.lpPlan.bounding, "LP bounding must activate for this model")

            // rootBudgetMillis = 0 ⇒ Cancellation.after(0) is already passed ⇒ every root step bails.
            val starved = resolved.copy(lpPlan = resolved.lpPlan.copy(rootBudgetMillis = 0L))
            when (val res = BacktrackSolver(problem.bake()).minimize(obj, starved)) {
                is MinimizeResult.Optimal ->
                    assertEquals(
                        (brute ?: error("solver Optimal but brute infeasible")).toDouble(),
                        res.objective,
                        1e-9,
                    )

                is MinimizeResult.Infeasible -> assertTrue(brute == null, "solver Infeasible but brute feasible")

                else -> error("unexpected $res")
            }
        }

    }

    private fun bruteMin(n: Int, ub: IntArray, cost: LongArray, cons: List<Pair<LongArray, Long>>): Long? {
        val x = IntArray(n)
        var best: Long? = null
        fun feasible(): Boolean = cons.all { (c, r) -> (0 until n).sumOf { c[it] * x[it] } <= r }
        fun rec(i: Int) {
            if (i == n) {
                if (feasible()) {
                    val s = (0 until n).sumOf { cost[it] * x[it] }
                    val cur = best
                    if (cur == null || s < cur) best = s
                }
                return
            }
            for (v in 0..ub[i]) {
                x[i] = v
                rec(i + 1)
            }
        }
        rec(0)
        return best
    }
}
