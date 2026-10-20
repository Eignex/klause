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
    fun `opposing proportional bounds reconstruct a required definition`() {
        for (constraints in listOf(
            "(<= y (+ (* x x) 2)) (>= (* 2 y) (+ (* 2 x x) 4))",
            "(>= (- y) (- (+ (* x x) 2))) (<= (* (- 3) y) (- (+ (* 3 x x) 6)))",
            "(= z (+ (* x x) 2)) (<= y z) (>= y (+ (* x x) 2))",
        )) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real) (declare-const y Real) (declare-const z Real)
                (assert (and $constraints))
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
                .conjunctiveAtoms(arithmetic, 128))

            val point = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
                .point(atoms, source.realCount, emptyMap()))

            assertEquals(BigFraction.ONE, point.getValue(0), constraints)
            assertEquals(BigFraction.ofLong(3), point.getValue(1), constraints)
            assertEquals(if (constraints.startsWith("(= z")) BigFraction.ofLong(3) else BigFraction.ZERO,
                point.getValue(2), constraints)
        }
    }

    @Test
    fun `contradictory required signs withhold a definition plan`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (> y (+ (* x x) 2)) (<= y (+ (* x x) 2))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val plan = PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
            .prepare(atoms, source.realCount, emptyMap())

        assertNull(plan)
    }

    @Test
    fun `raw term admission precedes consolidation of opposing bounds`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (<= y (+ (* x x) 2)) (>= y (+ (* x x) 2))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val plan = PolynomialWitnessDefinitions(arithmetic, 3, 8, 4096)
            .prepare(atoms, source.realCount, emptyMap())

        assertNull(plan)
    }

    @Test
    fun `interrupted equality inference exposes no partial plan to a fresh invocation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (<= y (+ (* x x) 2)) (>= y (+ (* x x) 2))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        assertNotNull(PolynomialWitnessDefinitions(PolynomialArithmetic(counter), 2048, 8, 4096)
            .prepare(atoms, source.realCount, emptyMap()))
        var spent = 0L
        val interrupted = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= completedWork - 1
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        assertFailsWith<PolynomialInterrupted> {
            PolynomialWitnessDefinitions(PolynomialArithmetic(interrupted), 2048, 8, 4096)
                .prepare(atoms, source.realCount, emptyMap())
        }

        val point = assertNotNull(PolynomialWitnessDefinitions(PolynomialArithmetic(), 2048, 8, 4096)
            .point(atoms, source.realCount, emptyMap()))
        assertEquals(BigFraction.ofLong(3), point.getValue(1))
    }

    @Test
    fun `fixed coordinates survive reconstruction from opposing bounds`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (<= y (+ (* x x) 2)) (>= y (+ (* x x) 2))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        val fixed = mapOf(0 to BigFraction.ofLong(3))

        val point = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
            .point(atoms, source.realCount, fixed))

        assertEquals(fixed.getValue(0), point.getValue(0))
        assertEquals(BigFraction.ofLong(11), point.getValue(1))
    }

    @Test
    fun `prepared definitions reconstruct each proposal independently`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real) (declare-const z Real) (declare-const unused Real)
            (assert (and (= y (* x x)) (= z (+ y 1))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        val plan = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
            .prepare(atoms, source.realCount, emptyMap()))

        val first = assertNotNull(plan.point(0, BigFraction.ofLong(2)))
        val second = assertNotNull(plan.point(0, BigFraction.ofLong(3)))
        val seed = assertNotNull(plan.point())

        assertEquals(BigFraction.ofLong(4), first.getValue(1))
        assertEquals(BigFraction.ofLong(5), first.getValue(2))
        assertEquals(BigFraction.ofLong(10), second.getValue(2))
        assertEquals(BigFraction.ONE, seed.getValue(0))
        assertEquals(BigFraction.ZERO, first.getValue(3))
    }

    @Test
    fun `prepared proposals preserve fixed and dependent coordinates`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const fixed Real) (assert (= y (* x x)))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        val plan = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
            .prepare(atoms, source.realCount, mapOf(2 to BigFraction.ofLong(3))))

        for (variable in listOf(1, 2)) assertNull(plan.point(variable, BigFraction.ofLong(2)))
    }

    @Test
    fun `prepared axes decline coefficient growth before root isolation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real) (declare-const z Real)
            (assert (and (= y (* x x)) (<= (* 2 x z) 1)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        val plan = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 2)
            .prepare(atoms, source.realCount, mapOf(2 to BigFraction.ofLong(3))))

        val axis = plan.axis(plan.residualAtoms.single().polynomial, 0)

        assertNull(axis)
    }

    @Test
    fun `boundary proposals retain complete Boolean and strict source obligations`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real) (declare-const unused Real)
            (assert (and (= y (* x x)) (< (* x x) 2) (or (= y (- 1)) (> x 1))))
            """.trimIndent(),
        )
        val algebraic = AlgebraicArithmetic()

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        val square = algebraic.power(point.realValue(0), 2)
        assertTrue(algebraic.compare(point.realValue(0), algebraic.rational(BigFraction.ONE)) > 0)
        assertTrue(algebraic.compare(square, algebraic.rational(BigFraction.ofLong(2))) < 0)
        assertEquals(0, algebraic.compare(square, point.realValue(1)))
        assertEquals(BigFraction.ZERO, point.realValue(2).rational)
    }

    @Test
    fun `a source boundary cannot satisfy a strict guarded alternative`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (= y (* x x)) (<= (* x x) 2) (or (> y 2) (< y 0))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `containing work exhaustion withholds a reconstructed boundary witness`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (and (= y (* x x)) (< (* x x) 2) (or (= y (- 1)) (> x 1))))
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 2))

        val result = solveQfNra(source.formula, 0, 2,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

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
    fun `unary comparisons preserve rational thresholds and coefficient polarity`() {
        for (comparison in listOf(
            "(> x (/ 3 2))", "(< x (/ (- 3) 2))", "(>= (* (- 2) x) 1)",
            "(<= (+ (* (- 2) x) 1) 0)", "(= (+ (* 2 x) 1) 0)", "(distinct (+ (* 2 x) 1) 3)",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert (and (= y (* x x)) $comparison))",
            )
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
                .conjunctiveAtoms(arithmetic, 128))

            val point = assertNotNull(PolynomialWitnessDefinitions(arithmetic, 2048, 8, 4096)
                .point(atoms, 2, emptyMap()))

            val captured = point.mapValues { algebraic.rational(it.value) }
            assertTrue(atoms.all { it.holds(algebraic.sign(algebraic.evaluate(it.polynomial, captured))) }, comparison)
        }
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
