package com.eignex.klause.solver
import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DifferenceFragmentsTest {
    private fun open(n: Int) = Array(n) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) }

    private fun frag(factors: List<Factor>, n: Int, domains: Array<IntDomain> = open(n)) =
        differenceFragmentOf(factors.toTypedArray(), n, bounds(domains))

    private fun bounds(domains: Array<IntDomain>) = IntBounds.fromFiniteBounds(
        LongArray(domains.size) { domains[it].min },
        LongArray(domains.size) { domains[it].max },
        BooleanArray(domains.size) { domains[it].min == Long.MIN_VALUE },
        BooleanArray(domains.size) { domains[it].max == Long.MAX_VALUE },
        null,
        null,
    )

    private fun diff(a: Int, b: Int, op: LinearOp, c: Int) = Linear(intArrayOf(1, -1), intArrayOf(a, b), op, c)

    @Test
    fun `unconditional difference row should become an unguarded edge`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2))
        assertEquals(1, f.edges.count { it.guard == DifferenceEdge.ALWAYS })
    }

    @Test
    fun `open model side should not become a domain bound edge`() {
        val bounds = IntBounds.fromFiniteBounds(
            longArrayOf(-8),
            longArrayOf(8),
            booleanArrayOf(true),
            booleanArrayOf(true),
            null,
            null,
        )
        val fragment = assertNotNull(
            differenceFragmentOf(arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)), 1, bounds),
        )
        assertTrue(fragment.edges.none { it.domainBound })
    }

    @Test
    fun `reified difference row should become an edge guarded by its aux`() {
        val r = ReifiedLinear(7, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        val f = assertNotNull(frag(listOf(r), 2))
        val guarded = f.edges.single { it.guard == Lit.make(7, true) }
        assertEquals(3L, guarded.bound)
    }

    @Test
    fun `reified difference row should add the negated edge under a false aux`() {
        val r = ReifiedLinear(7, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        val f = assertNotNull(frag(listOf(r), 2))
        val negated = f.edges.single { it.guard == Lit.make(7, false) }
        assertEquals(-4L, negated.bound)
    }

    @Test
    fun `reified equality should add no edge under a false aux`() {
        val r = ReifiedLinear(7, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3)
        val f = assertNotNull(frag(listOf(r), 2))
        assertTrue(f.edges.none { it.guard == Lit.make(7, false) })
    }

    @Test
    fun `clause should not disqualify the fragment`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3), Clause(intArrayOf(Lit.make(0, true)))), 2))
        assertTrue(f.edges.any { it.guard == DifferenceEdge.ALWAYS })
    }

    @Test
    fun `general linear row should be skipped while difference rows are collected`() {
        val general = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        val f = assertNotNull(frag(listOf(general, diff(0, 1, LinearOp.LE, 3)), 2))
        assertEquals(1, f.edges.size)
        assertEquals(3L, f.edges.single().bound)
    }

    @Test
    fun `contradictory difference pair should form a negative cycle`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, -1), diff(1, 0, LinearOp.LE, -1)), 2))
        assertNotNull(f.graph().negativeCycle())
    }

    @Test
    fun `equality should contribute edges in both directions`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.EQ, 2), diff(0, 1, LinearOp.LE, 1)), 2))
        assertNotNull(f.graph().negativeCycle())
    }

    @Test
    fun `declared domains should become edges against zero`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2, arrayOf(IntDomain(0, 5), IntDomain(0, 5))))
        assertEquals(4, f.edges.count { it.guard == DifferenceEdge.ALWAYS && it.bound != 3L })
    }

    @Test
    fun `Long MIN_VALUE lower bound should not wrap into a domain edge`() {
        val modelBounds = IntBounds.fromModelBounds(
            longArrayOf(Long.MIN_VALUE),
            longArrayOf(3),
            null,
            null,
        )

        val fragment = assertNotNull(
            differenceFragmentOf(arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)), 1, modelBounds),
        )

        assertTrue(fragment.edges.none { it.source == 0 && it.target == DifferenceFragment.ZERO && it.domainBound })
    }

    @Test
    fun `fragment should be null without difference rows`() {
        val general = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        assertNull(frag(listOf(general), 2))
    }

    @Test
    fun `complete difference coverage should require only difference and boolean rows`() {
        val cases = listOf(
            arrayOf<Factor>(diff(0, 1, LinearOp.LE, 3), Clause(intArrayOf(Lit.make(0, true)))) to true,
            arrayOf<Factor>(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3)) to false,
            arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3)) to false,
        )
        for ((factors, expected) in cases) {
            assertEquals(expected, hasCompleteDifferenceCoverage(factors), factors.toList().toString())
        }
    }

    @Test
    fun `potential sample should satisfy active difference edges`() {
        val fragment = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2))
        val sample = fragment.potentialSample(2, BooleanArray(0))
        assertTrue(sample is Potentials.Found)
        val values = sample.values
        assertTrue(values[0] - values[1] <= 3L)
    }

    @Test
    fun `potential sample should be abandoned when the budget is spent`() {
        val fragment = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2))
        assertEquals(Potentials.Abandoned, fragment.potentialSample(2, BooleanArray(0)) { true })
    }
}
