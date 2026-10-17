package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialBooleanProjectionTest {
    @Test
    fun `fixed guards remove inactive boundaries while retaining every source coordinate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" } +
            (0..64).joinToString(" ") { "(declare-const b$it Bool)" }
        for (truth in listOf(false, true)) {
            val guard = if (truth) "b0" else "(not b0)"
            val source = SmtLibPolynomial.parse(
                "$declarations (assert (and $guard (or $guard (= (+ (* x0 x0) (* x1 x1)) 1))))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(33, witness.realCount)
            assertEquals(65, witness.boolCount)
            assertEquals(truth, witness.boolValue(0))
            assertTrue(result.stats.liftedCells > 0)
        }
    }

    @Test
    fun `negated compounds can entail Boolean guards without choosing a branch`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const a Bool) (declare-const b Bool)
            (assert (and (not (or (not a) (not b))) (or a (= (+ (* x0 x0) (* x1 x1)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertTrue(witness.boolValue(1))
    }

    @Test
    fun `an unresolved guard retains all numeric alternatives`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or b (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val stopped = assertIs<QfNraResult.Unknown>(result)
        assertEquals(PolynomialResource.MATRIX_CELLS, stopped.resource)
    }

    @Test
    fun `a required compound retains its unresolved numeric constraint`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (or (not b) (= (+ (* x0 x0) (* x1 x1)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `negative numeric requirements retain their projection boundaries`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (not (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `shared numeric predicates remain boundaries for their active occurrences`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1)) (= (+ (* x0 x0) (* x1 x1)) 1)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `a conflicting unit closure retains the original projection obligation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (not b) (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `reduced projection still solves every active numeric source constraint`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (= x0 3) (or b (= (+ (* x1 x1) (* x2 x2)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(BigFraction.ofLong(3), assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `Boolean facts derived from numeric requirements retain those requirements`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (= x0 3) (= b (= x0 3)) (or b (= (+ (* x1 x1) (* x2 x2)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ofLong(3), witness.realValue(0).rational)
        assertTrue(witness.boolValue(0))
    }

    @Test
    fun `active numeric contradictions remain refutable after inactive boundaries are removed`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (> x0 0) (< x0 0) (or b (= (+ (* x1 x1) (* x2 x2)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `retracted Boolean pins restore the guarded projection family`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or b (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to true))))
            val unmasked = invocation.check(PolynomialCondition(bools = mapOf(0 to false)))
            assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(unmasked).resource)
            assertIs<QfNraResult.Satisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to true))))
        }
    }

    @Test
    fun `inactive boundaries do not discard fixed source coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val fixed = mapOf(1 to BigFraction.ofLong(7))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
            PolynomialCondition(lower = fixed, upper = fixed))

        assertEquals(BigFraction.ofLong(7), assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(1).rational)
    }

    @Test
    fun `interruption exposes no partially reduced projection family`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const x Real) (assert (and b (or b (= (* x x) 2))))",
        )
        val normalized = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val stopped = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { normalized.projectionPolynomials(stopped) }
    }

    @Test
    fun `the containing work limit withholds a reduced projection witness`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val complete = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val stopped = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = complete.stats.workSpent - 1,
                polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }

    @Test
    fun `invocation observations accumulate completed boundary reductions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            assertIs<QfNraResult.Satisfiable>(invocation.check())

            assertEquals(2L, invocation.stats.booleanProjectionAtomsPruned)
        }
    }

    @Test
    fun `numeric sources have no Boolean projection observation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse("$declarations (assert (= x0 3))")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Satisfiable>(result)
        assertNull(result.stats.booleanProjectionAtomsPruned)
    }
}
