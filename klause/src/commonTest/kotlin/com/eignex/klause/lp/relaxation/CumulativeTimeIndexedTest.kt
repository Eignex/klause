package com.eignex.klause.lp.relaxation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.LpStatus
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.Sense
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
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
        durations: LongArray,
        resources: LongArray,
        capacity: Long,
        horizon: Int,
        disjunctive: Boolean = false,
    ): Problem {
        val n = starts.size
        val domains = Array(n + 1) { if (it < n) starts[it] else IntDomain(0, horizon.toLong()) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(longArrayOf(1, -1), intArrayOf(n, i), LinearOp.GE, durations[i]))
        factors.add(
            if (disjunctive) {
                Cumulative.unary(IntArray(n) { it }, durations)
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
        val sol = solveLp(relaxation.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
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
        val sol = solveLp(relaxation.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
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
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), 1)),
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
            arrayOf<Factor>(Cumulative.unary(intArrayOf(0, 1, 2), longArrayOf(2, 3, 4))),
        )
        // Serial by SPT: starts 0, 2, 5 ⇒ Σ = 7 (the plain LP gives 0).
        assertEquals(0.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = false), eps)
        assertEquals(7.0, sumStartBound(p, intArrayOf(0, 1, 2), timeIndexed = true), eps)
    }

    /** Makespan LP bound with the energetic row (#430) on and the time-indexed rows [ti] on/off. */
    private fun energeticVsTiBound(problem: Problem, makespanVar: Int, ti: Boolean): Double {
        val obj = LinearObjective(intCoefficients = LongArray(problem.numIntVars) { if (it == makespanVar) 1L else 0L })
        val relaxation = CpToLpRelaxation(problem, obj, cumulative = true, cumulativeTimeIndexed = ti)
            .build(PropagationSession(problem))
        val sol = solveLp(relaxation.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `time-indexed resource coupling exceeds the energetic makespan row`() {
        // #472 gate: the issue assumed the energetic area row (#430) dominates the time-indexed
        // makespan, so the model would be redundant for makespan. It is not — the cross-task
        // resource rows lift the bound past the energetic window on this multi-capacity profile.
        val p = makespanProblem(
            starts = arrayOf(IntDomain(0, 6), IntDomain(3, 5), IntDomain(4, 9), IntDomain(2, 6)),
            durations = longArrayOf(3, 2, 3, 2),
            resources = longArrayOf(3, 1, 2, 2),
            capacity = 3,
            horizon = 14,
        )
        val energetic = energeticVsTiBound(p, makespanVar = 4, ti = false)
        val both = energeticVsTiBound(p, makespanVar = 4, ti = true)
        assertTrue(both > energetic + eps, "time-indexed did not strengthen: energetic=$energetic both=$both")
    }

    @Test
    fun `disaggregated makespan rows do not strengthen the expected-start channel`() {
        // #472 scope: the proposed disaggregated `M ≥ (t+durᵢ)·x_{i,t}` rows (and the equivalent
        // completion-indicator step rows) cannot beat the plain channel — any makespan bound linear
        // in one task's x is dominated by the expected completion `M ≥ startᵢ + durᵢ` already gives.
        // Built by hand so the negative result is documented independently of the production builder.
        val est = intArrayOf(0, 0, 1)
        val lst = intArrayOf(3, 3, 4)
        val dur = intArrayOf(2, 2, 2)
        val res = intArrayOf(1, 1, 1)
        val cap = 2
        val channel = handBuiltTiMakespan(est, lst, dur, res, cap, disaggregate = false)
        val disagg = handBuiltTiMakespan(est, lst, dur, res, cap, disaggregate = true)
        assertEquals(channel, disagg, eps, "disaggregation moved the bound: channel=$channel disagg=$disagg")
    }

    /** Minimum-makespan time-indexed LP, built directly; [disaggregate] adds `M ≥ (t+durᵢ)·x_{i,t}`. */
    private fun handBuiltTiMakespan(
        est: IntArray,
        lst: IntArray,
        dur: IntArray,
        res: IntArray,
        cap: Int,
        disaggregate: Boolean,
    ): Double {
        val n = est.size
        val t0 = est.min()
        val t1 = (0 until n).maxOf { lst[it] + dur[it] }
        val b = LpBuilder()
        val mk = b.addVar(t0.toLong(), t1.toLong(), cost = 1L)
        val xCols = Array(n) { IntArray(lst[it] - est[it] + 1) }
        for (i in 0 until n) {
            val assign = LinkedHashMap<Int, Long>()
            val chan = LinkedHashMap<Int, Long>()
            for (k in xCols[i].indices) {
                val t = est[i] + k
                val col = b.addVar(0L, 1L)
                xCols[i][k] = col
                assign[col] = 1L
                chan[col] = t.toLong()
            }
            b.addRow(assign, Relation.EQ, 1L)
            val start = b.addVar(est[i].toLong(), lst[i].toLong())
            chan[start] = -1L
            b.addRow(chan, Relation.EQ, 0L)
            b.addRow(mapOf(mk to 1L, start to -1L), Relation.GE, dur[i].toLong())
            if (disaggregate) {
                for (k in xCols[i].indices) {
                    val t = est[i] + k
                    b.addRow(mapOf(mk to 1L, xCols[i][k] to -(t + dur[i]).toLong()), Relation.GE, 0L)
                }
            }
        }
        for (tt in t0 until t1) {
            val row = LinkedHashMap<Int, Long>()
            for (i in 0 until n) {
                val lo = maxOf(est[i], tt - dur[i] + 1)
                val hi = minOf(lst[i], tt)
                for (t in lo..hi) row[xCols[i][t - est[i]]] = (row[xCols[i][t - est[i]]] ?: 0L) + res[i]
            }
            if (row.isNotEmpty()) b.addRow(row, Relation.LE, cap.toLong())
        }
        val sol = solveLp(b.build(Sense.MINIMIZE))
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `oversized horizon is skipped so the bound is unchanged`() {
        // Horizon 100000 ≫ MAX_TI_HORIZON: the builder emits nothing, so the bound matches plain.
        val p = makespanProblem(
            starts = Array(2) { IntDomain(0, 100_000) },
            durations = longArrayOf(3, 3),
            resources = longArrayOf(1, 1),
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
            val durations = LongArray(n) { rng.nextInt(1, 4).toLong() }
            val resources = LongArray(n) { rng.nextInt(1, 3).toLong() }
            val capacity = rng.nextInt(1, 4).toLong()
            val starts = Array(n) { IntDomain(rng.nextInt(0, 3).toLong(), (3 + rng.nextInt(0, 3)).toLong()) }
            val horizon = (0 until n).maxOf { starts[it].max + durations[it] }
            val p = makespanProblem(starts, durations, resources, capacity, horizon.toInt())
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
            val durations = LongArray(n) { rng.nextInt(1, 4).toLong() }
            val resources = LongArray(n) { rng.nextInt(1, 3).toLong() }
            val capacity = rng.nextInt(1, 4).toLong()
            val starts = Array(n) { IntDomain(0, rng.nextInt(2, 5).toLong()) }
            val horizon = (0 until n).maxOf { starts[it].max + durations[it] }
            val p = makespanProblem(starts, durations, resources, capacity, horizon.toInt())
            val optimum = bruteOptimum(n, starts, durations, resources, capacity)
            val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == n) 1L else 0L })
            val params = BacktrackParams(
                randomSeed = 7L,
                lpPlan = LpPlan(
                    bounding = true,
                    learn = true,
                    energeticReasoning = true,
                    cumulative = true,
                    cumulativeTimeIndexed = true,
                    cumulativeFlow = true,
                ),
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
        durations: LongArray,
        resources: LongArray,
        capacity: Long,
    ): Long? {
        val s = IntArray(n)
        var best: Long? = null
        fun feasible(): Boolean {
            val horizon = (0 until n).maxOf { s[it] + durations[it] }
            for (t in 0 until horizon) {
                var load = 0L
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
                s[i] = v.toInt()
                rec(i + 1)
            }
        }
        rec(0)
        return best
    }
}
