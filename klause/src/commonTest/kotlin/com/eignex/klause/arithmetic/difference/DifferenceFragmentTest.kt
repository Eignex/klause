package com.eignex.klause.arithmetic.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gathering a model's difference constraints. The cases that matter are the mixed ones: a real QF_IDL
 * instance is clauses and reified rows around a core of differences, so collecting must compose with
 * structure it does not understand rather than refusing the model.
 */
class DifferenceFragmentTest {

    private fun open(n: Int) = Array(n) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) }

    private fun frag(factors: List<Factor>, n: Int, domains: Array<IntDomain> = open(n)) =
        differenceFragmentOf(factors.toTypedArray(), n, bounds(domains))

    private fun bounds(domains: Array<IntDomain>) = IntBounds.fromOpenSides(
        domains,
        BooleanArray(domains.size) { domains[it].min == Long.MIN_VALUE },
        BooleanArray(domains.size) { domains[it].max == Long.MAX_VALUE },
        null,
        null,
    )

    private fun diff(a: Int, b: Int, op: LinearOp, c: Int) = Linear(intArrayOf(1, -1), intArrayOf(a, b), op, c)

    @Test
    fun `an unconditional difference row becomes an unguarded edge`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2))
        assertEquals(1, f.edges.count { it.guard == DifferenceEdge.ALWAYS })
    }

    @Test
    fun `an open model side does not become a difference range edge`() {
        val domains = arrayOf(IntDomain(-8, 8))
        val bounds = IntBounds.fromOpenSides(domains, booleanArrayOf(true), booleanArrayOf(true), null, null)

        val fragment = assertNotNull(
            differenceFragmentOf(arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)), 1, bounds),
        )

        assertTrue(fragment.edges.none { it.domainBound })
    }

    @Test
    fun `a reified difference row becomes an edge guarded by its aux`() {
        val r = ReifiedLinear(7, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        val f = assertNotNull(frag(listOf(r), 2))
        val guarded = f.edges.single { it.guard == Lit.make(7, true) }
        assertEquals(3L, guarded.bound, "the edge holds exactly when the aux is true")
    }

    @Test
    fun `a reified row also contributes the negation its false aux asserts`() {
        // `¬(x0 − x1 ≤ 3)` is `x1 − x0 ≤ −4` over the integers, a difference in its own right.
        val r = ReifiedLinear(7, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        val f = assertNotNull(frag(listOf(r), 2))
        val negated = f.edges.single { it.guard == Lit.make(7, false) }
        assertEquals(-4L, negated.bound)
    }

    @Test
    fun `a reified equality contributes nothing under a false aux`() {
        // Its negation is a disequality, which is outside the fragment.
        val r = ReifiedLinear(7, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3)
        val f = assertNotNull(frag(listOf(r), 2))
        assertTrue(f.edges.none { it.guard == Lit.make(7, false) })
    }

    @Test
    fun `a clause does not disqualify the model`() {
        // The all-or-nothing predecessor recognised 0 of 30 real instances for exactly this reason.
        val f = assertNotNull(
            frag(listOf(diff(0, 1, LinearOp.LE, 3), Clause(intArrayOf(Lit.make(0, true)))), 2),
        )
        assertTrue(f.edges.any { it.guard == DifferenceEdge.ALWAYS })
    }

    @Test
    fun `a general linear row is left out but the differences are still collected`() {
        val general = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        val f = assertNotNull(frag(listOf(general, diff(0, 1, LinearOp.LE, 3)), 2))
        assertEquals(1, f.edges.size, "the general row contributes no edge and the difference row one")
        assertEquals(3L, f.edges.single().bound, "the edge is the difference row's")
    }

    @Test
    fun `a contradictory pair is refuted by the graph`() {
        val f = assertNotNull(
            frag(listOf(diff(0, 1, LinearOp.LE, -1), diff(1, 0, LinearOp.LE, -1)), 2),
        )
        assertNotNull(f.graph().negativeCycle(), "the pair sums to 0 ≤ −2")
    }

    @Test
    fun `an equality contributes both directions`() {
        val f = assertNotNull(
            frag(listOf(diff(0, 1, LinearOp.EQ, 2), diff(0, 1, LinearOp.LE, 1)), 2),
        )
        assertNotNull(f.graph().negativeCycle())
    }

    @Test
    fun `declared domains enter as differences against zero`() {
        val f = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2, arrayOf(IntDomain(0, 5), IntDomain(0, 5))))
        assertEquals(4, f.edges.count { it.guard == DifferenceEdge.ALWAYS && it.bound != 3L })
    }

    @Test
    fun `a model with no difference rows yields nothing`() {
        val general = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3)
        assertNull(frag(listOf(general), 2), "no edges is not a fragment")
    }

    @Test
    fun `complete difference coverage permits boolean structure`() {
        assertTrue(
            hasCompleteDifferenceCoverage(
                arrayOf(diff(0, 1, LinearOp.LE, 3), Clause(intArrayOf(Lit.make(0, true)))),
            ),
        )
    }

    @Test
    fun `complete difference coverage rejects a general linear row`() {
        assertFalse(
            hasCompleteDifferenceCoverage(
                arrayOf(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3)),
            ),
        )
    }

    @Test
    fun `complete difference coverage rejects a reified equality`() {
        assertFalse(
            hasCompleteDifferenceCoverage(
                arrayOf(ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 3)),
            ),
        )
    }

    @Test
    fun `potential sample satisfies active difference edges`() {
        val fragment = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2))
        val values = assertIs<Potentials.Found>(fragment.potentialSample(2, BooleanArray(0))).values

        assertTrue(values[0] - values[1] <= 3L)
    }

    @Test
    fun `a spent budget abandons the sample instead of reporting infeasible`() {
        val fragment = assertNotNull(frag(listOf(diff(0, 1, LinearOp.LE, 3)), 2))

        assertEquals(Potentials.Abandoned, fragment.potentialSample(2, BooleanArray(0)) { true })
    }
}
