package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmtFixedModelTest {
    @Test
    fun `declared pi keeps its retained value instead of the real-function constant`() {
        val parsed = SmtLib.parse("(declare-const pi Real)")
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0)))

        val result = model.evaluate(SExpr.Atom("pi"))

        assertEquals("0.0", result)
    }

    @Test
    fun `declared exp keeps its total function table instead of the real-function extension`() {
        val parsed = SmtLib.parse("(declare-fun exp (Real) Real)")
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf()))

        val result = model.evaluate(SExprReader("(exp 1.0)").readAll().single())

        assertEquals("0.0", result)
    }

    @Test
    fun `direct source reconstruction retains implicit named definitions`() {
        val parsed = SmtLib.parse("(assert (! true :named label))")
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf()))

        val result = model.evaluate(SExpr.Atom("label"))

        assertEquals("true", result)
    }

    @Test
    fun `quoted indexed operator symbols retain their standard bitvector identity`() {
        val parsed = SmtLib.parse("")
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf()))

        val result = model.evaluate(SExprReader("((_ |extract| 0 0) (_ |bv10| 4))").readAll().single())

        assertEquals("#b0", result)
    }

    @Test
    fun `qualified helper calls retain quoted grammar-word identities`() {
        val parsed = SmtLib.parse("(define-fun |let| ((x Int)) Int (+ x 1))")
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf()))

        val result = model.evaluate(SExprReader("((as |let| Int) 2)").readAll().single())

        assertEquals("3", result)
        assertEquals("true", model.evaluate(SExpr.Atom("|true|")))
    }

    @Test
    fun `polymorphic helper queries specialize type qualifiers at their actual rank`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (define-fun identity ((x A)) A x)
        """.trimIndent())
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf()))

        val result = model.evaluate(SExprReader("((as identity (_ BitVec 4)) #b1010)").readAll().single())

        assertEquals("#b1010", result)
    }

    @Test
    fun `algebraic queries retain native authority across different root representations`() {
        val roots = AlgebraicArithmetic()
        val first = roots.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = roots.roots(RationalPolynomial.of(-4, 0, 0, 0, 1)).last()
        val parsed = SmtLib.parse("(declare-const a Real) (declare-const b Real)")
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0, 0.0),
            algebraicReals = listOf(first, second))

        val model = parsed.reconstructSessionModel(sample)

        assertEquals("true", model.evaluate(SExprReader("(= a b)").readAll().single()))
        assertEquals("true", model.evaluate(SExprReader("(= (- a b) 0.0)").readAll().single()))
    }

    @Test
    fun `division queries reuse the checked zero divisor interpretation`() {
        val values = object : SmtModelValues {
            override fun checkpoint(units: Long) = Unit
            override fun boolean(name: String): Boolean = error(name)
            override fun integer(name: String): String = error(name)
            override fun real(name: String): String = error(name)
            override fun value(name: String): SmtValue = when (name) {
                "@operation:div" -> SmtValue.Function(listOf(SmtSort.Int, SmtSort.Int),
                    listOf(listOf(SmtValue.Integer("3"), SmtValue.Integer("0")) to SmtValue.Integer("7")),
                    SmtValue.Integer("9"))
                else -> error(name)
            }
        }
        val model = SmtFixedModel(emptyList(), SmtSourceModel(emptyList()), values, ExecutionControl.Never)

        val observed = model.evaluate(SExprReader("(div 3 0)").readAll().single())
        val unseen = model.evaluate(SExprReader("(div 4 0)").readAll().single())

        assertEquals("7", observed)
        assertEquals("9", unseen)
    }

    @Test
    fun `bitvector queries preserve indexed wiring and overflow semantics`() {
        val parsed = SmtLib.parse("(declare-const x (_ BitVec 4))")
        val model = parsed.reconstructSessionModel(Sample(BooleanArray(4), longArrayOf()))

        val term = SExprReader("((_ extract 3 0) (concat #b1111 (bvadd x #b1010)))").readAll().single()

        assertEquals("#b1010", model.evaluate(term))
        assertEquals("true", model.evaluate(SExprReader("(bvuaddo #b1111 #b0001)").readAll().single()))
    }

    @Test
    fun `queries reject malformed arities before touching the retained model`() {
        val parsed = SmtLib.parse("(declare-const p Bool)")
        val model = parsed.reconstructSessionModel(Sample(booleanArrayOf(true), longArrayOf()))

        assertFailsWith<UnsupportedSmtException> {
            model.evaluate(SExprReader("(not p p)").readAll().single())
        }
        assertEquals("true", model.evaluate(SExpr.Atom("p")))
    }

    @Test
    fun `lazy polymorphic queries keep the fixed family at a previously unseen rank`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const x A)
            (assert (= x x))
        """.trimIndent())
        val sample = Sample(BooleanArray(parsed.encodingBoolVarNames.size),
            LongArray(parsed.encodingIntVarNames.size), DoubleArray(parsed.encodingRealVarNames.size))
        val model = parsed.reconstructSessionModel(sample)
        val term = SExprReader("(as x (_ BitVec 129))").readAll().single()

        val first = model.evaluate(term)
        val second = model.evaluate(term)

        assertEquals("#b" + "0".repeat(129), first)
        assertEquals(first, second)
    }
}
