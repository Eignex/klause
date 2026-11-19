package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QfNraSolverTest {
    @Test
    fun `probe settlement should preserve the primary arithmetic failure`() {
        val primary = IllegalArgumentException("arithmetic")
        val cleanup = IllegalStateException("accounting")
        val work = QfNraWork(QfNraParams(ExecutionControl(accounting = WorkMeter { throw cleanup })))
        val arithmetic = PolynomialArithmetic(work)
        work.execution = arithmetic.execution

        val observed = assertFailsWith<IllegalArgumentException> {
            arithmetic.execution.operation {
                work.probe {
                    arithmetic.step()
                    throw primary
                }
            }
        }

        assertSame(primary, observed)
        assertSame(cleanup, observed.suppressedExceptions.single())
    }

    @Test
    fun `intermediate root rewrites retain active search`() {
        for (length in listOf(1, 2, 4)) {
            val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
            val graphs = (0 until length).joinToString(" ") {
                "(= x$it (+ (* x${it + 1} x${it + 1}) 1))"
            }
            val source = SmtLibPolynomial.parse("$declarations (assert (and $graphs (= x$length 2)))")
            val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000), PolynomialCondition())

            var result: QfNraResult? = null
            while (result == null) {
                result = query.next()
                if (query.progress.rootFinishedWork == null) assertEquals(0L, query.progress.searchRestarts)
            }

            val point = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(BigFraction.ofLong(2), point.realValue(length).rational)
            assertTrue(query.progress.searchRestarts <= 1L)
        }
    }

    @Test
    fun `a checked witness can complete before optional root reduction finishes`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (= x (+ (* y y) 1)) (= y 2)))",
        )
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000), PolynomialCondition())

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ofLong(5), point.realValue(0).rational)
        assertEquals(BigFraction.ofLong(2), point.realValue(1).rational)
        assertNull(query.progress.rootFinishedWork)
        assertTrue(query.progress.searchWork > 0 || result.stats.envelopeQueries > 0)
    }

    @Test
    fun `restarting snapshot search does not renew its optional probe allowance`() {
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        work.probe {
            repeat(100_000) { arithmetic.step() }
            null
        }
        val spent = work.spentWork
        var entered = false

        work.probe {
            entered = true
            null
        }

        assertFalse(entered)
        assertEquals(spent, work.spentWork)
    }

    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `affine bound consequences retain a full damped fifth order transition counterexample`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val energy = "(+ (* x2 x2) (* (/ 1 25) x2 x3) (* (/ 1 10) x3 x3) " +
            "(* (- (/ 3 20)) x2) (* (- (/ 1 50)) x3))"
        val invariant = "(and (<= (- 1) x2) (<= x2 (/ 6 5)) (<= (- (/ 16 5)) x3) " +
            "(<= x3 (/ 33 10)) (<= $energy (/ 111 100)))"
        val postcondition = invariant.replace("x2", "x0").replace("x3", "x1")
        val fifthOrder = "(+ x2 (- (/ (* x2 x2 x2) 6)) (/ (* x2 x2 x2 x2 x2) 120))"
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and $invariant (= x0 (+ x2 (/ x3 100))) " +
                "(= x1 (+ x3 (/ (- (* (- (/ 1 2)) x3) (* (/ 981 100) $fifthOrder)) 100))) " +
                "(not $postcondition)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 5_000_000))

        val point = assertIs<QfNraResult.Satisfiable>(result, result.toString()).assignment
        assertEquals(33, point.realCount)
        val hundredth = algebraic.rational(BigFraction.of(bigIntOf(1), bigIntOf(100)))
        val u = point.realValue(2)
        val v = point.realValue(3)
        val step = algebraic.add(u, algebraic.multiply(hundredth, v))
        assertEquals(0, algebraic.compare(point.realValue(0), step))
        val sine = algebraic.evaluate(RationalPolynomial(listOf(BigFraction.ZERO, BigFraction.ONE,
            BigFraction.ZERO, BigFraction.of(bigIntOf(-1), bigIntOf(6)), BigFraction.ZERO,
            BigFraction.of(bigIntOf(1), bigIntOf(120)))), u)
        val acceleration = algebraic.subtract(
            algebraic.multiply(algebraic.rational(BigFraction.of(bigIntOf(-1), bigIntOf(2))), v),
            algebraic.multiply(algebraic.rational(BigFraction.of(bigIntOf(981), bigIntOf(100))), sine))
        assertEquals(0, algebraic.compare(point.realValue(1),
            algebraic.add(v, algebraic.multiply(hundredth, acceleration))))
        val inside = ArrayList<Boolean>()
        for ((a, b) in listOf(u to v, point.realValue(0) to point.realValue(1))) {
            val value = listOf(algebraic.power(a, 2),
                algebraic.multiply(algebraic.rational(BigFraction.of(bigIntOf(1), bigIntOf(25))),
                    algebraic.multiply(a, b)),
                algebraic.multiply(algebraic.rational(BigFraction.of(bigIntOf(1), bigIntOf(10))),
                    algebraic.power(b, 2)),
                algebraic.multiply(algebraic.rational(BigFraction.of(bigIntOf(-3), bigIntOf(20))), a),
                algebraic.multiply(algebraic.rational(BigFraction.of(bigIntOf(-1), bigIntOf(50))), b))
                .fold(algebraic.rational(BigFraction.ZERO), algebraic::add)
            inside.add(algebraic.compare(a, algebraic.rational(BigFraction.MINUS_ONE)) >= 0 &&
                algebraic.compare(a, algebraic.rational(BigFraction.of(bigIntOf(6), bigIntOf(5)))) <= 0 &&
                algebraic.compare(b, algebraic.rational(BigFraction.of(bigIntOf(-16), bigIntOf(5)))) >= 0 &&
                algebraic.compare(b, algebraic.rational(BigFraction.of(bigIntOf(33), bigIntOf(10)))) <= 0 &&
                algebraic.compare(value, algebraic.rational(BigFraction.of(bigIntOf(111), bigIntOf(100)))) <= 0)
        }
        assertEquals(listOf(true, false), inside)
    }

    @Test
    fun `solver should find an exact irrational satisfying section`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
        assertEquals(1, algebraic.sign(value))
    }

    @Test
    fun `solver should lift an exact witness above an irrational coordinate`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (> x 0) (= (* y y) x) (> y 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(algebraic.power(assignment.realValue(1), 2), assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `solver should refute a genuinely coupled multivariate system`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x y) 1) (= (+ x y) 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `solver should solve a coupled irrational intersection`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ (* x x) (* y y)) 1) (= (+ x y) 0) (> x 0) (< y 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-1, 0, 2), assignment.realValue(0)))
        assertEquals(0, algebraic.sign(algebraic.add(assignment.realValue(0), assignment.realValue(1))))
    }

    @Test
    fun `selected irrational coordinates should not satisfy false disequality`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (> x 0) (= (* y y) 2) (> y 0) (distinct x y)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `zero specialization should retain its exact constraint truth`() {
        for ((target, satisfiable) in listOf(0 to true, 1 to false)) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (= x 0) (= (* x y) $target)))
                (check-sat)
                """.trimIndent(),
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (satisfiable) {
                assertIs<QfNraResult.Satisfiable>(result)
            } else {
                assertIs<QfNraResult.Unsatisfiable>(result)
            }
        }
    }

    @Test
    fun `nullification on a positive dimensional base should retain feasible sectors`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and (= x 0) (= (* x y z) 0) (> y 1) (> z 1)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), assignment.realValue(1)))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), assignment.realValue(2)))
    }

    @Test
    fun `lifting should handle a vanished leading coefficient`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= x 0) (= (+ (* x y y) y (- 1)) 0)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ONE, assignment.realValue(1).rational)
    }

    @Test
    fun `solver should retain a lower dimensional singular witness`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (+ (* x x) (* y y)) 0))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(assignment.realValue(0)))
        assertEquals(0, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `strict inequalities should exclude a shared boundary`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (<= (* x x) 1) (> x 1)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `strict feasible sectors should have exact interior samples`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (< (* x x) 2) (> x 1)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), value))
    }

    @Test
    fun `repeated roots should remain satisfying sections`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (= (* (- x 1) (- x 1)) 0))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertEquals(BigFraction.ONE, assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `Boolean alternatives should retain an irrational satisfying disjunct`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (or (= (+ (* x x) 1) 0) (= (* x x) 2)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
    }

    @Test
    fun `source Boolean references should preserve correlation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and (or b (= (* x x) 2)) (not b) (not (= (* x x) 2))))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `all six polynomial relations should retain equality boundary semantics`() {
        for ((relation, satisfiable) in listOf(
            "=" to true,
            "distinct" to false,
            "<" to false,
            "<=" to true,
            ">" to false,
            ">=" to true,
        )) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real)
                (assert (and (= x 0) ($relation (* x x) 0)))
                (check-sat)
                """.trimIndent(),
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (satisfiable) {
                assertIs<QfNraResult.Satisfiable>(result)
            } else {
                assertIs<QfNraResult.Unsatisfiable>(result)
            }
        }
    }

    @Test
    fun `unused source declarations should receive complete coordinates`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const y Real)
            (assert (>= (* x x) 0))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(1, assignment.boolCount)
        assertEquals(2, assignment.realCount)
        assertEquals(BigFraction.ZERO, assignment.realValue(1).rational)
    }

    @Test
    fun `numeric conditionals should retain every auxiliary coordinate`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and (not b) (= (ite b (* x x) 2) 2)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(source.realCount > source.sourceRealCount)
        assertEquals(source.realCount, assignment.realCount)
        assertEquals(false, assignment.boolValue(0))
    }

    @Test
    fun `constant contradictions should need no real dimensions`() {
        val source = SmtLibPolynomial.parse("(assert (= (* 2 2) 5)) (check-sat)")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `Boolean only formulas should receive a complete satisfying assignment`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const c Bool)
            (assert (xor b c))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, assignment.realCount)
        assertEquals(2, assignment.boolCount)
        assertTrue(assignment.boolValue(0) != assignment.boolValue(1))
    }

    @Test
    fun `wide rational coefficients should retain their exact witness`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (= x (/ 18446744073709551617 3)))
            (check-sat)
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(BigFraction.of(parseBigInt("18446744073709551617"), bigIntOf(3)), value.rational)
    }

    @Test
    fun `pre cancelled calls should publish unknown without work`() {
        val source = SmtLibPolynomial.parse("(assert false) (check-sat)")

        val result = solveQfNra(source.formula, 0, 0, QfNraParams(cancellation = ExecutionControl { true }))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertEquals(0L, unknown.stats.workSpent)
    }

    @Test
    fun `exhaustion before refutation publication should return unknown`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (< (* x x) 0)) (check-sat)")
        val completed = solveQfNra(source.formula, source.boolCount, source.realCount)
        val limit = assertIs<QfNraResult.Unsatisfiable>(completed).stats.workSpent - 1

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(maximumWork = limit))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `caller interruption after lifting should publish no witness`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (assert (and (> x 1) (< x 2)))
            (check-sat)
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : ExecutionControl() {
            override fun isCancelled(): Boolean = spent >= limit
            override val accounting: WorkMeter get() = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertTrue(unknown.stats.liftedCells > 0)
        assertEquals(spent, unknown.stats.workSpent)
    }

    @Test
    fun `total equality sections can refute without matrix allocation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x y) 1) (= (+ x y) 0)))
            (check-sat)
            """.trimIndent(),
        )
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `matrix allowance stops should never supply a refutation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (+ (* x0 x0) (* x1 x1)) 1))",
        )
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.ResourceLimit, unknown.reason)
        assertEquals(PolynomialResource.MATRIX_CELLS, unknown.resource)
    }

    @Test
    fun `coupled sections refute a repeated support before regional projection`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" } +
            (0 until 4).joinToString(" ") { " (declare-const a$it Bool) (declare-const b$it Bool)" }
        val prefix = (0 until 4).joinToString(" ") { "(or a$it b$it)" }
        val source = SmtLibPolynomial.parse("$declarations (assert (and $prefix (= (* x3 x4) 1) " +
            "(= (* x0 x0) 2) (= (* x1 x1) 2) (= (* x2 x2) 3) (= (+ (* x1 x2) x1 x2) 1)))")
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(coupledSections = true), PolynomialCondition())

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        val stats = assertIs<QfNraResult.Unsatisfiable>(result).stats
        assertEquals(0L, stats.booleanTheoryRegions)
        assertEquals(0L, stats.projectedPolynomials)
        assertEquals(0L, stats.liftedCells)
        assertEquals(1L, query.progress.coupledConflicts)
    }

    @Test
    fun `returned witnesses should survive buffer mutation and another invocation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and b (= (* x x) 2) (> x 0)))
            (check-sat)
            """.trimIndent(),
        )
        val result = solveQfNra(source.formula, source.boolCount, source.realCount)
        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment

        assignment.bools[0] = false
        runCatching { (assignment.reals as MutableList<*>).clear() }
        solveQfNra(source.formula, source.boolCount, source.realCount)

        assertTrue(assignment.boolValue(0))
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), assignment.realValue(0)))
    }

    @Test
    fun `an opaque containing meter should remain live after arithmetic construction`() {
        var charged = 0L
        var active: WorkMeter? = null
        val work = QfNraWork(QfNraParams(cancellation = object : ExecutionControl() {
            override fun isCancelled(): Boolean = false
            override val accounting: WorkMeter? get() = active
        }))
        val arithmetic = PolynomialArithmetic(work)
        active = WorkMeter { charged += it }

        arithmetic.multiply(Polynomial.ONE, Polynomial.ONE)

        assertEquals(2L, charged)
        assertEquals(2L, work.spentWork)
    }

    @Test
    fun `an optional probe should retain a containing stop that clears after observation`() {
        var pulse = false
        val work = QfNraWork(QfNraParams(cancellation = ExecutionControl {
            val observed = pulse
            pulse = false
            observed
        }))
        val arithmetic = PolynomialArithmetic(work)

        assertFailsWith<PolynomialInterrupted> {
            work.probe {
                arithmetic.execution.operation {
                    repeat(3) { arithmetic.step() }
                    pulse = true
                    arithmetic.checkpoint()
                }
                null
            }
        }

        assertFalse(pulse)
        assertEquals(3L, work.spentWork)
    }
}
