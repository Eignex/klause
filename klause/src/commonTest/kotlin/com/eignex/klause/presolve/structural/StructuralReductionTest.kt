package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralReductionTest {

    private fun theLinear(problem: Problem): Linear = problem.factors.filterIsInstance<Linear>().single()

    /** What [Presolve.reduceStructural] rewrites [problem] to, materialized from its delta. */
    private fun reduced(problem: Problem): Problem =
        problem.bake().let { it.withPassDelta(Presolve.reduceStructural(it), BakeConfig.NONE) }

    /** A fixed index into a constant array, with the result column open above when [openResult] is set. */
    private fun fixedElement(openResult: Boolean) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(2, 2), IntDomain(0, 100)),
        factors = listOf(Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false)),
        openIntHi = booleanArrayOf(false, openResult),
    )

    @Test
    fun `the source form rewrites a global whose columns are all closed`() {
        val delta = StructuralReduction.reduceSource(fixedElement(openResult = false))

        assertEquals(listOf(0), delta.droppedIndices.toList())
        assertTrue(delta.addedFactors.single() is Linear)
    }

    @Test
    fun `the source form leaves a global over an open column alone`() {
        val delta = StructuralReduction.reduceSource(fixedElement(openResult = true))

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a constant array of one value fixes the result and tightens the index range`() {
        // Every entry is 7, so result = 7; dropping the element keeps idx in its valid range [1, 3].
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 10)),
            listOf(Element(idx = 0, result = 1, arr = longArrayOf(7, 7, 7), arrIsVars = false)),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        assertEquals(7L, checkNotNull(theLinear(out).integerConstants).bound)
        assertEquals(
            1L,
            out.finiteIntDomain(0).min,
            "index lower bound clamped to the array's first position",
        )
        assertEquals(3L, out.finiteIntDomain(0).max, "index upper bound clamped to the array's last position")
    }

    @Test
    fun `a two-variable all-different becomes a binary disequality`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is AllDifferent }, "the all-different global is removed")
        val ne = theLinear(out)
        assertEquals(LinearOp.NE, ne.op)
        assertEquals(setOf(0, 1), ne.vars.toSet())
        assertEquals(0L, checkNotNull(ne.integerConstants).bound)
    }

    @Test
    fun `an all-different over value-disjoint groups splits into independent all-differents`() {
        // x0..x2 live in [0,5], x3..x5 in [10,15] — the two ranges cannot share a value.
        val problem = Problem(
            0,
            6,
            arrayOf(
                IntDomain(0, 5),
                IntDomain(0, 5),
                IntDomain(0, 5),
                IntDomain(10, 15),
                IntDomain(10, 15),
                IntDomain(10, 15),
            ),
            listOf(AllDifferent(intArrayOf(0, 1, 2, 3, 4, 5), domainMin = 0, domainSize = 16)),
        )
        val out = reduced(problem)
        val groups = out.factors.filterIsInstance<AllDifferent>().map { it.vars.toSet() }
        assertEquals(setOf(setOf(0, 1, 2), setOf(3, 4, 5)), groups.toSet(), "splits into the two value-disjoint groups")
    }

    @Test
    fun `a cumulative whose tasks cannot share the resource becomes a disjunctive`() {
        // Three tasks each demanding 3 of capacity 4: no two fit together, so it is a no-overlap.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            listOf(
                Cumulative(
                    starts = intArrayOf(0, 1, 2),
                    durations = longArrayOf(2, 2, 2),
                    resources = longArrayOf(3, 3, 3),
                    capacity = 4L,
                ),
            ),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is Cumulative && !it.unary }, "the cumulative is reduced to a unary one")
        val disj = out.factors.filterIsInstance<Cumulative>().single { it.unary }
        assertEquals(listOf(0, 1, 2), disj.starts.toList())
    }

    private fun coeffOf(linear: Linear, v: Int): Long = checkNotNull(
        linear.integerConstants,
    ).coeffs[linear.vars.indexOf(v)]

    @Test
    fun `a fixed operand turns a product into a linear equality`() {
        // a = 3 fixed, so result = 3·b, i.e. result − 3·b = 0.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(3, 3), IntDomain(0, 10), IntDomain(0, 100)),
            listOf(Product(a = 0, b = 1, result = 2)),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is Product }, "the product is linearised")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0L, checkNotNull(eq.integerConstants).bound)
        assertEquals(1L, coeffOf(eq, 2), "result keeps unit coefficient")
        assertEquals(-3L, coeffOf(eq, 1), "the operand takes the negated fixed value as coefficient")
    }

    @Test
    fun `an nvalue with target equal to arity becomes all-different`() {
        // n (var 3) = 3 = |xs| forces the three counted vars pairwise distinct.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(3, 3)),
            listOf(NValue(n = 3, xs = intArrayOf(0, 1, 2))),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is NValue }, "the nvalue global is removed")
        val ad = out.factors.filterIsInstance<AllDifferent>().single()
        assertEquals(listOf(0, 1, 2), ad.vars.toList())
    }

    @Test
    fun `an nvalue with target one forces all values equal`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(1, 1)),
            listOf(NValue(n = 3, xs = intArrayOf(0, 1, 2))),
        )
        val out = reduced(problem)
        assertTrue(out.factors.none { it is NValue }, "the nvalue global is removed")
        assertEquals(2, out.factors.filterIsInstance<Linear>().size, "two equalities chain the three vars")
    }

}
