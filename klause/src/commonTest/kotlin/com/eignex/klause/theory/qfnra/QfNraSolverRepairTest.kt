package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfNraSolverRepairTest {
    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `successive scalar equality repairs should retain the positive completed point within caller work`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (= (* y y) 3) (> x 0) (> y 0)))
            """.trimIndent(),
        )
        val work = QfNraWork(QfNraParams(maximumWork = 8192))
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        val assignment = assertIs<QfNraAssignment>(
            work.probe {
                probe.probeWitness(source.realCount, emptyMap()) { null }
            },
        )

        assertEquals(BigFraction.ofLong(2), algebraic.power(assignment.realValue(0), 2).rational)
        assertEquals(BigFraction.ofLong(3), algebraic.power(assignment.realValue(1), 2).rational)
        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `coupled line completion should retain checked irrational coordinates and source fixings`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (declare-const fixed Real)
            (declare-const unused Real)
            (assert (and (= (* x x) 2) (= y x) (> x 0) (= fixed 3)))
            """.trimIndent(),
        )
        val fixed = mapOf(2 to BigFraction.ofLong(3))
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        val assignment = assertIs<QfNraAssignment>(
            work.probe {
                work.coupled {
                    probe.coupled(
                        guides,
                        List(source.realCount) { fixed[it] ?: BigFraction.ZERO },
                        fixed,
                        atomTermCounts = guides.map { it.polynomial.terms.size },
                    )
                }
            },
        )

        assertEquals(BigFraction.ofLong(2), algebraic.power(assignment.realValue(0), 2).rational)
        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(0, algebraic.compare(assignment.realValue(0), assignment.realValue(1)))
        assertEquals(BigFraction.ofLong(3), assignment.realValue(2).rational)
        assertEquals(BigFraction.ZERO, assignment.realValue(3).rational)
    }

    @Test
    fun `dyadic repairs should refresh shared weights before returning a checked hierarchy`() {
        for (extended in listOf(false, true)) {
            val declarations = if (extended) "(declare-const fixed Real) (declare-const unused Real)" else ""
            val condition = if (extended) "(= fixed 2)" else ""
            val source = SmtLibPolynomial.parse(
                """
                (declare-const y Real)
                (declare-const x Real)
                $declarations
                (assert (and (> x 1) (> y x) (> (* x y) 4) $condition))
                """.trimIndent(),
            )
            val fixed = if (extended) mapOf(2 to BigFraction.ofLong(2)) else emptyMap()
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

            val assignment = assertIs<QfNraAssignment>(
                work.probe {
                    work.coupled {
                        probe.coupled(
                            guides,
                            List(source.realCount) { fixed[it] ?: BigFraction.ZERO },
                            fixed,
                            atomTermCounts = guides.map { it.polynomial.terms.size },
                        )
                    }
                },
            )

            assertEquals(1L, work.snapshot().dyadicBases)
            assertTrue(work.snapshot().workSpent < 32768)
            assertEquals(1, algebraic.compare(assignment.realValue(1), algebraic.rational(BigFraction.ONE)))
            assertEquals(1, algebraic.compare(assignment.realValue(0), assignment.realValue(1)))
            assertEquals(
                1,
                algebraic.compare(
                    algebraic.multiply(assignment.realValue(0), assignment.realValue(1)),
                    algebraic.rational(BigFraction.ofLong(4)),
                ),
            )
            if (extended) {
                assertEquals(BigFraction.ofLong(2), assignment.realValue(2).rational)
                assertEquals(BigFraction.ZERO, assignment.realValue(3).rational)
            }
        }
    }

    @Test
    fun `successive witness proposals should keep exact powers isolated`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (* x x) 2))
            (assert (= y (+ (^ x 4) (^ x 2))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))
        val point = mutableMapOf(
            0 to algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last(),
            1 to algebraic.rational(BigFraction.ofLong(6)),
        )
        assertIs<QfNraAssignment>(probe.candidate(point))
        point[0] = algebraic.roots(RationalPolynomial.of(-3, 0, 1)).last()

        val result = probe.candidate(point)

        assertNull(result)
    }

    @Test
    fun `sparse signed weights should retain strict rational bounds and unused source coordinates`() {
        for (negated in listOf(false, true)) {
            val declarations = (0 until 32).joinToString("\n") { "(declare-const x$it Real)" }
            val comparisons = if (negated) {
                "(not (>= (* 3 x0) 1)) (not (<= x31 x0))"
            } else {
                "(< (* 3 x0) 1) (> x31 x0)"
            }
            val source = SmtLibPolynomial.parse(
                """
                $declarations
                (assert (and (> x0 0) (> x31 0) (> (* x0 x31) 0) $comparisons))
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(
                probe.coupled(
                    guides,
                    List(source.realCount) { BigFraction.ZERO },
                    emptyMap(),
                    atomTermCounts = guides.map { it.polynomial.terms.size },
                ),
            )

            assertEquals(1, algebraic.sign(assignment.realValue(0)))
            assertEquals(1, algebraic.compare(assignment.realValue(31), assignment.realValue(0)))
            assertEquals(
                -1,
                algebraic.compare(
                    algebraic.multiply(
                        assignment.realValue(0),
                        algebraic.rational(BigFraction.ofLong(3)),
                    ),
                    algebraic.rational(BigFraction.ONE),
                ),
            )
            for (id in 1 until 31) assertEquals(BigFraction.ZERO, assignment.realValue(id).rational)
        }
    }

    @Test
    fun `admission snapshots should retain complements and fresh fixed query values`() {
        for (negated in listOf(false, true)) {
            val comparison = if (negated) "(not (<= (* x y) fixed))" else "(> (* x y) fixed)"
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const fixed Real)
                (declare-const unused Real)
                (assert (and $comparison (not (<= x 0)) (not (<= y 0))))
                """.trimIndent(),
            )
            val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
            service.invocation().use { invocation ->
                for (value in listOf(BigFraction.ONE, BigFraction.ofLong(2))) {
                    val result = assertIs<QfNraResult.Satisfiable>(
                        invocation.check(
                            PolynomialCondition(
                                lower = mapOf(2 to value),
                                upper = mapOf(2 to value),
                            ),
                        ),
                    )
                    val point = result.assignment

                    assertEquals(value, point.realValue(2).rational)
                    assertEquals(BigFraction.ZERO, point.realValue(3).rational)
                    assertEquals(
                        1,
                        algebraic.compare(
                            algebraic.multiply(point.realValue(0), point.realValue(1)),
                            algebraic.rational(value),
                        ),
                    )
                    assertEquals(1L, result.stats.dyadicBases)
                }
                assertEquals(2L, invocation.queries)
                assertEquals(2L, invocation.stats.dyadicBases)
            }
        }
    }

    @Test
    fun `containing dyadic interruption should not count as a private work decline`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128))
        val counts = guides.map { it.polynomial.terms.size }
        lateinit var work: QfNraWork
        work = QfNraWork(QfNraParams(Cancellation { work.dyadicPreExtraction }))
        val arithmetic = PolynomialArithmetic(work)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        assertFailsWith<PolynomialInterrupted> {
            work.probe {
                work.coupled {
                    probe.coupled(
                        guides,
                        List(source.realCount) { BigFraction.ZERO },
                        emptyMap(),
                        atomTermCounts = counts,
                    )
                }
            }
        }

        val stats = work.snapshot()
        assertEquals(1L, stats.dyadicAttempts)
        assertEquals(0L, stats.dyadicBases)
        assertEquals(0L, stats.coupledWorkDeclines)
        assertEquals(0L, stats.dyadicPreExtractionDeclines)
    }

    @Test
    fun `source monomial scales should complete a multiplicative hierarchy without root queries`() {
        for (negated in listOf(false, true)) {
            val comparisons = if (negated) {
                "(not (<= x (* y z))) (not (<= y z)) (not (< z 1))"
            } else {
                "(> x (* y z)) (> y z) (>= z 1)"
            }
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (declare-const fixed Real)
                (declare-const unused Real)
                (assert (and (> x 0) (> y 0) (> z 0) $comparisons (= fixed 2)))
                (check-sat)
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val fixed = mapOf(3 to BigFraction.ofLong(2))
            val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val seed = List(source.realCount) { fixed[it] ?: BigFraction.ZERO }
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(
                probe.coupled(
                    guides,
                    seed,
                    fixed,
                    atomTermCounts = guides.map { it.polynomial.terms.size },
                ),
            )

            val x = assignment.realValue(0)
            val y = assignment.realValue(1)
            val z = assignment.realValue(2)
            assertEquals(1, algebraic.compare(x, algebraic.multiply(y, z)))
            assertEquals(1, algebraic.compare(y, z))
            assertTrue(algebraic.compare(z, algebraic.rational(BigFraction.ONE)) >= 0)
            assertEquals(BigFraction.ofLong(2), assignment.realValue(3).rational)
            assertEquals(BigFraction.ZERO, assignment.realValue(4).rational)
            repeat(32) {
                assertEquals(emptyList(), probe.roots(Polynomial.ONE, 0, emptyMap()))
            }
            assertNull(probe.roots(Polynomial.ONE, 0, emptyMap()))
        }
    }

    @Test
    fun `negative monomial scales should retain strict rational upper bounds`() {
        for (negated in listOf(false, true)) {
            val comparisons = if (negated) {
                "(not (>= (* 3 x) 1)) (not (<= y x))"
            } else {
                "(< (* 3 x) 1) (> y x)"
            }
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> x 0) (> y 0) (> (* x y) 0) $comparisons))
                (check-sat)
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(
                probe.coupled(
                    guides,
                    List(source.realCount) { BigFraction.ZERO },
                    emptyMap(),
                    atomTermCounts = guides.map { it.polynomial.terms.size },
                ),
            )

            val x = assignment.realValue(0)
            val y = assignment.realValue(1)
            assertEquals(1, algebraic.sign(x))
            assertEquals(1, algebraic.compare(y, x))
            assertEquals(
                -1,
                algebraic.compare(
                    algebraic.multiply(
                        x,
                        algebraic.rational(BigFraction.ofLong(3)),
                    ),
                    algebraic.rational(BigFraction.ONE),
                ),
            )
        }
    }

    @Test
    fun `incomplete monomial domination should retain the existing positive center`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (> (* x y) 0) (> x (/ 1 2)) (< x 2)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(
            probe.coupled(
                guides,
                List(source.realCount) { BigFraction.ZERO },
                emptyMap(),
                atomTermCounts = guides.map { it.polynomial.terms.size },
            ),
        )

        assertEquals(0, algebraic.compare(assignment.realValue(0), algebraic.rational(BigFraction.ONE)))
        assertEquals(0, algebraic.compare(assignment.realValue(1), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `unresolved equalities should retain affine completion instead of monomial domination`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 3) (> x y) (> y 0) (> (* x y) 0)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(
            probe.coupled(
                guides,
                List(source.realCount) { BigFraction.ZERO },
                emptyMap(),
                atomTermCounts = guides.map { it.polynomial.terms.size },
            ),
        )

        val x = assignment.realValue(0)
        val y = assignment.realValue(1)
        assertEquals(0, algebraic.compare(algebraic.add(x, y), algebraic.rational(BigFraction.ofLong(3))))
        assertEquals(1, algebraic.compare(x, y))
        assertEquals(1, algebraic.sign(y))
    }

    @Test
    fun `conflicting effective relations should reject every monomial proposal`() {
        for (negated in listOf(false, true)) {
            val upper = if (negated) "(not (> (* x y) 1))" else "(<= (* x y) 1)"
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> (* x y) 1) $upper))
                (check-sat)
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            assertNull(
                probe.coupled(
                    guides,
                    List(source.realCount) { BigFraction.ZERO },
                    emptyMap(),
                    atomTermCounts = guides.map { it.polynomial.terms.size },
                ),
            )
        }
    }

    @Test
    fun `shared boundaries should follow each changed point and coordinate`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (> (* x y) 2) (>= (* x y) 2)))
            (check-sat)
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            .map { it to it.polynomial.terms.size }
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
        val point = linkedMapOf(
            0 to algebraic.rational(BigFraction.ZERO),
            1 to algebraic.rational(BigFraction.ONE),
        )

        val first = requireNotNull(probe.boundaries(guides, 0, point)).single()
        point[1] = algebraic.rational(BigFraction.ofLong(2))
        val changedPoint = requireNotNull(probe.boundaries(guides, 0, point)).single()
        point[0] = algebraic.rational(BigFraction.ofLong(4))
        val changedCoordinate = requireNotNull(probe.boundaries(guides, 1, point)).single()

        assertEquals(0, algebraic.compare(first, algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(0, algebraic.compare(changedPoint, algebraic.rational(BigFraction.ONE)))
        assertEquals(
            0,
            algebraic.compare(
                changedCoordinate,
                algebraic.rational(BigFraction.of(bigIntOf(1), bigIntOf(2))),
            ),
        )
    }

    @Test
    fun `declined or interrupted conjunction extraction should not publish partial source indices`() {
        for (alternative in listOf(false, true)) {
            val tail = if (alternative) "(or (> y 0) (< y 0))" else "(> y 0)"
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> x 0) $tail))
                (check-sat)
                """.trimIndent(),
            )
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val indices = arrayListOf(77)

            assertNull(formula.conjunctiveAtoms(PolynomialArithmetic(), if (alternative) 128 else 1, indices))

            assertEquals(listOf(77), indices)
            var spent = 0L
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= 5
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                formula.conjunctiveAtoms(PolynomialArithmetic(cancellation), 128, indices)
            }
            assertEquals(listOf(77), indices)
        }
    }
}
