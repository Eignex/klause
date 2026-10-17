package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QfNraSolverTest {
    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `dense strict affine regions retain all original coordinates and Boolean pins`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val rows = listOf("(+ x0 x1)" to 2, "(- x0 x1)" to 2,
            "(+ (* 2 x0) x1)" to 4, "(- (* 2 x0) x1)" to 4)
        val bounds = rows.joinToString(" ") { (term, upper) ->
            "(assert (> $term 0)) (assert (< $term $upper))"
        }
        val source = SmtLibPolynomial.parse("$declarations (declare-const b Bool) (assert b) $bounds")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val x = witness.realValue(0)
        val y = witness.realValue(1)
        for ((value, upper) in listOf(algebraic.add(x, y) to 2L, algebraic.subtract(x, y) to 2L,
            algebraic.add(algebraic.multiply(algebraic.rational(BigFraction.ofLong(2)), x), y) to 4L,
            algebraic.subtract(algebraic.multiply(algebraic.rational(BigFraction.ofLong(2)), x), y) to 4L)) {
            assertTrue(algebraic.sign(value) > 0)
            assertTrue(algebraic.compare(value, algebraic.rational(BigFraction.ofLong(upper))) < 0)
        }
        assertEquals(33, witness.realCount)
        assertTrue(witness.boolValue(0))
        assertEquals(0L, result.stats.dyadicAttempts)
        assertEquals(0L, result.stats.liftedCells)
    }

    @Test
    fun `dense affine conflicts refute weak and strict endpoint contradictions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val other = listOf("(- x0 x1)", "(+ (* 2 x0) x1)", "(- (* 2 x0) x1)")
            .joinToString(" ") { "(assert (>= $it (- 10))) (assert (<= $it 10))" }
        for ((relation, lower) in listOf(">=" to 1, ">" to 0)) {
            val source = SmtLibPolynomial.parse(
                "$declarations (assert ($relation (+ x0 x1) $lower)) (assert (<= (+ x0 x1) 0)) $other",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            assertIs<QfNraResult.Unsatisfiable>(result)
            assertEquals(0L, result.stats.liftedCells)
        }
    }

    @Test
    fun `an enclosing work meter interrupts affine completion without publication`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val rows = listOf("(+ x0 x1)" to 2, "(- x0 x1)" to 2,
            "(+ (* 2 x0) x1)" to 4, "(- (* 2 x0) x1)" to 4)
        val bounds = rows.joinToString(" ") { (term, upper) ->
            "(assert (> $term 0)) (assert (< $term $upper))"
        }
        val source = SmtLibPolynomial.parse("$declarations $bounds")
        val completed = solveQfNra(source.formula, source.boolCount, source.realCount)
        val limit = completed.stats.workSpent / 2
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= limit
            override fun workMeter(): WorkMeter = meter
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation = token))

        assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, result.reason)
        assertEquals(charged, result.stats.workSpent)
        assertTrue(charged >= limit)
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
        assertEquals(2L, result.stats.equationalSections)
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
