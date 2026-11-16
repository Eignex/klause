package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QfNraReductionTest {
    private val algebraic = AlgebraicArithmetic()

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
        lateinit var query: QfNraQuery
        query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(cancellation = ExecutionControl { query.stats.liftedCells > 0L }), PolynomialCondition())
        var result: QfNraResult? = null
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
            } else
                assertIs<QfNraResult.Unsatisfiable>(result)
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
}
