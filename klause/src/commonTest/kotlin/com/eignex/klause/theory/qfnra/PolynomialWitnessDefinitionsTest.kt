package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialWitnessDefinitionsTest {
    @Test
    fun `completion reconstructs chained definitions and unused declarations`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real) (declare-const z Real) (declare-const unused Real)
            (assert (and (= x (* y y)) (= y (+ z 1)) (< z 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val point = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
            .point(atoms, source.realCount, emptyMap()))

        assertEquals(4, point.size)
        assertEquals(point.getValue(1) * point.getValue(1), point.getValue(0))
        assertEquals(point.getValue(2) + BigFraction.ONE, point.getValue(1))
        assertTrue(point.getValue(2) < BigFraction.ZERO)
        assertEquals(BigFraction.ZERO, point.getValue(3))
    }

    @Test
    fun `a nonconstant coefficient cannot define a coordinate`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= (* x y) 1))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096).point(atoms, 2, emptyMap())

        assertNull(point)
    }

    @Test
    fun `fixed coordinates survive reconstruction`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= y (+ x 1)))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        val fixed = mapOf(0 to BigFraction.ofLong(2))

        val point = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096).point(atoms, 2, fixed))

        assertEquals(fixed.getValue(0), point.getValue(0))
        assertEquals(point.getValue(0) + BigFraction.ONE, point.getValue(1))
    }

    @Test
    fun `admission decline supplies no partial point`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= x (* 3 y y)))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        for ((terms, degree, bits) in listOf(Triple(1, 8, 4096), Triple(2048, 1, 4096), Triple(2048, 8, 1))) {
            assertNull(PolynomialWitnessDefinitions(arithmetic, terms, degree, bits).point(atoms, 2, emptyMap()))
        }
    }

    @Test
    fun `interruption supplies no partially reconstructed point`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= x (* y y)))",
        )
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .conjunctiveAtoms(PolynomialArithmetic(), 128))
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        assertNotNull(PolynomialWitnessDefinitions(PolynomialArithmetic(counter), 2048, 8, 4096)
            .point(atoms, 2, emptyMap()))

        for (limit in listOf(1L, completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val interrupted = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                PolynomialWitnessDefinitions(PolynomialArithmetic(interrupted), 2048, 8, 4096)
                    .point(atoms, 2, emptyMap())
            }
        }
    }

    @Test
    fun `guarded definitions leave a feasible alternative available`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (or (and (= x (* y y)) (< x 0)) (and (= x 1) (= y 0))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ONE, point.realValue(0).rational)
        assertEquals(BigFraction.ZERO, point.realValue(1).rational)
    }

    @Test
    fun `a rejected reconstructed point preserves complete source search`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= x (* y y)) (> x 2) (< y 0)))",
        )
        val algebraic = AlgebraicArithmetic()

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(point.realValue(0), algebraic.power(point.realValue(1), 2)))
        assertTrue(algebraic.sign(point.realValue(1)) < 0)
        assertTrue(algebraic.compare(point.realValue(0), algebraic.rational(BigFraction.ofLong(2))) > 0)
    }
}
