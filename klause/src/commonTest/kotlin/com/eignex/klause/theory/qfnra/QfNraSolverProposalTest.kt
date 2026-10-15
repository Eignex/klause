package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfNraSolverProposalTest {
    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `nonzero coupled sectors should preserve positive coordinates while breaking symmetry`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (declare-const unused Real)
            (assert (and (> x 0) (> y 0) (> z 0) (> x y) (> (* x y) 0)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(
            probe.coupled(
                requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                List(source.realCount) { BigFraction.ZERO },
                emptyMap(),
            ),
        )

        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
        assertEquals(1, algebraic.sign(assignment.realValue(2)))
        assertEquals(1, algebraic.compare(assignment.realValue(0), assignment.realValue(1)))
        assertEquals(BigFraction.ZERO, assignment.realValue(3).rational)
    }

    @Test
    fun `coupled affine completion should retain nonzero fixed constants and coefficients`() {
        for ((fixedValue, expression, total) in listOf(
            Triple(1L, "(+ x z y)", 4L),
            Triple(2L, "(+ (* y x) z)", 5L),
        )) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (assert (and (= $expression $total) (> x z) (> z 0) (> (* x z) 0)))
                (check-sat)
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val fixed = mapOf(1 to BigFraction.ofLong(fixedValue))
            val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(
                probe.coupled(
                    requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                    List(source.realCount) { fixed[it] ?: BigFraction.ZERO },
                    fixed,
                ),
            )

            val x = assignment.realValue(0)
            val y = assignment.realValue(1)
            val z = assignment.realValue(2)
            val value = if (fixedValue == 1L) {
                algebraic.add(algebraic.add(x, z), y)
            } else {
                algebraic.add(algebraic.multiply(y, x), z)
            }
            assertEquals(0, algebraic.compare(value, algebraic.rational(BigFraction.ofLong(total))))
            assertEquals(BigFraction.ofLong(fixedValue), y.rational)
            assertEquals(1, algebraic.compare(x, z))
            assertEquals(1, algebraic.sign(z))
        }
    }

    @Test
    fun `degree ineligible guides should remain mandatory in coupled point checks`() {
        for (relation in listOf(">=", "<")) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (assert (and ($relation (^ x 9) 0) (> (* x y) 0) (> x y) (> x 0) (> y 0) (> z 0)))
                (check-sat)
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = probe.coupled(
                requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                List(source.realCount) { BigFraction.ZERO },
                emptyMap(),
            )

            if (relation == "<") {
                assertNull(assignment)
            } else {
                val accepted = assertIs<QfNraAssignment>(assignment)
                assertEquals(1, algebraic.sign(accepted.realValue(0)))
                assertEquals(1, algebraic.sign(accepted.realValue(1)))
                assertEquals(1, algebraic.sign(accepted.realValue(2)))
                assertEquals(1, algebraic.compare(accepted.realValue(0), accepted.realValue(1)))
            }
        }
    }

    @Test
    fun `coupled sector proposals should move both coordinates from a zero product`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(
            probe.coupled(
                requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                listOf(BigFraction.ZERO, BigFraction.ZERO),
                emptyMap(),
            ),
        )

        val x = assignment.realValue(0)
        val y = assignment.realValue(1)
        assertEquals(1, algebraic.sign(x))
        assertEquals(1, algebraic.sign(y))
        assertTrue(algebraic.compare(algebraic.multiply(x, y), algebraic.rational(BigFraction.ONE)) >= 0)
    }

    @Test
    fun `affine nullspace proposals should preserve a relation while entering its positive interior`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 1) (> x 0) (> y 0) (> (* x y) 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
            ),
        )

        val x = result.assignment.realValue(0)
        val y = result.assignment.realValue(1)
        assertEquals(1, algebraic.sign(x))
        assertEquals(1, algebraic.sign(y))
        assertEquals(BigFraction.ONE, algebraic.add(x, y).rational)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `coupled proposals should not release a fixed zero coordinate`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and (>= (* x y) 1) (> y 0) (> z 0)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val fixed = mapOf(0 to BigFraction.ZERO)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            List(3) { BigFraction.ZERO },
            fixed,
        )

        assertNull(assignment)
    }

    @Test
    fun `guarded affine equalities should not restrict another satisfying alternative`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (or (= (+ x y) 0) (= (+ x y) 2)) (> (* x y) 0) (> x 0) (> y 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
            ),
        )

        assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(1)))
        assertEquals(
            BigFraction.ofLong(2),
            algebraic.add(
                result.assignment.realValue(0),
                result.assignment.realValue(1),
            ).rational,
        )
    }

    @Test
    fun `containing work limit should withhold a coupled witness without replenishing its allowance`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 1) (> x 0) (> y 0) (> (* x y) 0)))
            (check-sat)
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
            ),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(
            source.formula,
            source.boolCount,
            source.realCount,
            QfNraParams(cancellation, maximumWork = limit),
        )

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(limit, spent)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `an observed containing stop inside a coupled phase should not publish a fallback witness`() {
        var spent = 0L
        var stopped = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (spent < 4 || stopped) return false
                stopped = true
                return true
            }
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val work = QfNraWork(QfNraParams(cancellation))
        val arithmetic = PolynomialArithmetic(work)
        var fallbackReached = false

        assertFailsWith<PolynomialInterrupted> {
            work.probe {
                work.coupled {
                    repeat(8) { arithmetic.step() }
                    null
                }
                fallbackReached = true
                null
            }
        }

        assertEquals(false, fallbackReached)
        assertEquals(4L, spent)
        assertEquals(spent, work.snapshot().workSpent)
    }

    @Test
    fun `oversized affine intermediates should decline without disabling complete point checks`() {
        val coefficient = "1" + "0".repeat(1000)
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ (* $coefficient x) y) 1) (= (+ x (* $coefficient y)) 1) (> (* x y) 0)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)

        val coupled = probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            listOf(BigFraction.ZERO, BigFraction.ZERO),
            emptyMap(),
        )
        val value = (BigFraction.of(parseBigInt(coefficient), bigIntOf(1)) + BigFraction.ONE).reciprocal()
        val fallback = probe.candidate(mapOf(0 to algebraic.rational(value), 1 to algebraic.rational(value)))

        assertNull(coupled)
        assertIs<QfNraAssignment>(fallback)
    }

    @Test
    fun `disequality guards should admit a narrow rational witness without matrix storage`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (> x 2) (< x 3) (not (= (* x x) 0))))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
            ),
        )

        val x = result.assignment.realValue(0)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), x))
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-3, 1), x))
        assertTrue(x.rational != null)
    }

    @Test
    fun `nonconjunctive strict product sectors should yield a witness before projection`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (or (and (> (* x y) 4) (> x 0) (> y 0))
                        (and (< (* x y) (- 4)) (> x 0) (< y 0))))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
            ),
        )

        assertEquals(0L, result.stats.projectedPolynomials)
        val x = result.assignment.realValue(0)
        val y = result.assignment.realValue(1)
        val product = algebraic.multiply(x, y)
        assertEquals(1, algebraic.sign(x))
        assertTrue(
            (
                algebraic.sign(y) > 0 &&
                    algebraic.compare(product, algebraic.rational(BigFraction.ofLong(4))) > 0
                ) ||
                (
                    algebraic.sign(y) < 0 &&
                        algebraic.compare(product, algebraic.rational(BigFraction.ofLong(-4))) < 0
                    ),
        )
    }

    @Test
    fun `nonconjunctive sectors should retain nested Boolean pins and original coordinates`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const y Real)
            (declare-const k Real)
            (declare-const unused Real)
            (assert (or (and b (> (* x y) k) (> x 0) (> y 0))
                        (and (not b) (< (* x y) (- k)) (> x 0) (< y 0))))
            (check-sat)
            """.trimIndent(),
        )
        for (positive in listOf(false, true)) {
            val condition = PolynomialCondition(
                bools = mapOf(0 to positive),
                lower = mapOf(2 to BigFraction.ofLong(4)),
                upper = mapOf(2 to BigFraction.ofLong(4)),
            )

            val result = assertIs<QfNraResult.Satisfiable>(
                solveQfNra(
                    source.formula,
                    source.boolCount,
                    source.realCount,
                    condition = condition,
                ),
            )

            assertEquals(0L, result.stats.projectedPolynomials)
            assertEquals(positive, result.assignment.boolValue(0))
            assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
            assertEquals(if (positive) 1 else -1, algebraic.sign(result.assignment.realValue(1)))
            val product = algebraic.multiply(result.assignment.realValue(0), result.assignment.realValue(1))
            assertEquals(
                if (positive) 1 else -1,
                algebraic.compare(product, algebraic.rational(BigFraction.ofLong(if (positive) 4 else -4))),
            )
            assertEquals(BigFraction.ofLong(4), result.assignment.realValue(2).rational)
            assertEquals(BigFraction.ZERO, result.assignment.realValue(3).rational)
        }
    }

    @Test
    fun `favorable boundary guides should not accept contradictory sign branches`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (or (and (> (* x y) 4) (> x 0) (< y 0))
                        (and (< (* x y) (- 4)) (> x 0) (> y 0))))
            (check-sat)
            """.trimIndent(),
        )
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        val assignment = work.probe { probe.probeWitness(source.realCount, emptyMap(), work::coupled) }

        assertNull(assignment)
        assertTrue(work.snapshot().workSpent > 0L)
        assertEquals(0L, work.snapshot().projectedPolynomials)
    }

    @Test
    fun `a containing stop should not clear into a Boolean sector witness`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (or (and (> (* x y) 4) (> x 0) (> y 0))
                        (and (< (* x y) (- 4)) (> x 0) (< y 0))))
            (check-sat)
            """.trimIndent(),
        )
        for (transient in listOf(false, true)) {
            var trigger = false
            var observed = false
            var spent = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean {
                    if (!trigger || (transient && observed)) return false
                    observed = true
                    return true
                }
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            val query = QfNraQuery(
                source.formula,
                source.boolCount,
                source.realCount,
                QfNraParams(stop),
                PolynomialCondition(),
            )
            assertNull(query.next())
            trigger = true

            val result = assertIs<QfNraResult.Unknown>(query.next())

            assertTrue(observed)
            assertEquals(QfNraReason.Interrupted, result.reason)
            assertEquals(spent, result.stats.workSpent)
            assertEquals(0L, result.stats.projectedPolynomials)
        }
    }

    @Test
    fun `negated inequality guides should preserve their source direction`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (> (* x x) 4) (< x 3) (> x 0) (not (> x (/ 11 4)))))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
            ),
        )

        val x = result.assignment.realValue(0)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), x))
        assertTrue(algebraic.compare(x, algebraic.rational(BigFraction.of(bigIntOf(11), bigIntOf(4)))) <= 0)
    }

    @Test
    fun `inequality proposals should retain fixed and unused original coordinates`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (declare-const unused Real)
            (assert (and (> (* x y) 2) (< (* x y) 3) (not (= (* x y) 0))))
            (check-sat)
            """.trimIndent(),
        )
        val condition = PolynomialCondition(
            lower = mapOf(1 to BigFraction.ONE),
            upper = mapOf(1 to BigFraction.ONE),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
                condition = condition,
            ),
        )

        assertEquals(3, result.assignment.realCount)
        assertEquals(BigFraction.ONE, result.assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), result.assignment.realValue(0)))
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-3, 1), result.assignment.realValue(0)))
    }

    @Test
    fun `contradictory disequality and boundary should return unsatisfiable`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (not (= x 2)) (<= (* (- x 2) (- x 2)) 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `rational repairs should capture every source coordinate before projection`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const unused Real)
            (assert (= x 2))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(1, result.assignment.boolCount)
        assertEquals(2, result.assignment.realCount)
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(0).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(1).rational)
    }

    @Test
    fun `later equality repairs should retain an exact algebraic trajectory`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real)
            (declare-const x Real)
            (assert (and (= (* x x) (* 2 a)) (= (* 3 a) 1) (> a 0) (> x 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(BigFraction.of(bigIntOf(1), bigIntOf(3)), result.assignment.realValue(0).rational)
        assertEquals(
            0,
            algebraic.signAt(
                RationalPolynomial(
                    listOf(
                        BigFraction.of(bigIntOf(-2), bigIntOf(3)),
                        BigFraction.ZERO,
                        BigFraction.ONE,
                    ),
                ),
                result.assignment.realValue(1),
            ),
        )
        assertEquals(1, algebraic.sign(result.assignment.realValue(1)))
    }

    @Test
    fun `equality proposals should not replace strict or disequality checks`() {
        for (constraint in listOf("(> x 1)", "(distinct (* x x) 1)")) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (assert (and (= (* x x) 1) $constraint))
                (check-sat)
                """.trimIndent(),
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            assertTrue(assertIs<QfNraResult.Unsatisfiable>(result).stats.projectedPolynomials > 0)
        }
    }

    @Test
    fun `negated equality proposals should retain satisfying sectors`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (not (= (* x x) 1)) (> x 1)))
            (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), result.assignment.realValue(0)))
    }

    @Test
    fun `conditioned repairs should preserve Boolean pins and unused coordinates`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const k Real)
            (declare-const unused Real)
            (assert (and (or b (= (* x x) k)) (> x 0)))
            (check-sat)
            """.trimIndent(),
        )
        val condition = PolynomialCondition(
            bools = mapOf(0 to false),
            lower = mapOf(1 to BigFraction.ofLong(2)),
            upper = mapOf(1 to BigFraction.ofLong(2)),
        )

        val result = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(
                source.formula,
                source.boolCount,
                source.realCount,
                condition = condition,
            ),
        )

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(false, result.assignment.boolValue(0))
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), result.assignment.realValue(0)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
    }

    @Test
    fun `an observed parent stop should not become private probe exhaustion`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 2
        var spent = 0L
        var observed = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (observed || spent < limit) return false
                observed = true
                return true
            }
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        assertTrue(observed)
        assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `skipped high degree repairs should retain complete refutation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (= (^ x 9) 0) (> x 1)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertTrue(assertIs<QfNraResult.Unsatisfiable>(result).stats.projectedPolynomials > 0)
    }

    @Test
    fun `checked rational points should not require matrix storage`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (* y y) x))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(
            source.formula,
            source.boolCount,
            source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
        )

        assertEquals(0L, assertIs<QfNraResult.Satisfiable>(result).stats.projectedPolynomials)
    }
}
