package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.model.PbOp
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * At-most-one clique extraction (the reusable builder behind clique-aware presolve and the planned
 * clique-aware local-search moves). Each test asserts which cliques a factor set yields.
 */
class PresolveSharedTest {

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `at-most-one cardinality yields one clique of its literals`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 0, max = 1)),
        )
        val cliques = Presolve.amoCliques(problem)
        assertEquals(listOf(setOf(pos(0), pos(1), pos(2))), cliques)
    }

    @Test
    fun `binary clause yields the clique of its negated literals`() {
        // The clause l0 v l1 is exactly at most one of {not l0, not l1}.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val cliques = Presolve.amoCliques(problem)
        assertEquals(listOf(setOf(pos(0), pos(1))), cliques)
    }

    @Test
    fun `a three-literal clause contributes no clique`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Clause(intArrayOf(pos(0), pos(1), pos(2)))),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a plain linear contributes no clique`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a non-unit-max cardinality contributes no clique`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 0, max = 2)),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a knapsack whose weights cannot pairwise exceed the bound contributes no clique`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(1, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 2L)),
        )
        assertTrue(Presolve.amoCliques(problem).isEmpty())
    }

    @Test
    fun `a knapsack whose every pair exceeds the bound yields an at-most-one clique`() {
        // x0 + x1 <= 1 is exactly at-most-one over {x0, x1}.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(1, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 1L)),
        )
        assertEquals(listOf(setOf(pos(0), pos(1))), Presolve.amoCliques(problem))
    }

    @Test
    fun `a knapsack yields a clique only over the large-weight literals whose pairs exceed the bound`() {
        // 5*x0 + 4*x1 + 1*x2 <= 6: x0+x1 = 9 > 6 exclude, but x2 pairs (6, 5) do not exceed 6.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(5, 4, 1), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6L)),
        )
        assertEquals(listOf(setOf(pos(0), pos(1))), Presolve.amoCliques(problem))
    }

    @Test
    fun `an exactly-one cardinality yields the clique of its literals`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 1, max = 1)),
        )
        assertEquals(listOf(setOf(pos(0), pos(1), pos(2))), Presolve.amoCliques(problem))
    }

    @Test
    fun `overlapping binary cliques merge into one maximal clique`() {
        // The three clauses pin at-most-one over each pair {x0,x1}, {x1,x2}, {x0,x2} — a triangle that
        // collapses to a single at-most-one over all three.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        )
        assertEquals(listOf(setOf(pos(0), pos(1), pos(2))), Presolve.amoCliques(problem))
    }

    @Test
    fun `a literal joins a clique only when it conflicts with every member`() {
        // 3 is adjacent to 1 and 2 but not to 0, so it may not extend the base clique {0,1,2}; the pair
        // it does form survives on its own.
        val merged = PresolveShared.mergeCliques(
            listOf(setOf(0, 1, 2), setOf(1, 3), setOf(2, 3)),
        )
        assertEquals(setOf(setOf(0, 1, 2), setOf(1, 2, 3)), merged.toSet())
    }

    @Test
    fun `a clique that is a subset of a larger one is dropped`() {
        val merged = PresolveShared.mergeCliques(listOf(setOf(0, 1, 2), setOf(0, 1)))
        assertEquals(listOf(setOf(0, 1, 2)), merged)
    }

    @Test
    fun `disjoint cliques are left unmerged`() {
        val cliques = listOf(setOf(0, 1), setOf(2, 3))
        assertEquals(cliques.toSet(), PresolveShared.mergeCliques(cliques).toSet())
    }

    @Test
    fun `the merge is independent of the order the base cliques arrive in`() {
        // Extension is greedy, so it is only deterministic because candidates are taken in id order —
        // permuting the input must not change the result.
        val base = listOf(setOf(0, 1), setOf(1, 2), setOf(0, 2), setOf(2, 3), setOf(1, 3), setOf(0, 3))
        val expected = PresolveShared.mergeCliques(base).toSet()

        assertEquals(expected, PresolveShared.mergeCliques(base.reversed()).toSet())
        assertEquals(expected, PresolveShared.mergeCliques(base.sortedBy { it.sum() }).toSet())
    }

    @Test
    fun `a cancelled merge returns the base cliques unextended`() {
        // The triangle would collapse to one size-3 clique; cancelling forgoes the growth but every
        // returned clique is still a valid at-most-one.
        val base = listOf(setOf(0, 1), setOf(1, 2), setOf(0, 2))
        val merged = PresolveShared.mergeCliques(base) { true }

        assertEquals(base.toSet(), merged.toSet())
    }

    @Test
    fun `negative literals merge like positive ones`() {
        // Lit encoding makes members arbitrary ints, including negative ones, so the conflict graph
        // cannot assume dense non-negative keys.
        val merged = PresolveShared.mergeCliques(listOf(setOf(-1, -2), setOf(-2, -3), setOf(-1, -3)))
        assertEquals(listOf(setOf(-1, -2, -3)), merged)
    }

    @Test
    fun `maxIntSpan reports the widest integer domain span`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(-5, 10), IntDomain(7, 7)),
            emptyList(),
        )
        assertEquals(15L, PresolveShared.maxIntSpan(problem))
    }

    @Test
    fun `maxIntSpan saturates an overflowing span rather than wrapping negative`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)), emptyList())
        assertEquals(Long.MAX_VALUE, PresolveShared.maxIntSpan(problem))
    }
}
