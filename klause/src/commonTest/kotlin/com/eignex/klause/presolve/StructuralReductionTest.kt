package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-factor structural reduction ([Presolve.reduceStructural]), exercised through the factors that
 * implement [com.eignex.klause.solver.Factor.structuralReduce]. Each test asserts the global is
 * rewritten into the simpler factor its structure implies, or left untouched when nothing pins it.
 */
class StructuralReductionTest {

    private fun theLinear(problem: Problem): Linear = problem.factors.filterIsInstance<Linear>().single()

    @Test
    fun `a fixed index into a constant array becomes a result equality`() {
        // idx = 2 (offset 1) selects arr[1] = 20, so result = 20.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(2, 2), IntDomain(0, 100)),
            listOf(Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(listOf(1), eq.vars.toList())
        assertEquals(20L, checkNotNull(eq.integerConstants).bound)
    }

    @Test
    fun `a fixed index into a variable array becomes an equality between result and the selected var`() {
        // idx = 1 (offset 1) selects arr[0] = var 2, so result (var 1) = var 2.
        val problem = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 9) }.also { it[0] = IntDomain(1, 1) },
            listOf(Element(idx = 0, result = 1, arr = longArrayOf(2, 3, 4), arrIsVars = true)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0L, checkNotNull(eq.integerConstants).bound)
        assertEquals(setOf(1, 2), eq.vars.toSet())
    }

    @Test
    fun `a fixed index selecting the result variable itself drops the element`() {
        // arr[0] is var 1 = result, so the constraint is result = result — vacuous.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 9) }.also { it[0] = IntDomain(1, 1) },
            listOf(Element(idx = 0, result = 1, arr = longArrayOf(1, 2), arrIsVars = true)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.isEmpty(), "the vacuous element drops with no replacement")
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
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        assertEquals(7L, checkNotNull(theLinear(out).integerConstants).bound)
        assertEquals(
            1L,
            out.requireFiniteIntDomains()[0].min,
            "index lower bound clamped to the array's first position",
        )
        assertEquals(3L, out.requireFiniteIntDomains()[0].max, "index upper bound clamped to the array's last position")
    }

    @Test
    fun `an unconstrained element is left untouched`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 3), IntDomain(0, 100)),
            listOf(Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "no fixed index and a varied array is the no-op signal")
    }

    @Test
    fun `a two-variable all-different becomes a binary disequality`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is AllDifferent }, "the all-different global is removed")
        val ne = theLinear(out)
        assertEquals(LinearOp.NE, ne.op)
        assertEquals(setOf(0, 1), ne.vars.toSet())
        assertEquals(0L, checkNotNull(ne.integerConstants).bound)
    }

    @Test
    fun `an all-different over three variables is left as a global`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "a 3-var all-different keeps its global form")
    }

    @Test
    fun `a vacuous cardinality drops`() {
        // 0 <= (#true of three literals) <= 3 accepts every assignment.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 0, max = 3)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.isEmpty(), "the vacuous cardinality drops")
    }

    @Test
    fun `a binding cardinality is left untouched`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 0, max = 1)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "an at-most-one still constrains, so it stays")
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
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        val groups = out.factors.filterIsInstance<AllDifferent>().map { it.vars.toSet() }
        assertEquals(setOf(setOf(0, 1, 2), setOf(3, 4, 5)), groups.toSet(), "splits into the two value-disjoint groups")
    }

    @Test
    fun `an all-different over overlapping ranges is left whole`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "one connected component does not split")
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
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is Cumulative && !it.unary }, "the cumulative is reduced to a unary one")
        val disj = out.factors.filterIsInstance<Cumulative>().single { it.unary }
        assertEquals(listOf(0, 1, 2), disj.starts.toList())
    }

    @Test
    fun `a cumulative whose tasks can share the resource is left alone`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            listOf(
                Cumulative(
                    starts = intArrayOf(0, 1, 2),
                    durations = longArrayOf(2, 2, 2),
                    resources = longArrayOf(1, 1, 1),
                    capacity = 4L,
                ),
            ),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "tasks fit together, so it stays cumulative")
    }

    private fun pos(v: Int) = Lit.make(v, true)

    private fun pbCardFeasibleCount(problem: Problem, numBool: Int): Int {
        var count = 0
        for (mask in 0 until (1 shl numBool)) {
            val a = BooleanArray(numBool) { (mask shr it) and 1 == 1 }
            val ok = problem.factors.all { f ->
                when (f) {
                    is PseudoBoolean -> {
                        var s = 0L
                        for (i in f.literals.indices) {
                            if (Lit.evaluate(f.literals[i], a[Lit.variable(f.literals[i])])) {
                                s += f.weights[i]
                            }
                        }
                        when (f.op) {
                            PbOp.LE -> s <= f.bound
                            PbOp.GE -> s >= f.bound
                            PbOp.EQ -> s == f.bound
                        }
                    }

                    is Cardinality -> f.literals.count { Lit.evaluate(it, a[Lit.variable(it)]) } in f.min..f.max

                    else -> true
                }
            }
            if (ok) count++
        }
        return count
    }

    private fun theCardinality(problem: Problem): Cardinality = problem.factors.filterIsInstance<Cardinality>().single()

    private fun checkUnitPbBecomesCardinality(numBool: Int, pb: PseudoBoolean): Problem {
        val problem = Problem(numBool, 0, emptyArray(), listOf(pb))
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertEquals(
            pbCardFeasibleCount(problem, numBool),
            pbCardFeasibleCount(out, numBool),
            "rewrite changed the feasible set",
        )
        assertTrue(out.factors.none { it is PseudoBoolean }, "the pseudo-Boolean is rewritten")
        return out
    }

    @Test
    fun `a unit-weight pseudo-boolean becomes the equivalent cardinality`() {
        listOf(
            Triple(PbOp.LE, 2L, 0 to 2),
            Triple(PbOp.GE, 2L, 2 to 3),
            Triple(PbOp.EQ, 1L, 1 to 1),
        ).forEach { (op, bound, expected) ->
            val out = checkUnitPbBecomesCardinality(
                3,
                PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(pos(0), pos(1), pos(2)), op, bound),
            )
            val card = theCardinality(out)
            assertEquals(expected.first, card.min, "$op $bound: cardinality min")
            assertEquals(expected.second, card.max, "$op $bound: cardinality max")
        }
    }

    @Test
    fun `a non-unit pseudo-boolean is left untouched`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 2)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "a weighted pseudo-Boolean stays as-is")
    }

    @Test
    fun `a vacuous unit-weight bound drops the pseudo-boolean`() {
        // Σlit ≤ 3 over three literals always holds.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(1, 1, 1), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 3)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(
            out.factors.none { it is PseudoBoolean || it is Cardinality },
            "the always-true constraint is dropped",
        )
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
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is Product }, "the product is linearised")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0L, checkNotNull(eq.integerConstants).bound)
        assertEquals(1L, coeffOf(eq, 2), "result keeps unit coefficient")
        assertEquals(-3L, coeffOf(eq, 1), "the operand takes the negated fixed value as coefficient")
    }

    @Test
    fun `a product with a zero operand fixes the result to zero`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 0), IntDomain(0, 100)),
            listOf(Product(a = 0, b = 1, result = 2)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        val eq = theLinear(out)
        assertEquals(listOf(2), eq.vars.toList(), "only the result remains")
        assertEquals(0L, checkNotNull(eq.integerConstants).bound)
    }

    @Test
    fun `a product with two free operands is left untouched`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 100)),
            listOf(Product(a = 0, b = 1, result = 2)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "a genuinely nonlinear product stays")
    }

    @Test
    fun `a single-operand maximum becomes a result equality`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            listOf(ArrayMinMax(result = 0, xs = intArrayOf(1), max = true)),
        )
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is ArrayMinMax }, "the min/max global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0L, checkNotNull(eq.integerConstants).bound)
        assertEquals(setOf(0, 1), eq.vars.toSet())
    }

    @Test
    fun `a multi-operand maximum is left untouched`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            listOf(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "a genuine maximum stays")
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
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
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
        val out = problem.withPassDelta(Presolve.reduceStructural(problem), BakeConfig.NONE)
        assertTrue(out.factors.none { it is NValue }, "the nvalue global is removed")
        assertEquals(2, out.factors.filterIsInstance<Linear>().size, "two equalities chain the three vars")
    }

    @Test
    fun `an nvalue with a free target is left untouched`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(1, 3)),
            listOf(NValue(n = 3, xs = intArrayOf(0, 1, 2))),
        )
        assertTrue(Presolve.reduceStructural(problem).isEmpty, "an unpinned nvalue stays")
    }
}
