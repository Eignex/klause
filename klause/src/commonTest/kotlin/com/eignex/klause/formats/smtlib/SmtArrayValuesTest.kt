package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtArrayValuesTest {
    @Test
    fun `Boolean array equality should match every finite truth table`() {
        var comparisons = 0
        val source = SmtLib.parse("(declare-const b Bool)").copy(sourceChecks = listOf(SmtSourceCheck { values ->
            val sort = SmtSort.Array(SmtSort.Bool, SmtSort.Bool)
            for (left in 0..3) for (right in 0..3) {
                val a = SmtValue.Array(sort, SmtValue.Boolean(false), listOf(
                    SmtValue.Boolean(false) to SmtValue.Boolean(left and 1 != 0),
                    SmtValue.Boolean(true) to SmtValue.Boolean(left and 2 != 0)))
                val b = SmtValue.Array(sort, SmtValue.Boolean(true), listOf(
                    SmtValue.Boolean(false) to SmtValue.Boolean(right and 1 != 0),
                    SmtValue.Boolean(true) to SmtValue.Boolean(right and 2 != 0)))
                assertEquals(left == right, values.equal(a, b))
                comparisons++
            }
            true
        }))

        source.reconstructModel({ false }, { "0" }, { "0.0" })

        assertEquals(16, comparisons)
    }

    @Test
    fun `infinite index domains should retain differences outside finite updates`() {
        val source = SmtLib.parse("(declare-const b Bool)").copy(sourceChecks = listOf(SmtSourceCheck { values ->
            val sort = SmtSort.Array(SmtSort.Int, SmtSort.Bool)
            val a = SmtValue.Array(sort, SmtValue.Boolean(false), emptyList())
            val b = SmtValue.Array(sort, SmtValue.Boolean(true),
                listOf(SmtValue.Integer("0") to SmtValue.Boolean(false)))
            values.equal(a, b) == false
        }))

        val model = source.reconstructModel({ false }, { "0" }, { "0.0" })

        assertEquals(1, model.values.size)
    }

    @Test
    fun `unknown free sort cardinality should withhold differing default equality`() {
        val source = SmtLib.parse("(declare-const b Bool)").copy(sourceChecks = listOf(SmtSourceCheck { values ->
            val indexSort = SmtSort.Uninterpreted("U")
            val sort = SmtSort.Array(indexSort, SmtSort.Bool)
            val a = SmtValue.Array(sort, SmtValue.Boolean(false), emptyList())
            val b = SmtValue.Array(sort, SmtValue.Boolean(true), listOf(
                SmtValue.Uninterpreted(indexSort, "u") to SmtValue.Boolean(false)))
            values.equal(a, b) == null
        }))

        val model = source.reconstructModel({ false }, { "0" }, { "0.0" })

        assertEquals(1, model.values.size)
    }

    @Test
    fun `real indexes should overwrite equal exact values with different spellings`() {
        val source = SmtLib.parse("(declare-const x Real) (declare-const y Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values ->
                val sort = SmtSort.Array(SmtSort.Real, SmtSort.Bool)
                val array = SmtValue.Array(sort, SmtValue.Boolean(false), listOf(
                    values.value("x") to SmtValue.Boolean(false), values.value("y") to SmtValue.Boolean(true)))
                smtArraySelect(array, values.value("x"), values) == SmtValue.Boolean(true)
            }))

        val model = source.reconstructModel({ false }, { "0" }, { if (it == 0) "0.5" else "(/ 1.0 2.0)" })

        assertEquals(2, model.values.size)
    }

    @Test
    fun `native algebraic array indexes should compare by exact value`() {
        val arithmetic = AlgebraicArithmetic()
        val first = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = arithmetic.roots(RationalPolynomial.of(-4, 0, 0, 0, 1)).last()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0, 2.0),
            algebraicReals = listOf(first, second))
        val source = SmtLib.parse("(declare-const x Real) (declare-const y Real)").copy(
            sourceChecks = listOf(SmtSourceCheck { values ->
                val sort = SmtSort.Array(SmtSort.Real, SmtSort.Bool)
                val array = SmtValue.Array(sort, SmtValue.Boolean(false),
                    listOf(values.value("x") to SmtValue.Boolean(true)))
                smtArraySelect(array, values.value("y"), values) == SmtValue.Boolean(true)
            }))

        val model = source.reconstructModel(sample)

        assertTrue(model.values[0].second != model.values[1].second)
    }

    @Test
    fun `array indexes should compare extensionally despite redundant updates`() {
        val source = SmtLib.parse("(declare-const b Bool)").copy(sourceChecks = listOf(SmtSourceCheck { values ->
            val indexSort = SmtSort.Array(SmtSort.Bool, SmtSort.Bool)
            val a = SmtValue.Array(indexSort, SmtValue.Boolean(false), emptyList())
            val b = SmtValue.Array(indexSort, SmtValue.Boolean(false), listOf(
                SmtValue.Boolean(true) to SmtValue.Boolean(false)))
            val array = SmtValue.Array(SmtSort.Array(indexSort, SmtSort.Bool), SmtValue.Boolean(false),
                listOf(a to SmtValue.Boolean(true)))
            smtArraySelect(array, b, values) == SmtValue.Boolean(true)
        }))

        val model = source.reconstructModel({ false }, { "0" }, { "0.0" })

        assertEquals(1, model.values.size)
    }

    @Test
    fun `unobserved array ranks should instantiate typed total polymorphic models`() {
        val source = SmtLib.parse("(declare-sort-parameter A) (declare-const a A) " +
            "(declare-fun f (A) A) (assert (= (f a) a))")
        val family = source.originalSource!!.polymorphicModel!!
        val sort = SmtSort.Array(SmtSort.Bool, SmtSort.Bool)

        val model = withSmtModelValues(source, { false }, { "0" }, { "0.0" }, ExecutionControl.Never) { values, _ ->
            val constant = family.instantiate("a", emptyList(), sort, values)
            val function = family.instantiate("f", listOf(sort), sort, values) as SmtValue.Function
            val images = function.entries.map { it.first.single() }
            assertEquals(false, values.equal(images[0], images[1]))
            assertTrue(images.all { it is SmtValue.Array })
            constant
        }

        assertTrue(model is SmtValue.Array)
    }
}
