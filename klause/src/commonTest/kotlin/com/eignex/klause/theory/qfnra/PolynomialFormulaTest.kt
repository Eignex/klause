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

class PolynomialFormulaTest {
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
