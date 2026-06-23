package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Objective shaving must be SOUND — it may only raise the objective lower bound to a value proven
 * (by propagation + the LP relaxation) to be a true lower bound, never above the optimum (which would
 * lose the optimal solution). Checked directly (the shaved bound equals the brute-force optimum on a
 * triangle vertex cover) and end-to-end (the solved optimum is unchanged with shaving on).
 */
class ObjectiveShavingTest {

    /** `cost = x0+x1+x2` over {0,1}³ with the three pair-covering rows (triangle vertex cover): every
     *  solution needs ≥ 2 ones, so the minimum `cost` is 2 — but its declared domain min is 0. */
    private fun triangleCover(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4, // x0, x1, x2, cost
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0), // cost channelling
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `shaving raises the lower bound to the true optimum and no further`() {
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)) // minimize cost (var 3)
        val engine = LpEngine(
            p,
            obj,
            BacktrackParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "shave"),
        )
        // cost's declared min is 0; shaving must prove cost ≥ 2 (cost ≤ 1 is infeasible) and stop there.
        val lb = engine.shaveObjectiveLb(objectiveVar = 3, ascending = true, token = Cancellation.Never)
        assertEquals(2, lb, "shaving must prove the true lower bound 2, not over- or under-shave")
    }

    @Test
    fun `shaving preserves the optimum end to end`() {
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val off = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true)),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(2.0, off.objectiveValue)
        assertEquals(2.0, on.objectiveValue, "objective shaving changed the optimum")
    }

    @Test
    fun `the proven objective floor reaches the lower-bound sink`() {
        // The shaved floor (cost >= 2) is a global lower bound; it must be published to the portfolio's
        // shared lower-bound sink so a peer arm can pick it up.
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val published = ArrayList<Double>()
        val result = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, objectiveShaving = true),
                objectiveLowerBoundSink = { published.add(it) },
            ),
        )
        assertTrue(result is MinimizeResult.Optimal && result.objectiveValue == 2.0, "optimum is 2")
        assertTrue(published.any { it >= 2.0 }, "the proven floor (cost >= 2) must reach the sink, got $published")
    }

    @Test
    fun `randomized shaving never exceeds the brute-force optimum`() {
        val rng = Random(20260623)
        var covered = 0
        repeat(200) { _ ->
            val n = rng.nextInt(2, 5)
            val cap = n // cost domain [0, n]
            // cost = Σ xᵢ over {0,1}ⁿ, with random covering rows; minimize cost.
            val domains = Array(n + 1) { if (it < n) IntDomain(0, 1) else IntDomain(0, cap) }
            val factors = ArrayList<Factor>()
            factors.add(Linear(IntArray(n + 1) { if (it < n) 1 else -1 }, IntArray(n + 1) { it }, LinearOp.EQ, 0))
            repeat(rng.nextInt(1, n)) {
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                // Covering row (≥ 1), always satisfiable by setting the ones.
                factors.add(Linear(IntArray(k) { 1 }, vars, LinearOp.GE, 1))
            }
            val p = Problem(0, n + 1, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n + 1) { if (it == n) 1L else 0L })
            val engine = LpEngine(
                p,
                obj,
                BacktrackParams(lpPlan = LpPlan(bounding = true)),
                SolveStatsSink(backend = "shave"),
            )
            val lb = engine.shaveObjectiveLb(
                objectiveVar = n,
                ascending = true,
                token = Cancellation.Never,
            ) ?: return@repeat
            covered++
            // Brute force the true minimum cost over {0,1}ⁿ (covering rows only; the channelling EQ defines cost).
            val covering = factors.filterIsInstance<Linear>().filter { it.op == LinearOp.GE }
            var best = cap + 1
            for (mask in 0 until (1 shl n)) {
                var ok = true
                var sum = 0
                for (i in 0 until n) if ((mask shr i) and 1 == 1) sum++
                for (f in covering) {
                    var s = 0L
                    for (idx in f.vars.indices) s += f.coeffs[idx].toLong() * ((mask shr f.vars[idx]) and 1)
                    if (s < f.bound) {
                        ok = false
                        break
                    }
                }
                if (ok && sum < best) best = sum
            }
            assertTrue(lb <= best, "UNSOUND: shaved lower bound $lb exceeds the true optimum $best")
        }
        assertTrue(covered > 50, "shaving engaged on only $covered instances")
    }

    @Test
    fun `randomized variable shaving never excludes a feasible value`() {
        val rng = Random(20260625)
        var shaved = 0
        repeat(300) { _ ->
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 5)
            val domains = Array(n) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val coeffs = LongArray(n) { rng.nextInt(-2, 3).toLong() }
                if (coeffs.all { it == 0L }) return@repeat
                val rel = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
                val rhs = rng.nextInt(-hi, hi * n + 1)
                factors.add(Linear(coeffs.map { it.toInt() }.toIntArray(), IntArray(n) { it }, rel, rhs))
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { 1L })
            val params = BacktrackParams(lpPlan = LpPlan(bounding = true))
            val engine = LpEngine(p, obj, params, SolveStatsSink(backend = "shave"))
            val bounds = engine.shaveVariableBounds(Cancellation.Never)
            if (bounds.isEmpty()) return@repeat
            shaved++
            // Brute force: every shaved [lo, hi] must contain every feasible value of that variable.
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
                    if (!feasible()) return
                    for (b in bounds) {
                        assertTrue(
                            point[b.varId] in b.lo..b.hi,
                            "variable shaving excluded feasible x${b.varId}=${point[b.varId]} from [${b.lo},${b.hi}]",
                        )
                    }
                    return
                }
                for (v in 0..hi) {
                    point[idx] = v
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(shaved > 0, "variable shaving never engaged across 300 instances")
    }
}
