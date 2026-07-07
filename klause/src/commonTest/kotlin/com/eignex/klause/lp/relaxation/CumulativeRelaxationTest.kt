package com.eignex.klause.lp.relaxation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.lp.LpConfig
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.lp.LpStatus
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

/** #430: the energetic makespan LP lower bound for the scheduling globals. */
class CumulativeRelaxationTest {

    private val eps = 1e-6

    /**
     * `min M  s.t.  M ≥ startᵢ + durᵢ ∀i,  cumulative(start, dur, res, cap)`. Variable layout: ints
     * `0..n-1` are the starts, int `n` is the makespan `M`. The makespan link is the direct
     * `M − startᵢ ≥ durᵢ` row the relaxation must recognise.
     */
    private fun makespanProblem(
        starts: Array<IntDomain>,
        durations: IntArray,
        resources: IntArray,
        capacity: Int,
        horizon: Int,
        disjunctive: Boolean = false,
    ): Problem {
        val n = starts.size
        val m = n // makespan var id
        val domains = Array(n + 1) { if (it < n) starts[it] else IntDomain(0, horizon.toLong()) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) {
            factors.add(Linear(intArrayOf(1, -1), intArrayOf(m, i), LinearOp.GE, durations[i]))
        }
        factors.add(
            if (disjunctive) {
                Disjunctive(IntArray(n) { it }, durations)
            } else {
                Cumulative(IntArray(n) { it }, durations, resources, capacity)
            },
        )
        return Problem(0, n + 1, domains, factors.toTypedArray())
    }

    /** LP bound of `min M` on [problem] with the cumulative makespan row [cumulative] on/off. */
    private fun makespanBound(problem: Problem, makespanVar: Int, cumulative: Boolean): Double {
        val obj = LinearObjective(intCoefficients = LongArray(problem.numIntVars) { if (it == makespanVar) 1L else 0L })
        val relaxation = CpToLpRelaxation(problem, obj, cumulative = cumulative).build(PropagationSession(problem))
        val sol = solveLp(relaxation.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `area bound proves the disjunctive makespan`() {
        // 3 unit tasks of length 3, capacity 1: total energy 9 must serialise ⇒ makespan ≥ 9.
        val p = makespanProblem(
            starts = Array(3) { IntDomain(0, 20) },
            durations = intArrayOf(3, 3, 3),
            resources = intArrayOf(1, 1, 1),
            capacity = 1,
            horizon = 20,
        )
        // Without the row the LP only sees M ≥ startᵢ + 3 with every start at 0 ⇒ M ≥ 3.
        assertEquals(3.0, makespanBound(p, makespanVar = 3, cumulative = false), eps)
        assertEquals(9.0, makespanBound(p, makespanVar = 3, cumulative = true), eps)
    }

    @Test
    fun `capacity divides the cumulative energy`() {
        // 4 tasks, demand 1, length 2, capacity 2: energy 8 over capacity 2 ⇒ makespan ≥ 4.
        val p = makespanProblem(
            starts = Array(4) { IntDomain(0, 20) },
            durations = intArrayOf(2, 2, 2, 2),
            resources = intArrayOf(1, 1, 1, 1),
            capacity = 2,
            horizon = 20,
        )
        assertEquals(4.0, makespanBound(p, makespanVar = 4, cumulative = true), eps)
    }

    @Test
    fun `energetic windowing beats the whole-horizon area`() {
        // Task 0: est 0, len 2. Task 1: est 5, len 2. Capacity 1.
        // Whole horizon (t1 = 0): 0 + (2 + 2) = 4. Window t1 = 5: 5 + 2 (task 1) = 7 > 4.
        val p = makespanProblem(
            starts = arrayOf(IntDomain(0, 20), IntDomain(5, 20)),
            durations = intArrayOf(2, 2),
            resources = intArrayOf(1, 1),
            capacity = 1,
            horizon = 20,
        )
        assertEquals(7.0, makespanBound(p, makespanVar = 2, cumulative = true), eps)
    }

    @Test
    fun `disjunctive factor gets the one-machine bound`() {
        val p = makespanProblem(
            starts = Array(3) { IntDomain(0, 20) },
            durations = intArrayOf(2, 3, 4),
            resources = intArrayOf(1, 1, 1),
            capacity = 1,
            horizon = 20,
            disjunctive = true,
        )
        // Σ dur = 9, all may start at 0 ⇒ makespan ≥ 9.
        assertEquals(9.0, makespanBound(p, makespanVar = 3, cumulative = true), eps)
    }

    @Test
    fun `makespan via array-max of end variables is recognised`() {
        // ints: 0,1 starts; 2,3 ends; 4 makespan. endᵢ = startᵢ + 2 ; makespan = max(end).
        val domains = arrayOf(
            IntDomain(0, 20),
            IntDomain(0, 20), // starts
            IntDomain(0, 20),
            IntDomain(0, 20), // ends
            IntDomain(0, 20), // makespan
        )
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.EQ, 2), // end0 - start0 = 2
            Linear(intArrayOf(1, -1), intArrayOf(3, 1), LinearOp.EQ, 2), // end1 - start1 = 2
            ArrayMinMax(result = 4, xs = intArrayOf(2, 3), max = true), // makespan = max(end)
            Cumulative(intArrayOf(0, 1), intArrayOf(2, 2), intArrayOf(1, 1), capacity = 1),
        )
        val p = Problem(0, 5, domains, factors)
        // Energy 4 at capacity 1 ⇒ makespan ≥ 4.
        assertEquals(4.0, makespanBound(p, makespanVar = 4, cumulative = true), eps)
    }

