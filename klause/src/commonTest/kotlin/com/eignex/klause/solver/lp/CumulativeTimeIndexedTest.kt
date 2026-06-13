package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #453: the time-indexed `x_{i,t}` LP reformulation of the scheduling globals. */
class CumulativeTimeIndexedTest {

    private val eps = 1e-6

    /** `min M  s.t.  M ≥ startᵢ + durᵢ,  cumulative(...)`; ints `0..n-1` starts, `n` is the makespan. */
    private fun makespanProblem(
        starts: Array<IntDomain>,
        durations: IntArray,
        resources: IntArray,
        capacity: Int,
        horizon: Int,
        disjunctive: Boolean = false,
    ): Problem {
        val n = starts.size
        val domains = Array(n + 1) { if (it < n) starts[it] else IntDomain(0, horizon) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, durations[i]))
        factors.add(
            if (disjunctive) {
                Disjunctive(IntArray(n) { it }, durations)
            } else {
                Cumulative(IntArray(n) { it }, durations, resources, capacity)
            },
        )
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    private fun makespanBound(problem: Problem, makespanVar: Int, timeIndexed: Boolean): Double {
        val obj = LinearObjective(intCoefficients = LongArray(problem.numIntVars) { if (it == makespanVar) 1L else 0L })
        val relaxation = CpToLpRelaxation(problem, obj, cumulativeTimeIndexed = timeIndexed)
            .build(PropagationSession(problem))
        val sol = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue + relaxation.objectiveConstant
    }

    /** Sum-of-starts LP bound (`min Σ startᵢ`) with the time-indexed rows on/off. The time-indexed
     *  formulation is exact (integral) for single-machine completion-time objectives, where the
     *  expected-start channel cannot be gamed — unlike loose-domain makespan. */
    private fun sumStartBound(problem: Problem, taskStarts: IntArray, timeIndexed: Boolean): Double {
        val obj = LinearObjective(
            intCoefficients = LongArray(problem.numIntVars) { if (it in taskStarts) 1L else 0L },
        )
        val relaxation = CpToLpRelaxation(problem, obj, cumulativeTimeIndexed = timeIndexed)
            .build(PropagationSession(problem))
        val sol = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue + relaxation.objectiveConstant
    }

