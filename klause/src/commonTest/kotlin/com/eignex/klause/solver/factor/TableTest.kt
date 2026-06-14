package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TableTest {

    @Test
    fun `enumerates only the allowed tuples`() {
        // Two vars in [0..3], allowed: (0, 1), (2, 3).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = intArrayOf(0, 1, 2, 3),
                ),
            ),
        )
        val results = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.toList() }
            .toList()
            .toSet()
        assertEquals(setOf(listOf(0, 1), listOf(2, 3)), results)
    }

    @Test
    fun `infeasible when domain excludes every row`() {
        // Two vars pinned to (1, 1). Allowed: (0,1), (2,3). Neither matches → Unsat.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(1, 1)),
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = intArrayOf(0, 1, 2, 3),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `propagation tightens domain to support set`() {
        // 3 vars ∈ [0..9]; tuples = [(1,2,3), (1,4,5), (7,8,9)].
        // After propagation: col 0 ⊆ {1, 7}; col 1 ⊆ {2, 4, 8}; col 2 ⊆ {3, 5, 9}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1, 2),
                    tuples = intArrayOf(1, 2, 3, 1, 4, 5, 7, 8, 9),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val ints = sat.assignment.ints.toList()
        assertTrue(
            ints in setOf(listOf(1, 2, 3), listOf(1, 4, 5), listOf(7, 8, 9)),
            "got $ints — not a known tuple",
        )
    }

    @Test
    fun `backtrack enumeration equals the in-domain tuple set`() {
        // Soundness gate for the unchanged-domains fast path: enumerating fires propagate
        // repeatedly on one PropagationState — fast-path hits on no-op re-fires, misses when a
        // decision shrinks a column — across push/pop that snapshot/restore the cached domain
        // refs. An unsound skip (returning satisfied when a value should have been pruned) would
        // let a forbidden tuple through, so the enumerated set must equal the brute-force set of
        // allowed rows that lie within the domains.
        data class Inst(val arity: Int, val lo: Int, val hi: Int, val tuples: List<List<Int>>)
        val instances = listOf(
            Inst(2, 0, 3, listOf(listOf(0, 1), listOf(2, 3), listOf(1, 1))),
            Inst(3, 0, 4, listOf(listOf(1, 2, 3), listOf(1, 4, 1), listOf(3, 3, 3), listOf(0, 0, 0))),
            Inst(3, 0, 2, listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 1, 1), listOf(0, 0, 0), listOf(2, 2, 2))),
        )
        for ((idx, inst) in instances.withIndex()) {
            val varsOf = IntArray(inst.arity) { it }
            val flat = IntArray(inst.tuples.size * inst.arity)
            inst.tuples.forEachIndexed { r, t -> for (c in 0 until inst.arity) flat[r * inst.arity + c] = t[c] }
            val brute = inst.tuples.filter { t -> t.all { it in inst.lo..inst.hi } }.map { it.toList() }.toHashSet()
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = inst.arity,
                intDomains = Array(inst.arity) { IntDomain(inst.lo, inst.hi) },
                factors = arrayOf<Factor>(Table(xs = varsOf, tuples = flat)),
            )
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L)).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "table instance #$idx: enumerated solutions must equal in-domain tuples")
        }
    }
}
