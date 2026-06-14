package com.eignex.klause.solver.factor

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the pure bounds-consistency core [computeBoundsAllDifferent] against a brute-force
 * matching oracle (#561). Bounds consistency: x_i may take v iff some all-different assignment
 * with x_i = v exists within the interval domains; the tightened lower/upper bound is the least /
 * greatest such v. Feasibility is the existence of a system of distinct representatives.
 */
class BoundsAllDifferentTest {

    /** True iff vars can be assigned pairwise-distinct values, each within [lo[i], hi[i]]. */
    private fun hasMatching(lo: IntArray, hi: IntArray): Boolean {
        val n = lo.size
        val matchValToVar = HashMap<Int, Int>()
        fun augment(i: Int, seen: HashSet<Int>): Boolean {
            for (v in lo[i]..hi[i]) {
                if (v in seen) continue
                seen.add(v)
                val occupant = matchValToVar[v]
                if (occupant == null || augment(occupant, seen)) {
                    matchValToVar[v] = i
                    return true
                }
            }
            return false
        }
        for (i in 0 until n) if (!augment(i, HashSet())) return false
        return true
    }

    /** True iff there's an SDR with x_i pinned to v. */
    private fun supports(lo: IntArray, hi: IntArray, i: Int, v: Int): Boolean {
        if (v < lo[i] || v > hi[i]) return false
        val lo2 = lo.copyOf()
        val hi2 = hi.copyOf()
        lo2[i] = v
        hi2[i] = v
        return hasMatching(lo2, hi2)
    }

    private fun bruteBounds(lo: IntArray, hi: IntArray): Triple<IntArray, IntArray, Boolean> {
        val n = lo.size
        val feasible = hasMatching(lo, hi)
        if (!feasible) return Triple(lo.copyOf(), hi.copyOf(), false)
        val bLo = IntArray(n)
        val bHi = IntArray(n)
        for (i in 0 until n) {
            var mn = hi[i] + 1
            var mx = lo[i] - 1
            for (v in lo[i]..hi[i]) {
                if (supports(lo, hi, i, v)) {
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
            }
            bLo[i] = mn
            bHi[i] = mx
        }
        return Triple(bLo, bHi, true)
    }

    @Test
    fun `matches brute force on random small instances`() {
        val rng = Random(20260614)
        var feasibleCases = 0
        var prunedCases = 0
        repeat(4000) {
            val n = 2 + rng.nextInt(5) // 2..6 vars
            val span = 0 + rng.nextInt(7) // values within 0..~8
            val lo = IntArray(n)
            val hi = IntArray(n)
            for (i in 0 until n) {
                val a = rng.nextInt(span + 1)
                val b = a + rng.nextInt(3) // width 0..2
                lo[i] = a
                hi[i] = b
            }
            val (bLo, bHi, bFeas) = bruteBounds(lo, hi)
            val nLo = IntArray(n)
            val nHi = IntArray(n)
            val feas = computeBoundsAllDifferent(lo, hi, nLo, nHi)
            assertEquals(bFeas, feas, "feasibility mismatch for lo=${lo.toList()} hi=${hi.toList()}")
            if (!bFeas) return@repeat
            feasibleCases++
            for (i in 0 until n) {
                // Soundness: the algorithm must never prune past the true bounds-consistent bound.
                assertTrue(
                    nLo[i] <= bLo[i],
                    "var $i over-raised min: algo ${nLo[i]} > brute ${bLo[i]} (lo=${lo.toList()} hi=${hi.toList()})",
                )
                assertTrue(
                    nHi[i] >= bHi[i],
                    "var $i over-lowered max: algo ${nHi[i]} < brute ${bHi[i]} (lo=${lo.toList()} hi=${hi.toList()})",
                )
                // Completeness: it must achieve full bounds consistency (reach the true bounds).
                assertEquals(bLo[i], nLo[i], "var $i min not tight (lo=${lo.toList()} hi=${hi.toList()})")
                assertEquals(bHi[i], nHi[i], "var $i max not tight (lo=${lo.toList()} hi=${hi.toList()})")
                if (nLo[i] != lo[i] || nHi[i] != hi[i]) prunedCases++
            }
        }
        // Sanity that the corpus actually exercised both feasible filtering and pruning.
        assertTrue(feasibleCases > 100, "too few feasible cases: $feasibleCases")
        assertTrue(prunedCases > 50, "too few pruning cases: $prunedCases")
    }
}