    @Test
    fun `time-indexed is exact for single-machine total completion time`() {
        // 3 unit tasks of length 3 on capacity 1, min Σ startᵢ. Serial starts 0,3,6 ⇒ Σ = 9; the
        // resource rows forbid the all-at-zero point the plain LP allows (Σ = 0). The single-machine
        // time-indexed LP is integral, so it attains exactly 9.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 20) },
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), 1)),
        )
        assertEquals(0.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = false), eps)
        assertEquals(9.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = true), eps)
    }

    @Test
    fun `disjunctive factor is reformulated too`() {
        // Same single-machine completion-time bound through the Disjunctive surface (cap 1).
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 20) },
            arrayOf<Factor>(Disjunctive(intArrayOf(0, 1, 2), intArrayOf(2, 3, 4))),
        )
        // Serial by SPT: starts 0, 2, 5 ⇒ Σ = 7 (the plain LP gives 0).
        assertEquals(0.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = false), eps)
        assertEquals(7.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = true), eps)
    }

    @Test
    fun `oversized horizon is skipped so the bound is unchanged`() {
        // Horizon 100000 ≫ MAX_TI_HORIZON: the builder emits nothing, so the bound matches plain.
        val p = makespanProblem(
            starts = Array(2) { IntDomain(0, 100_000) },
            durations = intArrayOf(3, 3),
            resources = intArrayOf(1, 1),
            capacity = 1,
            horizon = 100_006,
        )
        assertEquals(
            makespanBound(p, 2, timeIndexed = false),
            makespanBound(p, 2, timeIndexed = true),
            eps,
        )
    }

    @Test
    fun `bound never exceeds the true optimum - soundness vs brute force`() {
        val rng = Random(20260613)
        var strengthened = 0
        repeat(150) { _ ->
            val n = rng.nextInt(2, 4)
            val durations = IntArray(n) { rng.nextInt(1, 4) }
            val resources = IntArray(n) { rng.nextInt(1, 3) }
            val capacity = rng.nextInt(1, 4)
            val starts = Array(n) { IntDomain(rng.nextInt(0, 3), 3 + rng.nextInt(0, 3)) }
            val horizon = (0 until n).maxOf { starts[it].max + durations[it] }
            val p = makespanProblem(starts, durations, resources, capacity, horizon)
            val optimum = bruteOptimum(n, starts, durations, resources, capacity) ?: return@repeat
            val bound = makespanBound(p, n, timeIndexed = true)
            assertTrue(
                bound <= optimum + eps,
                "unsound: time-indexed bound $bound > optimum $optimum " +
                    "(dur=${durations.toList()} res=${resources.toList()} cap=$capacity)",
            )
            if (bound > makespanBound(p, n, timeIndexed = false) + eps) strengthened++
        }
        assertTrue(strengthened > 15, "the reformulation strengthened only $strengthened instances")
    }

    @Test
    fun `branch and bound preserves the optimum with both relaxations on`() {
        // End-to-end: the time-indexed rows (#453) and the preemptive flow prune (#454) both fire
        // during search — their rows and nogoods must never exclude the true optimum.
        val rng = Random(20260613)
        var optimal = 0
        repeat(60) { iter ->
            val n = rng.nextInt(2, 4)
            val durations = IntArray(n) { rng.nextInt(1, 4) }
            val resources = IntArray(n) { rng.nextInt(1, 3) }
            val capacity = rng.nextInt(1, 4)
            val starts = Array(n) { IntDomain(0, rng.nextInt(2, 5)) }
            val horizon = (0 until n).maxOf { starts[it].max + durations[it] }
            val p = makespanProblem(starts, durations, resources, capacity, horizon)
            val optimum = bruteOptimum(n, starts, durations, resources, capacity)
            val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == n) 1L else 0L })
            val params = BacktrackParams(
                randomSeed = 7L,
                lpBounding = true,
                lpLearn = true,
                lpObjectiveBound = true,
                lpFixpoint = true,
                lpCumulative = true,
                lpCumulativeTimeIndexed = true,
                lpCumulativeFlow = true,
                energeticReasoning = true,
            )
            when (val res = BacktrackSolver(p).minimize(obj, params)) {
                is MinimizeResult.Optimal -> {
                    optimal++
                    assertTrue(optimum != null, "solver Optimal on a brute-infeasible instance #$iter")
                    assertEquals(optimum.toDouble(), res.objective, 1e-9, "wrong optimum on instance #$iter")
                }

                is MinimizeResult.Infeasible -> assertTrue(optimum == null, "solver Infeasible but feasible #$iter")

                else -> error("unexpected non-terminal result $res on instance #$iter")
            }
        }
        assertTrue(optimal > 15, "covered only $optimal optimal instances")
    }

    /** Minimal feasible `max(startᵢ + durᵢ)` over all in-domain start assignments, or null if none. */
    private fun bruteOptimum(
        n: Int,
        starts: Array<IntDomain>,
        durations: IntArray,
        resources: IntArray,
        capacity: Int,
    ): Int? {
        val s = IntArray(n)
        var best: Int? = null
        fun feasible(): Boolean {
            val horizon = (0 until n).maxOf { s[it] + durations[it] }
            for (t in 0 until horizon) {
                var load = 0
                for (k in 0 until n) if (s[k] <= t && t < s[k] + durations[k]) load += resources[k]
                if (load > capacity) return false
            }
            return true
        }
        fun rec(i: Int) {
            if (i == n) {
                if (feasible()) {
                    val mk = (0 until n).maxOf { s[it] + durations[it] }
                    if (best == null || mk < best!!) best = mk
                }
                return
            }
            for (v in starts[i].min..starts[i].max) {
                s[i] = v
                rec(i + 1)
            }
        }
        rec(0)
        return best
    }
}