    @Test
    fun `no makespan link emits no row`() {
        // Cumulative with no M ≥ end constraint at all: nothing to attach a bound to.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 20) },
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), 1)),
        )
        assertTrue(!CumulativeRelaxation(p).applicable, "no verified makespan ⇒ no plan")
    }

    @Test
    fun `variable durations and optional tasks are skipped`() {
        // durationVars present ⇒ the M ≥ startᵢ + durᵢ link is not a two-variable linear; skip (sound).
        val p = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 20) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.GE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(4, 1), LinearOp.GE, 0),
                Cumulative(
                    starts = intArrayOf(0, 1),
                    durations = intArrayOf(2, 2),
                    resources = intArrayOf(1, 1),
                    capacity = 1,
                    durationVars = intArrayOf(2, 3),
                ),
            ),
        )
        assertTrue(!CumulativeRelaxation(p).applicable, "variable durations ⇒ no plan")
    }

    @Test
    fun `bound never exceeds the true optimum - soundness vs brute force`() {
        val rng = Random(20260613)
        var nontrivial = 0
        repeat(120) { _ ->
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 5)
            val durations = IntArray(n) { rng.nextInt(1, 4) }
            val resources = IntArray(n) { rng.nextInt(1, 3) }
            val capacity = rng.nextInt(1, 4)
            val starts = Array(n) { IntDomain(rng.nextInt(0, 3).toLong(), (hi + rng.nextInt(0, 3)).toLong()) }
            // Ensure every domain is non-empty and the horizon covers any feasible end.
            val horizon = (0 until n).maxOf { starts[it].max + durations[it] }
            val p = makespanProblem(starts, durations, resources, capacity, horizon.toInt())
            val optimum = bruteOptimum(n, starts, durations, resources, capacity) ?: return@repeat // infeasible
            val bound = makespanBound(p, makespanVar = n, cumulative = true)
            assertTrue(
                bound <= optimum + eps,
                "unsound: LP makespan bound $bound > true optimum $optimum " +
                    "(dur=${durations.toList()} res=${resources.toList()} cap=$capacity)",
            )
            val plain = makespanBound(p, makespanVar = n, cumulative = false)
            if (bound > plain + eps) nontrivial++
        }
        assertTrue(nontrivial > 12, "the row only strengthened $nontrivial instances")
    }

    @Test
    fun `branch and bound preserves the scheduling optimum under an LP emphasis`() {
        // End-to-end: the non-global makespan row (live earliest-starts + premises) fires during
        // search, so a wrong premise would corrupt the optimum. The AGGRESSIVE emphasis turns on lpCumulative plus
        // the full LP learning stack, exactly the path that consumes the row's premises.
        val rng = Random(20260613)
        var optimal = 0
        var infeasible = 0
        repeat(45) { iter ->
            val n = rng.nextInt(2, 4)
            val durations = IntArray(n) { rng.nextInt(1, 4) }
            val resources = IntArray(n) { rng.nextInt(1, 3) }
            val capacity = rng.nextInt(1, 4)
            val starts = Array(n) { IntDomain(0, rng.nextInt(2, 5).toLong()) }
            val horizon = (0 until n).maxOf { starts[it].max + durations[it] }
            val p = makespanProblem(starts, durations, resources, capacity, horizon.toInt())
            val optimum = bruteOptimum(n, starts, durations, resources, capacity)
            val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == n) 1L else 0L })
            val params = BacktrackParams(randomSeed = 7L, lubyRestartBase = 8L, lpConfig = LpConfig.AGGRESSIVE)
            when (val res = BacktrackSolver(p).minimize(obj, params)) {
                is MinimizeResult.Optimal -> {
                    optimal++
                    assertTrue(optimum != null, "solver Optimal on a brute-infeasible instance #$iter")
                    assertEquals(optimum.toDouble(), res.objective, 1e-9, "wrong scheduling optimum on instance #$iter")
                }

                is MinimizeResult.Infeasible -> {
                    infeasible++
                    assertTrue(optimum == null, "solver Infeasible on a brute-feasible instance #$iter")
                }

                else -> error("unexpected non-terminal result $res on instance #$iter")
            }
        }
        assertTrue(optimal > 12, "covered only $optimal optimal instances")
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
                s[i] = v.toInt()
                rec(i + 1)
            }
        }
        rec(0)
        return best
    }
}
