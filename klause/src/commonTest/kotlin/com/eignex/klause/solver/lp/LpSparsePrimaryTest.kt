package com.eignex.klause.solver.lp

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.LpAutoConfig
import com.eignex.klause.solver.backtrack.LpConfig
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #602: models over the dense-tableau cap route to the bound-only sparse pipeline instead of
 *  disabling LP — auto-config picks it, and search stays sound. */
class LpSparsePrimaryTest {

    private fun linearProblem(n: Int): Problem {
        val domains = Array(n) { IntDomain(0, 4) }
        val factors = arrayOf<Factor>(Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.GE, n))
        return Problem(0, n, domains, factors)
    }

    @Test
    fun `over-cap auto-config routes to the sparse primary path`() {
        val p = linearProblem(4)
        val saved = KlauseConfig.current
        try {
            // Dense cap = 1 cell ⇒ nothing fits dense; sparse cap large ⇒ route to sparse.
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpSparseMaxTableauCells = Long.MAX_VALUE)
            val r = LpAutoConfig.resolve(p, LpConfig.AGGRESSIVE)
            assertTrue(r.lpBounding, "lpBounding should be on via the sparse route")
            assertTrue(r.lpSparsePrimary, "lpSparsePrimary should be set when over the dense cap")
            assertFalse(r.lpCuts, "sparse-primary path is bound-only — no cuts")

            // Both caps tiny ⇒ neither dense nor sparse ⇒ LP off.
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpSparseMaxTableauCells = 1L)
            val off = LpAutoConfig.resolve(p, LpConfig.AGGRESSIVE)
            assertFalse(off.lpSparsePrimary)
            assertFalse(off.lpBounding)
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `sparse-primary minimize preserves the optimum`() {
        val rng = Random(20260619)
        val saved = KlauseConfig.current
        try {
            // Force every relaxation over the dense cap so the sparse-primary path is always taken.
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpSparseMaxTableauCells = Long.MAX_VALUE)
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
                assertTrue(resolved.lpSparsePrimary, "expected the sparse-primary path under the tiny dense cap")

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
    fun `sparse-primary single-objective propagation preserves the optimum`() {
        // Minimise a single variable z linked by z >= Σx to the rest, so the LP relaxation bounds z
        // from below and the sparse path's objective-bound propagation (#705 slice 1) fires. An
        // unsound (over-tightened) bound would prove a too-high optimum and fail against brute force.
        val rng = Random(424242)
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpSparseMaxTableauCells = Long.MAX_VALUE)
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
                assertTrue(resolved.lpSparsePrimary, "expected the sparse-primary path under the tiny dense cap")

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
    fun `sparse-primary infeasibility pruning matches brute force`() {
        // Tight mixed LE/GE/EQ constraints make many instances (and interior nodes) LP-infeasible, so
        // the exact-Farkas infeasibility prune (#705 slice 3) fires. An unsound prune would cut a
        // feasible node, surfacing as a feasible instance wrongly reported Infeasible or a wrong optimum.
        val rng = Random(987654321)
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(lpMaxTableauCells = 1L, lpSparseMaxTableauCells = Long.MAX_VALUE)
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
                assertTrue(resolved.lpSparsePrimary, "expected the sparse-primary path under the tiny dense cap")

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
            assertTrue(infeasible > 30 && feasible > 30, "want both verdicts exercised: infeasible=$infeasible feasible=$feasible")
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
