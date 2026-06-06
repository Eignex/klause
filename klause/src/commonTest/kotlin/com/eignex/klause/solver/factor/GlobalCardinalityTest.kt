package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlobalCardinalityTest {

    private class LowUpInst(
        val xsRanges: List<Pair<Int, Int>>,
        val cover: IntArray,
        val low: IntArray,
        val high: IntArray,
        val closed: Boolean,
    )

    /**
     * Soundness gate for the sharpened (pigeonhole-subset) GCC conflict reasons, low/up form.
     * Battery includes a capacity-infeasible instance (3 vars, 2 values cap-1 each) so the
     * count-pigeonhole and Régin-flow failure paths fire. Under the full CDCL backtracker
     * enumeration must equal brute force; an unsound reason drops a feasible assignment.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set with low-up bounds`() {
        val instances = listOf(
            LowUpInst(listOf(0 to 1, 0 to 1, 0 to 1), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(1, 1), false),
            LowUpInst(
                listOf(0 to 2, 0 to 2, 0 to 2),
                intArrayOf(0, 1, 2),
                intArrayOf(1, 1, 1),
                intArrayOf(1, 1, 1),
                false,
            ),
            LowUpInst(
                listOf(0 to 2, 0 to 2, 0 to 2, 0 to 2),
                intArrayOf(0, 1, 2),
                intArrayOf(0, 0, 0),
                intArrayOf(2, 2, 2),
                false,
            ),
            LowUpInst(listOf(0 to 3, 0 to 3, 0 to 3), intArrayOf(0, 1), intArrayOf(0, 0), intArrayOf(3, 3), true),
            LowUpInst(
                listOf(0 to 2, 0 to 2, 1 to 2),
                intArrayOf(0, 1, 2),
                intArrayOf(0, 0, 1),
                intArrayOf(1, 2, 2),
                false,
            ),
            // alldiff-like (each value ≤ 1): v0,v1 confined to {0,1} form a tight Hall set, so
            // pinning v2/v3 into {0,1} during search fires the Régin flow-deficiency path on a
            // problem that is satisfiable overall — probes the min-cut reason for soundness.
            LowUpInst(
                listOf(0 to 1, 0 to 1, 0 to 3, 0 to 3),
                intArrayOf(0, 1, 2, 3),
                intArrayOf(0, 0, 0, 0),
                intArrayOf(1, 1, 1, 1),
                false,
            ),
        )
        for ((idx, inst) in instances.withIndex()) {
            val n = inst.xsRanges.size
            val coverIdx = inst.cover.withIndex().associate { (i, v) -> v to i }
            fun ok(acc: IntArray): Boolean {
                val counts = IntArray(inst.cover.size)
                for (i in 0 until n) {
                    val ci = coverIdx[acc[i]]
                    if (ci != null) {
                        counts[ci]++
                    } else if (inst.closed) {
                        return false
                    }
                }
                for (kk in inst.cover.indices) if (counts[kk] < inst.low[kk] || counts[kk] > inst.high[kk]) return false
                return true
            }
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (ok(acc)) brute.add(acc.toList())
                    return
                }
                for (v in inst.xsRanges[p].first..inst.xsRanges[p].second) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(inst.xsRanges[it].first, inst.xsRanges[it].second) },
                factors = arrayOf<Factor>(
                    GlobalCardinality(
                        xs = IntArray(n) { it },
                        cover = inst.cover,
                        countLow = inst.low,
                        countHigh = inst.high,
                        closed = inst.closed,
                    ),
                ),
            )
            val params = BacktrackParams(randomSeed = 1L, variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    /** Soundness gate for the count-var (`countVars[k] = #{xs=cover[k]}`) form. */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set with count vars`() {
        val n = 3
        val cover = intArrayOf(0, 1)
        val m = cover.size
        val xsRange = 0 to 1
        val cvRange = 0 to 3
        val coverIdx = cover.withIndex().associate { (i, v) -> v to i }
        val k = n + m
        val brute = HashSet<List<Int>>()
        val acc = IntArray(k)
        fun ok(): Boolean {
            val counts = IntArray(m)
            for (i in 0 until n) coverIdx[acc[i]]?.let { counts[it]++ }
            for (j in 0 until m) if (acc[n + j] != counts[j]) return false
            return true
        }
        fun rec(p: Int) {
            if (p == k) {
                if (ok()) brute.add(acc.toList())
                return
            }
            val r = if (p < n) xsRange else cvRange
            for (v in r.first..r.second) {
                acc[p] = v
                rec(p + 1)
            }
        }
        rec(0)

        val doms = Array(k) {
            if (it < n) IntDomain(xsRange.first, xsRange.second) else IntDomain(cvRange.first, cvRange.second)
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = k,
            intDomains = doms,
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = IntArray(n) { it }, cover = cover, countVars = IntArray(m) { n + it }),
            ),
        )
        val params = BacktrackParams(randomSeed = 1L, variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
        val found = BacktrackSolver(problem).enumerate(params).take(100_000)
            .map { it.ints.toList() }.toHashSet()
        assertEquals(brute, found, "count-vars backtrack solution set must equal brute force")
    }

    @Test
    fun `gcc with count vars`() {
        // xs ∈ [0..2]^5, cover = [0,1,2], count vars are last 3 vars. Each count must equal
        // the # of xs taking that cover value.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 8,
            intDomains = Array(8) { i -> if (i < 5) IntDomain(0, 2) else IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2, 3, 4),
                    cover = intArrayOf(0, 1, 2),
                    countVars = intArrayOf(5, 6, 7),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = (0..4).map { sat.assignment.ints[it] }
        for (k in 0..2) {
            val expected = xs.count { it == k }
            assertEquals(expected, sat.assignment.ints[5 + k], "count[$k] mismatch")
        }
    }

    @Test
    fun `gcc low_up enforces bounds`() {
        // 6 xs ∈ [0..2], cover = [0,1,2], lo=[1,1,1], up=[3,3,3].
        // Every value must appear ≥ 1 and ≤ 3 times.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2, 3, 4, 5),
                    cover = intArrayOf(0, 1, 2),
                    countLow = intArrayOf(1, 1, 1),
                    countHigh = intArrayOf(3, 3, 3),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = (0..5).map { sat.assignment.ints[it] }
        for (k in 0..2) {
            val c = xs.count { it == k }
            assertTrue(c in 1..3, "count[$k]=$c out of [1, 3]; xs=$xs")
        }
    }

    @Test
    fun `closed variant rejects values outside cover`() {
        // 3 xs ∈ [0..5]; cover = {1, 2, 3}; closed → xs must each be in {1, 2, 3}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2),
                    cover = intArrayOf(1, 2, 3),
                    countLow = intArrayOf(0, 0, 0),
                    countHigh = intArrayOf(3, 3, 3),
                    closed = true,
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        for (i in 0..2) {
            assertTrue(
                sat.assignment.ints[i] in setOf(1, 2, 3),
                "closed gcc: xs[$i] = ${sat.assignment.ints[i]} not in cover",
            )
        }
    }

    /**
     * Flow-deficiency conflicts must cite the count vars whose search-derived lower bounds
     * form the unmet demand. Two values each demand one taker (count mins raised at search
     * levels) while only one var can still serve either — per-value counts stay locally
     * consistent, so only the Régin flow detects the deficit. The demand-side cover nodes
     * are not residual-reachable from the cut, and a reach-filtered citation drops exactly
     * the count premises — the learned clause then claims the var bounds alone are
     * contradictory and prunes feasible assignments (surfaced as a false UNSAT on
     * oocsp_racks).
     */
    @Test
    fun `flow deficiency conflict cites the count var demand bounds`() {
        // ints: xs = 0,1 over 0..2; countVars 2 (value 1) and 3 (value 2) over 0..2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1), cover = intArrayOf(1, 2), countVars = intArrayOf(2, 3)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(2, 1)) { "count-1 demand failed" }
        state.currentLevel = 2
        check(state.tightenIntMin(3, 1)) { "count-2 demand failed" }
        state.currentLevel = 3
        check(state.tightenIntMax(1, 0)) { "x1 restriction failed" }

        val gcc = problem.factors[0]
        assertFalse(gcc.propagate(state, 0), "demand 2 vs supply 1 must conflict")
        val reason = gcc.conflictReason(state, 0)
        assertNotNull(reason, "flow-deficiency conflict must carry a reason")
        val citedInts = buildSet {
            for (lit in reason) {
                val v = Lit.variable(lit)
                if (v >= problem.numBoolVars) add(state.atomIntVar[v - problem.numBoolVars])
            }
        }
        assertTrue(2 in citedInts && 3 in citedInts, "reason must cite both count vars; cited $citedInts")
    }
}
