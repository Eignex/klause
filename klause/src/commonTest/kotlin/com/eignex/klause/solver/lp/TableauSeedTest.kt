package com.eignex.klause.solver.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tableau-seeded warm start ([DualSimplex.solve]'s `seedTableau`) must be a pure speedup: a
 * child solve seeded from a parent's solved tableau lands on exactly the same verdict and optimum
 * as a cold solve of the same model. The battery varies what changes between parent and child —
 * bounds only (the rhs-patch path), coefficients of a nonbasic column (the column-patch + reseat
 * path, what a live-big-M reified row does), and coefficient changes the seed must *refuse*
 * (a basic column) — and checks the refusals genuinely fall back rather than corrupt the solve.
 */
class TableauSeedTest {

    private class Instance(val parent: LpModel, val child: LpModel)

    @Suppress("LongMethod")
    private fun randomInstance(rng: Random, n: Int, m: Int): Instance {
        val lo = IntArray(n)
        val hi = IntArray(n) { 4 + rng.nextInt(6) }
        val rowCols = ArrayList<IntArray>(m)
        val rowVals = ArrayList<LongArray>(m)
        val rowRel = ArrayList<Relation>(m)
        val rowRhs = ArrayList<Long>(m)
        repeat(m) { _ ->
            val kind = rng.nextInt(10)
            val cols: IntArray
            val vals: LongArray
            if (kind < 5) {
                val a = rng.nextInt(n)
                var b = rng.nextInt(n)
                if (b == a) b = (a + 1) % n
                cols = intArrayOf(a, b)
                vals = longArrayOf(1L, -1L)
            } else {
                val len = 2 + rng.nextInt(minOf(n - 1, 4))
                val start = rng.nextInt(n - len + 1)
                cols = IntArray(len) { start + it }
                vals = LongArray(len) { 1L }
            }
            val rel = when (rng.nextInt(8)) {
                0 -> Relation.EQ
                1, 2 -> Relation.GE
                else -> Relation.LE
            }
            var lhsAtMid = 0L
            for (k in cols.indices) lhsAtMid += vals[k] * (hi[cols[k]] / 2)
            val rhs = when (rel) {
                Relation.GE -> lhsAtMid - rng.nextInt(0, 3)
                Relation.LE -> lhsAtMid + rng.nextInt(0, 3)
                Relation.EQ -> lhsAtMid
            }
            rowCols.add(cols)
            rowVals.add(vals)
            rowRel.add(rel)
            rowRhs.add(rhs)
        }
        // The child mutates bounds and, half the time, one row's coefficient on one column —
        // standing in for a live-big-M change. The mutated column may or may not be basic in the
        // parent's solution; the seed handles the former and refuses the latter.
        val clo = lo.copyOf()
        val chi = hi.copyOf()
        repeat(maxOf(1, n / 4)) {
            val v = rng.nextInt(n)
            if (rng.nextBoolean()) clo[v] = minOf(chi[v], clo[v] + 1) else chi[v] = maxOf(clo[v], chi[v] - 1)
        }
        val childVals = ArrayList<LongArray>(m)
        for (r in 0 until m) childVals.add(rowVals[r].copyOf())
        if (rng.nextBoolean()) {
            val r = rng.nextInt(m)
            val k = rng.nextInt(rowCols[r].size)
            childVals[r][k] = childVals[r][k] + (1 + rng.nextInt(2)).toLong()
        }
        fun assemble(lows: IntArray, highs: IntArray, vals: List<LongArray>): LpModel {
            val b = LpBuilder()
            repeat(n) { j -> b.addVar(lows[j].toLong(), highs[j].toLong(), cost = ((j * 5 + 2) % 5 - 2).toLong()) }
            repeat(m) { r -> b.addRow(rowCols[r], vals[r], rowRel[r], rowRhs[r]) }
            return b.build(Sense.MINIMIZE)
        }
        return Instance(assemble(lo, hi, rowVals), assemble(clo, chi, childVals))
    }

    @Test
    fun `seeded child solve matches the cold solve exactly`() {
        val rng = Random(20260611)
        var seeded = 0
        var refused = 0
        var compared = 0
        repeat(400) {
            val inst = randomInstance(rng, n = 8 + rng.nextInt(8), m = 10 + rng.nextInt(12))
            val parentSimplex = DualSimplex(inst.parent)
            val parent = try {
                parentSimplex.solve()
            } catch (_: LpOverflowException) {
                return@repeat
            }
            if (parent.status != LpStatus.OPTIMAL) return@repeat

            val cold = try {
                DualSimplex(inst.child).solve()
            } catch (_: LpOverflowException) {
                return@repeat
            }
            val seededSimplex = DualSimplex(inst.child)
            val warm = try {
                seededSimplex.solve(seedTableau = parentSimplex)
            } catch (_: LpOverflowException) {
                return@repeat
            }
            if (seededSimplex.lastSolveSeeded) seeded++ else refused++
            compared++
            assertEquals(cold.status, warm.status, "seeded verdict diverged")
            if (cold.status == LpStatus.OPTIMAL) {
                assertEquals(
                    cold.objectiveValue,
                    warm.objectiveValue,
                    1e-9,
                    "seeded optimum diverged (seeded=${seededSimplex.lastSolveSeeded})",
                )
            }
        }
        assertTrue(compared > 200, "covered only $compared instances")
        assertTrue(seeded > 100, "only $seeded seeded solves — the fast path barely ran")
        assertTrue(refused > 20, "only $refused refusals — the fallback path barely ran")
    }

    @Test
    fun `bounds-only child always seeds`() {
        // No coefficient changes at all: the rhs-patch path must apply (the pure parent→child
        // branch-and-bound shape).
        val rng = Random(7)
        var seeded = 0
        var attempts = 0
        repeat(100) {
            val inst = randomInstance(rng, n = 10, m = 14)
            if (!inst.parent.a.indices.all { i -> inst.parent.a[i].contentEquals(inst.child.a[i]) }) return@repeat
            val parentSimplex = DualSimplex(inst.parent)
            val parent = try {
                parentSimplex.solve()
            } catch (_: LpOverflowException) {
                return@repeat
            }
            if (parent.status != LpStatus.OPTIMAL) return@repeat
            attempts++
            val seededSimplex = DualSimplex(inst.child)
            seededSimplex.solve(seedTableau = parentSimplex)
            if (seededSimplex.lastSolveSeeded) seeded++
        }
        assertTrue(attempts > 20, "covered only $attempts bounds-only instances")
        assertEquals(attempts, seeded, "a bounds-only child must always accept the seed")
    }
}
