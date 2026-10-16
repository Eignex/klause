package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
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

class PolynomialWitnessSupportsTest {
    @Test
    fun `disjoint equality proposals satisfy coupling rows in declaration orders`() {
        for (reversed in listOf(false, true)) {
            val names = listOf("a", "b", "c", "d", "e", "f")
            val declarations = (if (reversed) names.reversed() else names)
                .joinToString(" ") { "(declare-const $it Real)" }
            val source = SmtLibPolynomial.parse(
                """
                $declarations
                (assert (and (= (+ (* a a) (* b b)) 1) (= (+ (* c c) (* d d)) 1)
                  (= (+ (* e e) (* f f)) 1)
                  (>= (+ (* (- a c) (- a c)) (* (- b d) (- b d))) 2)
                  (>= (+ (* (- a e) (- a e)) (* (- b f) (- b f))) 2)
                  (>= (+ (* (- c e) (- c e)) (* (- d f) (- d f))) 2)))
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            var axes = 0
            var proposals = 0

            val point = assertNotNull(PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
                atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(),
                takeAxis = { ++axes <= 32 }, takeProposal = { ++proposals <= 63 },
            ))

            val coordinates = point.mapValues { algebraic.rational(it.value) }
            for (atom in atoms) {
                assertTrue(atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))))
            }
        }
    }

    @Test
    fun `source coefficients and strict inequalities guide local roots`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real)
            (assert (and (= (+ (* 4 a a) (* b b)) 4) (= (+ (* c c) (* d d)) 9)
              (> a 0) (< (* a c) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val point = assertNotNull(PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        ))

        val coordinates = point.mapValues { algebraic.rational(it.value) }
        for (atom in atoms) {
            assertTrue(atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))))
        }
    }

    @Test
    fun `fixed source coordinates stay protected during support joins`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real)
            (assert (and (= (+ (* a a) (* b b)) 1) (= (+ (* c c) (* d d)) 1)
              (>= (+ (* (- a c) (- a c)) (* (- b d) (- b d))) 2)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = assertNotNull(PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, mapOf(0 to BigFraction.ONE),
            { true }, { true },
        ))

        assertEquals(BigFraction.ONE, point.getValue(0))
    }

    @Test
    fun `unused declarations retain their captured zero seed`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const unused Real)
            (assert (and (= (* a a) 4) (= (* b b) 9)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = assertNotNull(PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        ))

        assertEquals(BigFraction.ZERO, point.getValue(2))
    }

    @Test
    fun `zero seed can satisfy a singular equality support`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real)
            (assert (and (= (* a b) 0) (= (+ (* c c) (* d d)) 1) (> c 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val point = assertNotNull(PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        ))

        val coordinates = point.mapValues { algebraic.rational(it.value) }
        for (atom in atoms) {
            assertTrue(atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))))
        }
    }

    @Test
    fun `overlapping equality supports decline a partial join`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const c Real)
            (assert (and (= (+ (* a a) (* b b)) 1) (= (+ (* b b) (* c c)) 1)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        )

        assertNull(point)
    }

    @Test
    fun `missing sparse roots do not refute a dense feasible source`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real)
            (assert (and (= (* a b) 1) (= (* c d) 1)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        )

        assertNull(point)
        assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))
    }

    @Test
    fun `nonrational roots cannot be replaced by isolating endpoints`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (assert (and (= (* a a) 2) (= (* b b) 3)))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        )

        assertNull(point)
    }

    @Test
    fun `coordinates outside equality supports retain their source obligations`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const outside Real)
            (assert (and (= (* a a) 4) (= (* b b) 9) (> outside 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        )

        assertNull(point)
    }

    @Test
    fun `guarded source restrictions stay with the complete witness check`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real)
            (assert (and (= (* a a) 4) (= (* b b) 9) (or (> a 0) (> b 0))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val point = assertNotNull(WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
            .probeWitness(source.realCount, emptyMap()) { null })

        val coordinates = (0 until source.realCount).associateWith { point.realValue(it) }
        val values = IntArray(formula.atoms.size) { index ->
            val atom = formula.atoms[index]
            if (atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))))
                PolynomialFormula.TRUE else PolynomialFormula.FALSE
        }
        assertEquals(PolynomialFormula.TRUE, formula.truth(IntArray(source.boolCount), values, arithmetic))
    }

    @Test
    fun `admission limits withhold unsupported support proposals`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (assert (and (= (* a a) 4) (= (* b b) 9)))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        for ((terms, degree, bits) in listOf(Triple(1, 8, 4096), Triple(2048, 1, 4096), Triple(2048, 8, 2))) {
            val point = PolynomialWitnessSupports(arithmetic, terms, degree, bits, 4).point(
                atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
            )

            assertNull(point)
        }
    }

    @Test
    fun `axis and proposal limits withhold an unfinished join`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (assert (and (= (* a a) 4) (= (* b b) 9)))",
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))
        for ((axisLimit, proposalLimit) in listOf(0 to 64, 1 to 64, 32 to 0, 32 to 2)) {
            var axes = 0
            var proposals = 0

            val point = PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
                atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(),
                { ++axes <= axisLimit }, { ++proposals <= proposalLimit },
            )

            assertNull(point)
        }
    }

    @Test
    fun `specialized coefficient growth declines before root isolation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const fixed Real)
            (assert (and (= (* a a) (* fixed fixed)) (= (* b b) 1)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic)
            .conjunctiveAtoms(arithmetic, 128))

        val point = PolynomialWitnessSupports(arithmetic, 2048, 8, 3, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, mapOf(2 to BigFraction.ofLong(3)),
            { true }, { true },
        )

        assertNull(point)
    }

    @Test
    fun `zero factors need no irrelevant intermediate power growth`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const zero Real) (declare-const b Real) (declare-const fixed Real)
            (assert (and (= (* zero fixed fixed) 0) (= (* b b) 1)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val point = assertNotNull(PolynomialWitnessSupports(arithmetic, 2048, 8, 3, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, mapOf(2 to BigFraction.ofLong(3)),
            { true }, { true },
        ))

        val coordinates = point.mapValues { algebraic.rational(it.value) }
        for (atom in atoms) {
            assertTrue(atom.holds(algebraic.sign(algebraic.evaluate(atom.polynomial, coordinates))))
        }
    }

    @Test
    fun `source root isolation retains the caller dense coefficient limit`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (assert (and (= (* a a) 4) (= (* b b) 9)))",
        )
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .conjunctiveAtoms(PolynomialArithmetic(), 128))
        val arithmetic = PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 1))

        assertFailsWith<PolynomialResourceLimit> {
            PolynomialWitnessSupports(arithmetic, 2048, 8, 4096, 4).point(
                atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
            )
        }
    }

    @Test
    fun `interruption publishes no partial support join`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (assert (and (= (* a a) 4) (= (* b b) 9)))",
        )
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .conjunctiveAtoms(PolynomialArithmetic(), 128))
        var completed = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completed += it }
        }
        assertNotNull(PolynomialWitnessSupports(PolynomialArithmetic(counter), 2048, 8, 4096, 4).point(
            atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
        ))
        for (limit in listOf(1L, completed / 2, completed - 1)) {
            var spent = 0L
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }

            assertFailsWith<PolynomialInterrupted> {
                PolynomialWitnessSupports(PolynomialArithmetic(cancellation), 2048, 8, 4096, 4).point(
                    atoms, atoms.map { it.polynomial.terms }, source.realCount, emptyMap(), { true }, { true },
                )
            }
        }
    }

    @Test
    fun `containing work exhaustion withholds a completed source witness`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real)
            (assert (and (= (* a a) 4) (= (* b b) 9) (< (* a b) 0)))
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        val stopped = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }
}
