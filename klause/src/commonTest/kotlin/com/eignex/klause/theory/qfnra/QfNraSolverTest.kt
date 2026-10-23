package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfNraSolverTest {
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
        assertTrue(query.progress.searchWork > 0)
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

        work.probe { entered = true; null }

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
    fun `retained equalities reduce projection predicates without removing coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val expression = "(+ (* x0 x0) (* x1 x1) x0)"
        for ((lower, upper) in listOf(
            "(> (* 4 $expression) 5)" to "(< (* 3 $expression) 4)",
            "(not (<= (* 4 $expression) 5))" to "(not (>= (* 3 $expression) 4))",
        )) {
            val source = SmtLibPolynomial.parse(
                "$declarations (assert (= (+ (* x0 x0) (* x1 x1)) 1)) (assert $lower) (assert $upper)",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000, polynomialLimits = PolynomialLimits(maxMatrixCells = 9)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertTrue(algebraic.compare(witness.realValue(0),
                algebraic.rational(BigFraction.ofLong(4).reciprocal())) > 0)
            assertTrue(algebraic.compare(witness.realValue(0),
                algebraic.rational(BigFraction.ofLong(3).reciprocal())) < 0)
            val norm = algebraic.add(algebraic.power(witness.realValue(0), 2),
                algebraic.power(witness.realValue(1), 2))
            assertEquals(0, algebraic.compare(norm, algebraic.rational(BigFraction.ONE)))
            assertEquals(33, witness.realCount)
        }
    }

    @Test
    fun `partial even powers retain zero boundary completions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for (predicate in listOf(
            "(= (+ (* x1 x1) (* x2 x2)) 0)",
            "(not (> (+ (* x1 x1) (* x2 x2)) 0))",
            "(>= (- (+ (* x1 x1) (* x2 x2))) 0)",
        )) {
            val source = SmtLibPolynomial.parse(
                "$declarations (assert (= (* x0 x0) 1)) (assert $predicate)",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            val point = assertIs<QfNraResult.Satisfiable>(result, result.toString()).assignment
            assertEquals(0, algebraic.sign(point.realValue(1)))
            assertEquals(0, algebraic.sign(point.realValue(2)))
        }
    }

    @Test
    fun `partial odd powers retain sign changing completions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 1)) (assert (= (+ (* x1 x1 x1) (* x0 x0)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result, result.toString()).assignment
        assertEquals(0, algebraic.compare(point.realValue(1), algebraic.rational(BigFraction.MINUS_ONE)))
    }

    @Test
    fun `independent missing boundaries avoid Cartesian sample products`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val constraints = (0 until 8).joinToString(" ") {
            "(assert (= (+ (* x${2 * it} x${2 * it}) (* x${2 * it + 1} x${2 * it + 1})) 1))"
        }
        val source = SmtLibPolynomial.parse("$declarations $constraints")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 2_000_000))

        val point = assertIs<QfNraResult.Satisfiable>(result, result.toString()).assignment
        for (index in 0 until 8) {
            val norm = algebraic.add(algebraic.power(point.realValue(2 * index), 2),
                algebraic.power(point.realValue(2 * index + 1), 2))
            assertEquals(0, algebraic.compare(norm, algebraic.rational(BigFraction.ONE)))
        }
    }

    @Test
    fun `a rejected mixed boundary retains later partial samples`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 1)) (assert (> (+ (* x1 x1) x0) 0))",
        )
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 4))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(point.realValue(0), algebraic.rational(BigFraction.ONE)))
        assertEquals(0, algebraic.compare(point.realValue(1), algebraic.rational(BigFraction.ZERO)))
    }

    @Test
    fun `an independent disequality retains its nonzero samples`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 1)) (assert (not (= (* x1 x1) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(algebraic.power(point.realValue(0), 2),
            algebraic.rational(BigFraction.ONE)))
        assertTrue(algebraic.sign(algebraic.power(point.realValue(1), 2)) > 0)
    }

    @Test
    fun `an exact sample can satisfy predicates before their full projection fits`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 1)) (assert (> (- (* x1 x1) x0) 0))",
        )
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 4))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(point.realValue(0), algebraic.rational(BigFraction.MINUS_ONE)))
        assertEquals(0, algebraic.compare(point.realValue(1), algebraic.rational(BigFraction.ZERO)))
    }

    @Test
    fun `exhausted partial samples leave unprojected root boundaries searchable`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 2)) (assert (= (* x0 x1) 1))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(algebraic.power(point.realValue(0), 2),
            algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(0, algebraic.compare(algebraic.multiply(point.realValue(0), point.realValue(1)),
            algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `incompatible equality fibres remain refutable after partial sampling`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 2)) (assert (= (* x0 x1) 1)) " +
                "(assert (= (* x1 x1) 1))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `a partial projection refusal retains the complete required family`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (> (+ (* x0 x0 x0 x0) (* x1 x1 x1 x1)) 0)) " +
                "(assert (= (+ (* x0 x0) (* x1 x1)) 1))",
        )
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 36))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        val norm = algebraic.add(algebraic.power(point.realValue(0), 2), algebraic.power(point.realValue(1), 2))
        assertEquals(0, algebraic.compare(norm, algebraic.rational(BigFraction.ONE)))
        assertTrue(algebraic.sign(algebraic.add(algebraic.power(point.realValue(0), 4),
            algebraic.power(point.realValue(1), 4))) > 0)
    }

    @Test
    fun `containing cancellation withholds exhaustion of a partial projection`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x0) 2)) (assert (= (* x0 x1) 1))",
        )
        var stopped = false
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(cancellation = Cancellation { stopped }), PolynomialCondition())
        var result: QfNraResult? = null
        while (result == null && query.stats.liftedCells == 0L) result = query.next()
        assertNull(result)

        stopped = true
        while (result == null) result = query.next()

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertTrue(unknown.stats.liftedCells > 0L)
    }

    @Test
    fun `reduced region observation retains predicates without changing native work`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= x0 (* x1 x1))) (assert (not (= x0 0)))",
        )
        val plain = solveQfNra(source.formula, source.boolCount, source.realCount)
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(), PolynomialCondition())
        var atoms: List<PolynomialAtom>? = null
        query.reducedRegionObserver = { atoms = it }

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        assertIs<QfNraResult.Satisfiable>(plain)
        assertIs<QfNraResult.Satisfiable>(result)
        assertEquals(plain.stats.workSpent, result.stats.workSpent)
        val atom = checkNotNull(atoms).single()
        assertEquals(PolynomialRelation.NE, atom.relation)
        assertEquals(listOf(1), atom.polynomial.variables)
        assertEquals(2, atom.polynomial.degree(1))
    }

    @Test
    fun `graph reduction retains a false zero disequality`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 0) (not (= (* x0 x1) 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, result.stats.liftedCells)
    }

    @Test
    fun `graph reductions and affine fibers reconstruct every original coordinate in reverse order`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (+ (* x1 x1) 1)) (= x2 (+ x0 x3)) " +
                "(>= x3 x1) (<= x3 (+ x1 1))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val square = algebraic.power(witness.realValue(1), 2)
        assertEquals(0, algebraic.compare(witness.realValue(0),
            algebraic.add(square, algebraic.rational(BigFraction.ONE))))
        assertEquals(0, algebraic.compare(witness.realValue(2),
            algebraic.add(witness.realValue(0), witness.realValue(3))))
        assertTrue(algebraic.compare(witness.realValue(3), witness.realValue(1)) >= 0)
        assertTrue(algebraic.compare(witness.realValue(3),
            algebraic.add(witness.realValue(1), algebraic.rational(BigFraction.ONE))) <= 0)
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `graph reductions expose an affine contradiction on remaining coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (+ x1 1)) (>= x0 0) (<= x1 (- 2))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(1L, result.stats.equationalSections)
    }

    @Test
    fun `affine elimination preserves strictness at coincident endpoints`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for ((relation, feasible) in listOf(">=" to true, ">" to false)) {
            val source = SmtLibPolynomial.parse("$declarations (assert ($relation x0 1)) (assert (<= x0 1))")

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (feasible) {
                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ONE)))
                assertEquals(33, witness.realCount)
            } else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `affine elimination reconstructs one sided polynomial fibers in reverse order`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for ((relation, direction) in listOf(">" to 1, "<" to -1)) {
            val boundary = if (direction > 0) "(* x1 x1)" else "(- (* x1 x1))"
            val source = SmtLibPolynomial.parse(
                "$declarations (assert ($relation x0 $boundary)) (assert (> x1 0))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            val square = algebraic.power(witness.realValue(1), 2)
            val bound = if (direction > 0) square else algebraic.negate(square)
            assertTrue(algebraic.compare(witness.realValue(0), bound) * direction > 0)
            assertTrue(algebraic.sign(witness.realValue(1)) > 0)
            assertEquals(33, witness.realCount)
        }
    }

    @Test
    fun `affine elimination refutes a strict inequality cycle without matrices`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (> x0 x1) (> x1 x2) (>= x2 x0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `affine elimination retains variable coefficient zero fibers and required equations`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 0) (= x1 1) (>= (* x0 x1) 0) (> x2 x1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.compare(witness.realValue(1), algebraic.rational(BigFraction.ONE)))
        assertTrue(algebraic.compare(witness.realValue(2), witness.realValue(1)) > 0)
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `affine elimination preserves a disequality on a remaining coordinate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (not (= x0 0)) (> x1 x0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(algebraic.sign(witness.realValue(0)) != 0)
        assertTrue(algebraic.compare(witness.realValue(1), witness.realValue(0)) > 0)
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `affine elimination reconstructs coincident irrational endpoints exactly`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x1 x1) 2) (> x1 0) (>= x0 x1) (<= x0 x1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
        assertEquals(0, algebraic.compare(witness.realValue(0), witness.realValue(1)))
        assertTrue(algebraic.sign(witness.realValue(0)) > 0)
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `affine elimination reconstructs a strict interval between irrational endpoints`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x1 x1) 2) (> x1 0) (= (* x2 x2) 3) (> x2 0) " +
                "(> x0 x1) (< x0 x2)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(algebraic.compare(witness.realValue(0), witness.realValue(1)) > 0)
        assertTrue(algebraic.compare(witness.realValue(0), witness.realValue(2)) < 0)
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `affine elimination retains Boolean pins and negated numeric requirements`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (= x1 0)) " +
                "(assert (or (and b (not (<= x0 x1))) (and (not b) (<= x0 x1))))",
        )
        for (truth in listOf(true, false, true)) {
            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                condition = PolynomialCondition(bools = mapOf(0 to truth)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(truth, witness.boolValue(0))
            assertEquals(truth, algebraic.compare(witness.realValue(0), witness.realValue(1)) > 0)
            assertEquals(33, witness.realCount)
        }
    }

    @Test
    fun `factored weak boundaries retain either zero coordinate fiber`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for (fixed in 0..1) {
            val source = SmtLibPolynomial.parse(
                "$declarations (assert (and (> x${1 - fixed} 0) (>= x$fixed 0) (<= x$fixed 0) " +
                    "(>= (* x0 x0 x1 x1 (+ x0 x1)) 0) (<= (* x0 x0 x1 x1 (- x0 x1)) 0)))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(0, algebraic.sign(witness.realValue(fixed)))
            assertTrue(algebraic.sign(witness.realValue(1 - fixed)) > 0)
            assertEquals(33, witness.realCount)
        }
    }

    @Test
    fun `factored boundaries retain odd monomial signs in original predicates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (>= x0 (- 1)) (<= x0 (- 1)) (>= x1 2) " +
                "(>= (* x0 x0 x0 x1 (+ x0 x1)) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `affine boundary intersections solve without dense matrix allocation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (> x0 0) (>= (* x0 x1) 1) (<= (* x0 x1) 2)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val product = algebraic.multiply(witness.realValue(0), witness.realValue(1))
        assertTrue(algebraic.sign(witness.realValue(0)) > 0)
        assertTrue(algebraic.compare(product, algebraic.rational(BigFraction.ONE)) >= 0)
        assertTrue(algebraic.compare(product, algebraic.rational(BigFraction.ofLong(2))) <= 0)
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `affine projection retains zero leading coefficient fibers`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (>= x0 0) (<= x0 0) (>= (* x0 x1) 0) (>= (+ (* x0 x1) 1) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(33, witness.realCount)
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

        val result = solveQfNra(source.formula, 0, 0, QfNraParams(cancellation = Cancellation { true }))

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
}
