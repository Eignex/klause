package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.PolynomialSelection
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialFormulaTest {
    @Test
    fun `required atom collection extends a prepared root closure with constant truth`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and b (or (> 0 1) (> x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val prepared = formula.prepareBooleanDefinitions(arithmetic)

        val atoms = assertNotNull(prepared.forcedAtoms())

        assertEquals(listOf(formula.atoms.last()), atoms.filter { it.polynomial.mainVariable != null })
    }

    @Test
    fun `constant predicates can contradict a prepared root closure`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and b (> 0 1) (> x 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val prepared = formula.prepareBooleanDefinitions(arithmetic)

        val atoms = prepared.forcedAtoms()

        assertNull(atoms)
    }

    @Test
    fun `a contradictory Boolean root withholds required atoms`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and b (not b) (> x 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val prepared = formula.prepareBooleanDefinitions(arithmetic)

        val atoms = prepared.forcedAtoms()

        assertNull(atoms)
    }

    @Test
    fun `required atoms follow the rewritten Boolean graph`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (and (= b (> x 0)) b))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val prepared = formula.prepareBooleanDefinitions(arithmetic)
        val rewritten = assertNotNull(prepared.formula)

        val atoms = prepared.forcedAtoms()

        assertEquals(rewritten.atoms, atoms)
    }

    @Test
    fun `required atom collection retains its containing stop after preparation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and b (or (> 0 1) (> x 0))))",
        )
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val prepared = formula.prepareBooleanDefinitions(arithmetic)
        stopped = true

        assertFailsWith<PolynomialInterrupted> { prepared.forcedAtoms() }
    }

    @Test
    fun `rational scalar equivalent comparisons preserve shared Boolean truth`() {
        val relations = listOf("=" to "=", "distinct" to "distinct", "<" to ">", "<=" to ">=",
            ">" to "<", ">=" to "<=")
        for ((left, reversed) in relations) for ((right, scalar) in listOf(left to "3", reversed to "(- 3)")) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (declare-const b Bool) (declare-const c Bool) " +
                    "(assert (and (= b ($left (- x y) 0)) (= c ($right (* $scalar (- x y)) 0)) " +
                    "(not (= b c))))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = formula.refuteBooleans(arithmetic, 128, 2048, 4096)

            assertTrue(refuted)
        }
    }

    @Test
    fun `complementary scalar comparisons cannot have equal Boolean truth`() {
        for ((left, right) in listOf("=" to "distinct", "<" to ">=", "<=" to ">")) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (declare-const b Bool) (declare-const c Bool) " +
                    "(assert (and (= b ($left (- x y) 0)) (= c ($right (* 3 (- x y)) 0)) (= b c)))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = formula.refuteBooleans(arithmetic, 128, 2048, 4096)

            assertTrue(refuted)
        }
    }

    @Test
    fun `weak and strict comparisons retain different truth at zero`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (= x 0) (= b (<= x 0)) (= c (< x 0)) b (not c)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ZERO, point.realValue(0).rational)
        assertEquals(true, point.boolValue(0))
        assertEquals(false, point.boolValue(1))
    }

    @Test
    fun `a variable factor retains its singular Boolean truth`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (= x 0) (= y 1) (= b (= (* x y) 0)) (= c (= y 0)) b (not c)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ZERO, point.realValue(0).rational)
        assertEquals(BigFraction.ONE, point.realValue(1).rational)
        assertEquals(true, point.boolValue(0))
        assertEquals(false, point.boolValue(1))
    }

    @Test
    fun `total graph substitution preserves equivalent predicate truth across alternatives`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const z Real) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (= z (+ (* 2 x) 1)) (= b (< z 1)) (= c (< x 0)) (not (= b c))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()

        val regions = reduction.formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertTrue(regions.isEmpty())
    }

    @Test
    fun `shared scalar predicates retract conditioned Boolean pins`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (= b (> x 0)) (= c (< (* (- 3) x) 0))))",
        )
        val invocation = PolynomialTheoryService(source.formula, source.boolCount, source.realCount).invocation()
        val fixed = mapOf(0 to BigFraction.ONE)

        try {
            for (other in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to true, 1 to other),
                    lower = fixed, upper = fixed))

                if (other) {
                    val point = assertIs<QfNraResult.Satisfiable>(result).assignment
                    assertEquals(BigFraction.ONE, point.realValue(0).rational)
                    assertTrue(point.boolValue(0))
                    assertTrue(point.boolValue(1))
                } else assertIs<QfNraResult.Unsatisfiable>(result)
            }
        } finally {
            invocation.close()
        }
    }

    @Test
    fun `cancelled predicate sharing exposes no replacement formula`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val arithmetic = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { formula.withAtoms(formula.atoms, arithmetic) }
    }

    @Test
    fun `witness guidance retains only unconditional signed atoms`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (= y (* x x)) (not (<= x 0))
              (or (= y 5) (= x 6)) (not (and (< x 0) (< y 0)))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = assertNotNull(formula.witnessAtoms(arithmetic, 128))

        assertEquals(2, atoms.size)
        assertEquals(setOf(PolynomialRelation.EQ, PolynomialRelation.GT), atoms.map { it.relation }.toSet())
        assertNull(formula.conjunctiveAtoms(arithmetic, 128))
    }

    @Test
    fun `witness guidance admission withholds partial atoms`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (> x 0) (> y 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val atoms = formula.witnessAtoms(arithmetic, 1)

        assertNull(atoms)
    }

    @Test
    fun `cancelled witness guidance exposes no partial atoms`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val arithmetic = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { formula.witnessAtoms(arithmetic, 128) }
    }

    @Test
    fun `private selections should preserve shared Boolean branch correlation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const b Bool)
            (assert (= (ite b (* x x) 3) 4))
            (assert (= (ite b 5 (* x x)) 4))
            """.trimIndent(),
        )
        val fixed = mapOf(0 to BigFraction.ofLong(2), 1 to BigFraction.ofLong(4), 2 to BigFraction.ofLong(4))

        val result = solveQfNra(
            source.formula,
            source.boolCount,
            source.realCount,
            condition = PolynomialCondition(lower = fixed, upper = fixed),
        )

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `selection invocations should retract Boolean pins between exact completions`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const r Real) (declare-const b Bool)
            (assert (= r (ite b (* x x) 3)))
            """.trimIndent(),
        )
        val invocation = PolynomialTheoryService(
            source.formula,
            source.boolCount,
            source.realCount,
            mapOf(0 to BigFraction.ofLong(2)),
        ).invocation()

        try {
            for ((branch, expected) in listOf(true to 4L, false to 3L, true to 4L)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to branch)))

                val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(BigFraction.ofLong(expected), assignment.realValue(1).rational)
                assertEquals(branch, assignment.boolValue(0))
            }
        } finally {
            invocation.close()
        }
    }

    @Test
    fun `cancelled private selection encoding should expose no partial normalized formula`() {
        val source = PolynomialSelection(
            SourceRealId(0),
            PolynomialRealFormula.BooleanVariable(SourceBoolId(0)),
            PolynomialExpression.Constant.of(bigIntOf(1)),
            PolynomialExpression.Constant.of(bigIntOf(2)),
        )
        val arithmetic = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { PolynomialFormula.normalize(source, arithmetic) }
    }
}
