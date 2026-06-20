package com.eignex.klause.solver.integration

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpPlan
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #41: during-search cut separation must stay sound — tightening a node with the cuts its LP point
 * violates (global cuts persisted, node-local cuts transient) never removes a feasible solution, so
 * the proven optimum is unchanged. Validated against brute force over AllDifferent COPs, the
 * cut-eligible structure that drives the structural separators.
 */
class CutSearchSeparationTest {

    private fun searchCutParams(seed: Long) = BacktrackParams(
        randomSeed = seed,
        lpPlan = LpPlan(bounding = true, cuts = true, cutSearchMaxDepth = 64),
    )

    @Test
    fun `during-search separation preserves the optimum on all-different COPs`() {
        val rng = Random(41_41_41)
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            var optimal = 0
            repeat(120) { _ ->
                val n = rng.nextInt(2, 5)
                val ub = n - 1 + rng.nextInt(0, 3) // enough distinct values to be feasible
                val cost = LongArray(n) { rng.nextLong(-5, 6) }
                val vars = IntArray(n) { it }
                // An optional linear side constraint to make the LP non-trivial.
                val cons = ArrayList<Pair<IntArray, Int>>()
                repeat(rng.nextInt(0, 2)) { _ ->
                    cons.add(IntArray(n) { rng.nextInt(-2, 3) } to rng.nextInt(0, n * ub + 1))
                }
                val brute = bruteMin(n, ub, cost, cons)

                val domains = Array(n) { IntDomain(0, ub) }
                val factors = ArrayList<Factor>()
                factors.add(AllDifferent(vars, domainMin = 0, domainSize = ub + 1))
                for ((c, r) in cons) factors.add(Linear(c, vars, LinearOp.LE, r))
                val problem = Problem(0, n, domains, factors.toTypedArray())
                val obj = LinearObjective(intCoefficients = cost)

                when (val res = BacktrackSolver(problem).minimize(obj, searchCutParams(7L))) {
                    is MinimizeResult.Optimal -> {
                        assertEquals(
                            (brute ?: error("solver Optimal but brute infeasible")).toDouble(),
                            res.objective,
                            1e-9,
                        )
                        optimal++
                    }

                    is MinimizeResult.Infeasible -> assertTrue(brute == null, "solver Infeasible but brute feasible")

                    else -> error("unexpected $res")
                }
            }
            assertTrue(optimal > 60, "covered only $optimal feasible instances")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `the during-search separation path executes`() {
        // An AllDifferent COP that branches; with the depth gate open, separating nodes issue extra LP
        // re-solves, so the LP-solve count exceeds the root-only (cutSearchMaxDepth = 0) run. This proves
        // the new path runs rather than being silently skipped.
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            val n = 5
            val ub = 6
            val vars = IntArray(n) { it }
            val problem = Problem(
                0,
                n,
                Array(n) { IntDomain(0, ub) },
                arrayOf<Factor>(
                    AllDifferent(vars, domainMin = 0, domainSize = ub + 1),
                    Linear(IntArray(n) { 1 }, vars, LinearOp.GE, n * 2),
                ),
            )
            val obj = LinearObjective(intCoefficients = LongArray(n) { (it + 1).toLong() })

            val rootOnly = BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, cuts = true, cutSearchMaxDepth = 0),
            )
            val withSearch = BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, cuts = true, cutSearchMaxDepth = 64),
            )
            val a = BacktrackSolver(problem).minimize(obj, rootOnly)
            val b = BacktrackSolver(problem).minimize(obj, withSearch)
            assertTrue(a is MinimizeResult.Optimal && b is MinimizeResult.Optimal)
            assertEquals(a.objective, b.objective, 1e-9, "search separation changed the optimum")
            val rootSolves = a.stats.lpSolves.sum
            val searchSolves = b.stats.lpSolves.sum
            assertTrue(
                searchSolves > rootSolves,
                "expected extra LP solves from during-search separation: $searchSolves vs $rootSolves",
            )
        } finally {
            KlauseConfig.current = saved
        }
    }

    /** Min Σ cost·x over distinct assignments in [0,ub] satisfying the LE constraints, or null. */
    private fun bruteMin(n: Int, ub: Int, cost: LongArray, cons: List<Pair<IntArray, Int>>): Long? {
        val x = IntArray(n)
        val used = BooleanArray(ub + 1)
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
            for (v in 0..ub) {
                if (used[v]) continue
                used[v] = true
                x[i] = v
                rec(i + 1)
                used[v] = false
            }
        }
        rec(0)
        return best
    }
}
