package com.eignex.klause.solver.integration

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpAutoConfig
import com.eignex.klause.solver.backtrack.lp.LpConfig
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.table.Table
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
        val saved = KlauseConfig.current
        try {
            // Over the base cap but within the ceiling ⇒ LP still on (the hull budget shrinks, not LP).
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpCeilingTableauCells = Long.MAX_VALUE)
            val r = LpAutoConfig.resolve(p, LpConfig.AGGRESSIVE)
            assertTrue(r.lpPlan.bounding, "lpBounding should be on within the ceiling")

            // Ceiling = 1 cell ⇒ nothing fits ⇒ LP off.
            KlauseConfig.current = saved.copy(lpCeilingTableauCells = 1L)
            val off = LpAutoConfig.resolve(p, LpConfig.AGGRESSIVE)
            assertFalse(off.lpPlan.bounding)
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `large-relaxation minimize preserves the optimum`() {
        val rng = Random(20260619)
        val saved = KlauseConfig.current
        try {
            // A large cap so the LP bounding path is always taken.
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            var optimal = 0
            repeat(80) { _ ->
                val n = rng.nextInt(3, 6)
                val ub = IntArray(n) { rng.nextInt(2, 6) }
                val cost = LongArray(n) { rng.nextLong(-6, 7) }
                val vars = IntArray(n) { it }
                val cons = ArrayList<Pair<LongArray, Long>>()
                repeat(rng.nextInt(1, 4)) { _ -> cons.add(LongArray(n) { rng.nextLong(-3, 4) } to rng.nextLong(0, 15)) }

                val brute = bruteMin(n, ub, cost, cons)
                val domains = Array(n) { IntDomain(0, ub[it]) }
                val factors = cons.map { (c, r) ->
                    Linear(
                        c.map { it.toInt() }.toIntArray(),
                        vars,
                        LinearOp.LE,
                        r.toInt(),
                    )
                }
                val problem = Problem(0, n, domains, factors.toTypedArray<Factor>())
                val obj = LinearObjective(intCoefficients = cost)
                val resolved = LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE, BacktrackParams(randomSeed = 5L))
                assertTrue(resolved.lpPlan.bounding, "LP bounding must activate for this model")

                when (val res = BacktrackSolver(problem).minimize(obj, resolved)) {
                    is MinimizeResult.Optimal -> {
                        val o = brute ?: error("solver Optimal but brute infeasible")
                        assertEquals(o.toDouble(), res.objective, 1e-9, "wrong optimum")
                        optimal++
                    }

                    is MinimizeResult.Infeasible -> assertTrue(brute == null, "solver Infeasible but brute feasible")

                    else -> error("unexpected $res")
                }
            }
            assertTrue(optimal > 30, "covered only $optimal")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `large-relaxation single-objective propagation preserves the optimum`() {
        // Minimise a single variable z linked by z >= Σx to the rest, so the LP relaxation bounds z
        // from below and the LP objective-bound propagation (#705 slice 1) fires. An
        // unsound (over-tightened) bound would prove a too-high optimum and fail against brute force.
        val rng = Random(424242)
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            var optimal = 0
            repeat(120) { _ ->
                val nx = rng.nextInt(2, 4)
                val ub = IntArray(nx) { rng.nextInt(2, 5) }
                val zVar = nx
                val zUb = ub.sum()
                val geCons = ArrayList<Pair<IntArray, Int>>()
                repeat(rng.nextInt(1, 3)) { _ ->
                    geCons.add(IntArray(nx) { rng.nextInt(0, 4) } to rng.nextInt(0, zUb + 1))
                }
                val brute = bruteMinLinked(nx, ub, geCons)

                val domains = Array(nx + 1) { if (it < nx) IntDomain(0, ub[it]) else IntDomain(0, zUb) }
                val factors = ArrayList<Factor>()
                for ((c, r) in geCons) factors.add(Linear(c, IntArray(nx) { it }, LinearOp.GE, r))
                // z >= Σx : Σx − z ≤ 0.
                factors.add(
                    Linear(IntArray(nx + 1) { if (it < nx) 1 else -1 }, IntArray(nx + 1) { it }, LinearOp.LE, 0),
                )
                val problem = Problem(0, nx + 1, domains, factors.toTypedArray())
                val obj = LinearObjective(intCoefficients = LongArray(nx + 1) { if (it == zVar) 1L else 0L })
                val resolved = LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE, BacktrackParams(randomSeed = 9L))
                assertTrue(resolved.lpPlan.bounding, "LP bounding must activate for this model")

                when (val res = BacktrackSolver(problem).minimize(obj, resolved)) {
                    is MinimizeResult.Optimal -> {
                        val o = brute ?: error("solver Optimal but brute infeasible")
                        assertEquals(o.toDouble(), res.objective, 1e-9, "wrong optimum")
                        optimal++
                    }

                    is MinimizeResult.Infeasible -> assertTrue(brute == null, "solver Infeasible but brute feasible")

                    else -> error("unexpected $res")
                }
            }
            assertTrue(optimal > 60, "covered only $optimal")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `lp carries the hull columns`() {
        val saved = KlauseConfig.current
        try {
            // The Table convex-hull columns are accepted onto the LP bounding path and budgeted against
            // the relaxation-size cap (#655).
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            val p = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 4), IntDomain(0, 5)),
                arrayOf<Factor>(
                    // A base linear row alongside the Table global.
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 0),
                    Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 5, 2, 2, 4, 0)),
                ),
            )
            val resolved = LpAutoConfig.resolve(p, LpConfig.AGGRESSIVE, BacktrackParams(randomSeed = 1L))
            assertTrue(resolved.lpPlan.bounding, "LP bounding must activate")
            assertTrue(resolved.lpPlan.table, "the Table hull must be wired onto the LP path")
            // And the solve is sound: minimise x0 over the table {(0,5),(2,2),(4,0)} ⇒ 0.
            val res = BacktrackSolver(p).minimize(LinearObjective(intCoefficients = longArrayOf(1L, 0L)), resolved)
            assertTrue(res is MinimizeResult.Optimal && res.objective == 0.0, "expected optimum x0=0, got $res")
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `large-relaxation infeasibility pruning matches brute force`() {
        // Tight mixed LE/GE/EQ constraints make many instances (and interior nodes) LP-infeasible, so
        // the exact-Farkas infeasibility prune (#705 slice 3) fires. An unsound prune would cut a
        // feasible node, surfacing as a feasible instance wrongly reported Infeasible or a wrong optimum.
        val rng = Random(987654321)
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            var infeasible = 0
            var feasible = 0
            repeat(400) { _ ->
                val n = rng.nextInt(2, 5)
                val ub = IntArray(n) { rng.nextInt(1, 4) }
                val cons = ArrayList<Triple<IntArray, LinearOp, Int>>()
                repeat(rng.nextInt(2, 5)) { _ ->
                    val op = listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ)[rng.nextInt(3)]
                    cons.add(Triple(IntArray(n) { rng.nextInt(-2, 3) }, op, rng.nextInt(-3, 6)))
                }
                val brute = bruteMinX0(n, ub, cons)
                val domains = Array(n) { IntDomain(0, ub[it]) }
                val factors = cons.map { (c, op, r) -> Linear(c, IntArray(n) { it }, op, r) }.toTypedArray<Factor>()
                val problem = Problem(0, n, domains, factors)
                val obj = LinearObjective(intCoefficients = LongArray(n) { if (it == 0) 1L else 0L })
                val resolved = LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE, BacktrackParams(randomSeed = 3L))
                assertTrue(resolved.lpPlan.bounding, "LP bounding must activate for this model")

                when (val res = BacktrackSolver(problem).minimize(obj, resolved)) {
                    is MinimizeResult.Optimal -> {
                        val o = brute ?: error("solver Optimal but brute infeasible")
                        assertEquals(o.toDouble(), res.objective, 1e-9, "wrong optimum")
                        feasible++
                    }

                    is MinimizeResult.Infeasible -> {
                        assertTrue(brute == null, "solver Infeasible but brute feasible")
                        infeasible++
                    }

                    else -> error("unexpected $res")
                }
            }
            assertTrue(
                infeasible > 30 && feasible > 30,
                "want both verdicts exercised: infeasible=$infeasible feasible=$feasible",
            )
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `a starved root LP budget degrades gracefully without losing the optimum`() {
        // #31: the pre-search root LP work (cut harvest + root-bound + probe) is time-boxed so a slow
        // root relaxation can't starve search. A zero budget cancels every root step immediately; the
        // solve must still reach the true optimum from search alone (graceful, sound degradation).
        val rng = Random(31_31_31)
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            repeat(40) { _ ->
                val n = rng.nextInt(3, 6)
                val ub = IntArray(n) { rng.nextInt(2, 6) }
                val cost = LongArray(n) { rng.nextLong(-6, 7) }
                val cons = ArrayList<Pair<LongArray, Long>>()
                repeat(rng.nextInt(1, 4)) { _ -> cons.add(LongArray(n) { rng.nextLong(-3, 4) } to rng.nextLong(0, 15)) }
                val brute = bruteMin(n, ub, cost, cons)

                val domains = Array(n) { IntDomain(0, ub[it]) }
                val factors = cons.map { (c, r) ->
                    Linear(c.map { it.toInt() }.toIntArray(), IntArray(n) { it }, LinearOp.LE, r.toInt())
                }.toTypedArray<Factor>()
                val problem = Problem(0, n, domains, factors)
                val obj = LinearObjective(intCoefficients = cost)
                val resolved = LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE, BacktrackParams(randomSeed = 7L))
                assertTrue(resolved.lpPlan.bounding, "LP bounding must activate for this model")

                // rootBudgetMillis = 0 ⇒ Cancellation.after(0) is already passed ⇒ every root step bails.
                val starved = resolved.copy(lpPlan = resolved.lpPlan.copy(rootBudgetMillis = 0L))
                when (val res = BacktrackSolver(problem).minimize(obj, starved)) {
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
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `an unbudgeted root LP still captures the root bound`() {
        // The complement of the starvation guard: rootBudgetFraction = 0 disables the cap, so the root
        // relaxation bound is captured (finite) exactly as before the #31 budget. A feasible-and-bounded
        // small minimize has a finite LP relaxation, so a finite rootLpBound proves the root solve ran.
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = Long.MAX_VALUE)
            val problem = Problem(
                0,
                3,
                Array(3) { IntDomain(0, 4) },
                arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 5)),
            )
            val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
            val resolved = LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE, BacktrackParams(randomSeed = 2L))
            val unbudgeted = resolved.copy(lpPlan = resolved.lpPlan.copy(rootBudgetFraction = 0.0))
            val res = BacktrackSolver(problem).minimize(obj, unbudgeted)
            assertTrue(res is MinimizeResult.Optimal && res.objective == 5.0, "expected optimum 5, got $res")
            assertTrue(res.stats.rootLpBound.isFinite(), "the unbudgeted root LP must capture a finite root bound")
        } finally {
            KlauseConfig.current = saved
        }
    }

    /** Min x0 over feasible assignments (LE/GE/EQ), or null if infeasible — the brute oracle. */
    private fun bruteMinX0(n: Int, ub: IntArray, cons: List<Triple<IntArray, LinearOp, Int>>): Long? {
        val x = IntArray(n)
        var best: Long? = null
        fun feasible(): Boolean = cons.all { (c, op, r) ->
            val s = (0 until n).sumOf { c[it] * x[it] }
            when (op) {
                LinearOp.LE -> s <= r
                LinearOp.GE -> s >= r
                LinearOp.EQ -> s == r
                else -> true
            }
        }
        fun rec(i: Int) {
            if (i == n) {
                if (feasible()) {
                    val cur = best
                    if (cur == null || x[0].toLong() < cur) best = x[0].toLong()
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

    /** Minimal feasible z = min over GE-feasible x of Σx (z can always equal Σx, ≤ its upper bound). */
    private fun bruteMinLinked(nx: Int, ub: IntArray, geCons: List<Pair<IntArray, Int>>): Long? {
        val x = IntArray(nx)
        var best: Long? = null
        fun feasible(): Boolean = geCons.all { (c, r) -> (0 until nx).sumOf { c[it] * x[it] } >= r }
        fun rec(i: Int) {
            if (i == nx) {
                if (feasible()) {
                    val s = (0 until nx).sumOf { x[it].toLong() }
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
