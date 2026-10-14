package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
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

class QfNraSolverTest {
    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `coupled sector proposals move both coordinates from a zero product`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap(),
        ))

        val x = assignment.realValue(0)
        val y = assignment.realValue(1)
        assertEquals(1, algebraic.sign(x))
        assertEquals(1, algebraic.sign(y))
        assertTrue(algebraic.compare(algebraic.multiply(x, y), algebraic.rational(BigFraction.ONE)) >= 0)
    }

    @Test
    fun `affine nullspace proposals preserve a relation while entering its positive interior`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 1) (> x 0) (> y 0) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        val x = result.assignment.realValue(0)
        val y = result.assignment.realValue(1)
        assertEquals(1, algebraic.sign(x))
        assertEquals(1, algebraic.sign(y))
        assertEquals(BigFraction.ONE, algebraic.add(x, y).rational)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `coupled proposals cannot release a fixed zero coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and (>= (* x y) 1) (> y 0) (> z 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val fixed = mapOf(0 to BigFraction.ZERO)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            List(3) { BigFraction.ZERO }, fixed)

        assertNull(assignment)
    }

    @Test
    fun `guarded affine equalities do not restrict another satisfying alternative`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (or (= (+ x y) 0) (= (+ x y) 2)) (> (* x y) 0) (> x 0) (> y 0)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(1)))
        assertEquals(BigFraction.ofLong(2), algebraic.add(
            result.assignment.realValue(0), result.assignment.realValue(1),
        ).rational)
    }

    @Test
    fun `containing work limit withholds a coupled witness without replenishing its allowance`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 1) (> x 0) (> y 0) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(cancellation, maximumWork = limit))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(limit, spent)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `coupled repairs share query and proposal exhaustion with coordinate repairs`() {
        for (exhaustProposals in listOf(false, true)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val algebraic = AlgebraicArithmetic(arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
            val zero = mapOf(0 to algebraic.rational(BigFraction.ZERO), 1 to algebraic.rational(BigFraction.ZERO))
            if (exhaustProposals) repeat(64) { assertNull(probe.candidate(zero)) }
            else repeat(32) { probe.roots(formula.atoms.first().polynomial, 0, zero) }

            val assignment = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap())

            assertNull(assignment)
        }
    }

    @Test
    fun `coupled phase exhaustion leaves charged scalar fallback work available`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
            (check-sat)
        """.trimIndent())
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val work = QfNraWork(QfNraParams(cancellation))
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
        val before = spent

        val assignment = work.probe {
            assertNull(work.coupled {
                repeat(65_536) { arithmetic.step() }
                null
            })
            assertEquals(before + 32_768, spent)
            probe.candidate(mapOf(0 to algebraic.rational(BigFraction.ONE), 1 to algebraic.rational(BigFraction.ONE)))
        }

        assertIs<QfNraAssignment>(assignment)
        assertTrue(spent > before + 32_768)
        assertTrue(spent < before + 65_536)
        assertEquals(spent, work.snapshot().workSpent)
    }

    @Test
    fun `an observed containing stop inside a coupled phase cannot publish a fallback witness`() {
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
    fun `oversized affine intermediates decline without disabling complete point checks`() {
        val coefficient = "1" + "0".repeat(1000)
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ (* $coefficient x) y) 1) (= (+ x (* $coefficient y)) 1) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)

        val coupled = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap())
        val value = (BigFraction.of(parseBigInt(coefficient), bigIntOf(1)) + BigFraction.ONE).reciprocal()
        val fallback = probe.candidate(mapOf(0 to algebraic.rational(value), 1 to algebraic.rational(value)))

        assertNull(coupled)
        assertIs<QfNraAssignment>(fallback)
    }

    @Test
    fun `disequality guards admit a narrow rational witness without matrix storage`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> x 2) (< x 3) (not (= (* x x) 0))))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
        ))

        val x = result.assignment.realValue(0)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), x))
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-3, 1), x))
        assertTrue(x.rational != null)
    }

    @Test
    fun `negated inequality guides preserve their source direction`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> (* x x) 4) (< x 3) (> x 0) (not (> x (/ 11 4)))))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        val x = result.assignment.realValue(0)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), x))
        assertTrue(algebraic.compare(x, algebraic.rational(BigFraction.of(bigIntOf(11), bigIntOf(4)))) <= 0)
    }

    @Test
    fun `inequality proposals retain fixed and unused original coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const unused Real)
            (assert (and (> (* x y) 2) (< (* x y) 3) (not (= (* x y) 0))))
            (check-sat)
        """.trimIndent())
        val condition = PolynomialCondition(lower = mapOf(1 to BigFraction.ONE),
            upper = mapOf(1 to BigFraction.ONE))

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount, condition = condition,
        ))

        assertEquals(3, result.assignment.realCount)
        assertEquals(BigFraction.ONE, result.assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), result.assignment.realValue(0)))
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-3, 1), result.assignment.realValue(0)))
    }

    @Test
    fun `contradictory disequality and boundary return unsatisfiable`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (not (= x 2)) (<= (* (- x 2) (- x 2)) 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `containing cancellation withholds an inequality proposal witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> x 2) (< x 3) (not (= (* x x) 0))))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `rational repairs capture every source coordinate before projection`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const unused Real)
            (assert (= x 2))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(1, result.assignment.boolCount)
        assertEquals(2, result.assignment.realCount)
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(0).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(1).rational)
    }

    @Test
    fun `later equality repairs retain an exact algebraic trajectory`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const a Real)
            (declare-const x Real)
            (assert (and (= (* x x) (* 2 a)) (= (* 3 a) 1) (> a 0) (> x 0)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(BigFraction.of(bigIntOf(1), bigIntOf(3)), result.assignment.realValue(0).rational)
        assertEquals(0, algebraic.signAt(RationalPolynomial(listOf(
            BigFraction.of(bigIntOf(-2), bigIntOf(3)), BigFraction.ZERO, BigFraction.ONE,
        )), result.assignment.realValue(1)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(1)))
    }

    @Test
    fun `equality proposals cannot replace strict or disequality checks`() {
        for (constraint in listOf("(> x 1)", "(distinct (* x x) 1)")) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (assert (and (= (* x x) 1) $constraint))
                (check-sat)
            """.trimIndent())

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            assertTrue(assertIs<QfNraResult.Unsatisfiable>(result).stats.projectedPolynomials > 0)
        }
    }

    @Test
    fun `negated equality proposals retain satisfying sectors`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (not (= (* x x) 1)) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), result.assignment.realValue(0)))
    }

    @Test
    fun `conditioned repairs preserve Boolean pins and unused coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const k Real)
            (declare-const unused Real)
            (assert (and (or b (= (* x x) k)) (> x 0)))
            (check-sat)
        """.trimIndent())
        val condition = PolynomialCondition(bools = mapOf(0 to false),
            lower = mapOf(1 to BigFraction.ofLong(2)), upper = mapOf(1 to BigFraction.ofLong(2)))

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount, condition = condition,
        ))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(false, result.assignment.boolValue(0))
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), result.assignment.realValue(0)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
    }

    @Test
    fun `parent cancellation withholds a completed probe witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertEquals(0L, unknown.stats.projectedPolynomials)
        assertEquals(spent, unknown.stats.workSpent)
    }

    @Test
    fun `an observed parent stop cannot become private probe exhaustion`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
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
    fun `probe work remains cumulative between invocation queries`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val required = solveQfNra(source.formula, source.boolCount, source.realCount).stats.workSpent
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        service.invocation(QfNraParams(cancellation, maximumWork = required + 1)).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val second = invocation.check()

            assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(second).reason)
            assertEquals(spent, invocation.stats.workSpent)
        }
    }

    @Test
    fun `skipped high degree repairs retain complete refutation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (^ x 9) 0) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertTrue(assertIs<QfNraResult.Unsatisfiable>(result).stats.projectedPolynomials > 0)
    }

    @Test
    fun `checked rational points do not require matrix storage`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (* y y) x))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(0L, assertIs<QfNraResult.Satisfiable>(result).stats.projectedPolynomials)
    }

    @Test
    fun `finds an exact irrational satisfying section`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
        assertEquals(1, algebraic.sign(value))
    }

    @Test
    fun `lifts an exact witness above an irrational coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (> x 0) (= (* y y) x) (> y 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(algebraic.power(assignment.realValue(1), 2), assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `refutes a genuinely coupled multivariate system`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x y) 1) (= (+ x y) 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `solves a coupled irrational intersection`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ (* x x) (* y y)) 1) (= (+ x y) 0) (> x 0) (< y 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-1, 0, 2), assignment.realValue(0)))
        assertEquals(0, algebraic.sign(algebraic.add(assignment.realValue(0), assignment.realValue(1))))
    }

    @Test
    fun `selected irrational coordinates cannot satisfy false disequality`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (> x 0) (= (* y y) 2) (> y 0) (distinct x y)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `zero specialization retains its exact constraint truth`() {
        for ((target, satisfiable) in listOf(0 to true, 1 to false)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (= x 0) (= (* x y) $target)))
                (check-sat)
            """.trimIndent())

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (satisfiable) assertIs<QfNraResult.Satisfiable>(result)
            else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `nullification on a positive dimensional base retains feasible sectors`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and (= x 0) (= (* x y z) 0) (> y 1) (> z 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), assignment.realValue(1)))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), assignment.realValue(2)))
    }

    @Test
    fun `lifting handles a vanished leading coefficient`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= x 0) (= (+ (* x y y) y (- 1)) 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ONE, assignment.realValue(1).rational)
    }

    @Test
    fun `retains a lower dimensional singular witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (+ (* x x) (* y y)) 0))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(assignment.realValue(0)))
        assertEquals(0, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `strict inequalities exclude a shared boundary`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (<= (* x x) 1) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `strict feasible sectors have exact interior samples`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (< (* x x) 2) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), value))
    }

    @Test
    fun `repeated roots remain satisfying sections`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (= (* (- x 1) (- x 1)) 0))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertEquals(BigFraction.ONE, assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `Boolean alternatives retain an irrational satisfying disjunct`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (or (= (+ (* x x) 1) 0) (= (* x x) 2)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
    }

    @Test
    fun `source Boolean references preserve correlation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and (or b (= (* x x) 2)) (not b) (not (= (* x x) 2))))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `all six polynomial relations retain equality boundary semantics`() {
        for ((relation, satisfiable) in listOf("=" to true, "distinct" to false, "<" to false,
            "<=" to true, ">" to false, ">=" to true)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (assert (and (= x 0) ($relation (* x x) 0)))
                (check-sat)
            """.trimIndent())

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (satisfiable) assertIs<QfNraResult.Satisfiable>(result)
            else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `unused source declarations receive complete coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const y Real)
            (assert (>= (* x x) 0))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(1, assignment.boolCount)
        assertEquals(2, assignment.realCount)
        assertEquals(BigFraction.ZERO, assignment.realValue(1).rational)
    }

    @Test
    fun `numeric conditionals retain every auxiliary coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and (not b) (= (ite b (* x x) 2) 2)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(source.realCount > source.sourceRealCount)
        assertEquals(source.realCount, assignment.realCount)
        assertEquals(false, assignment.boolValue(0))
    }

    @Test
    fun `constant contradictions need no real dimensions`() {
        val source = SmtLibPolynomial.parse("(assert (= (* 2 2) 5)) (check-sat)")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `Boolean only formulas receive a complete satisfying assignment`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const c Bool)
            (assert (xor b c))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, assignment.realCount)
        assertEquals(2, assignment.boolCount)
        assertTrue(assignment.boolValue(0) != assignment.boolValue(1))
    }

    @Test
    fun `wide rational coefficients retain their exact witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (= x (/ 18446744073709551617 3)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(BigFraction.of(parseBigInt("18446744073709551617"), bigIntOf(3)), value.rational)
    }

    @Test
    fun `pre cancelled calls publish unknown without work`() {
        val source = SmtLibPolynomial.parse("(assert false) (check-sat)")

        val result = solveQfNra(source.formula, 0, 0, QfNraParams(cancellation = Cancellation { true }))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertEquals(0L, unknown.stats.workSpent)
    }

    @Test
    fun `a zero work allowance cannot refute a contradiction`() {
        val source = SmtLibPolynomial.parse("(assert false) (check-sat)")

        val result = solveQfNra(source.formula, 0, 0, QfNraParams(maximumWork = 0))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `exhaustion before refutation publication returns unknown`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (< (* x x) 0)) (check-sat)")
        val completed = solveQfNra(source.formula, source.boolCount, source.realCount)
        val limit = assertIs<QfNraResult.Unsatisfiable>(completed).stats.workSpent - 1

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(maximumWork = limit))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `caller interruption after lifting publishes no witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> x 1) (< x 2)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertTrue(unknown.stats.liftedCells > 0)
        assertEquals(spent, unknown.stats.workSpent)
    }

    @Test
    fun `matrix allowance stops never supply a refutation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x y) 1) (= (+ x y) 0)))
            (check-sat)
        """.trimIndent())
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.ResourceLimit, unknown.reason)
        assertEquals(PolynomialResource.MATRIX_CELLS, unknown.resource)
    }

    @Test
    fun `returned witnesses survive buffer mutation and another invocation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and b (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val result = solveQfNra(source.formula, source.boolCount, source.realCount)
        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment

        assignment.bools[0] = false
        runCatching { (assignment.reals as MutableList<*>).clear() }
        solveQfNra(source.formula, source.boolCount, source.realCount)

        assertTrue(assignment.boolValue(0))
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), assignment.realValue(0)))
    }
}
