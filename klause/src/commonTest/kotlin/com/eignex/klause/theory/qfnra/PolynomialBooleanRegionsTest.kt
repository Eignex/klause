package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolynomialBooleanRegionsTest {
    @Test
    fun `a refuted first region does not hide a later numeric root`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (= x0 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ofLong(3), witness.realValue(0).rational)
    }

    @Test
    fun `all numeric regions must be refuted before source exhaustion`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (> x0 0) (< x0 0))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `an indeterminate numeric region withholds source exhaustion`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= (+ (* x0 x0) (* x1 x1)) 1))
                        (and b (> x0 0) (< x0 0))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val stopped = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.ResourceLimit, stopped.reason)
        assertEquals(PolynomialResource.MATRIX_CELLS, stopped.resource)
    }

    @Test
    fun `a refused first region permits a later checked numeric witness`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= (+ (* x0 x0) (* x1 x1)) 1)) (and b (= x0 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ofLong(3), witness.realValue(0).rational)
    }

    @Test
    fun `numeric disjunctions preserve alternatives without source Boolean variables`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (= x0 0) (= x0 1)) (= x0 3)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(BigFraction.ofLong(3), assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `shared predicates retain their correlation across Boolean regions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (or b (= x0 3)) (or (not b) (= x0 3)) (not (= x0 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `region checkpoints resume without repeating native work`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (= x0 3))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val complete = service.invocation().use { it.check() }

        service.invocation().use { invocation ->
            val pending = invocation.prepareCheck()
            var result: QfNraResult? = null
            while (result == null) result = pending.next()

            assertIs<QfNraResult.Satisfiable>(result)
            assertEquals(complete.stats, result.stats)
        }
    }

    @Test
    fun `numeric regions share the containing work limit`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (= x0 3))))
            """.trimIndent(),
        )
        val complete = solveQfNra(source.formula, source.boolCount, source.realCount)

        val stopped = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = complete.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }

    @Test
    fun `empty Boolean gates preserve their source truth`() {
        for ((formula, feasible) in listOf(
            PolynomialRealFormula.And(emptyList()) to true,
            PolynomialRealFormula.Or(emptyList()) to false,
        )) {
            val result = solveQfNra(formula, 0, 33)

            if (feasible) assertIs<QfNraResult.Satisfiable>(result)
            else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

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
    fun `a refused numeric region does not suppress a feasible Boolean alternative`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or b (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertTrue(assertIs<QfNraResult.Satisfiable>(result).assignment.boolValue(0))
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
    fun `complete Boolean contradiction refutes before numeric projection`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (not b) (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, result.stats.projectedPolynomials)
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
    fun `interruption exposes no incomplete region coverage`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const x Real) (assert (and b (or b (= (* x x) 2))))",
        )
        val normalized = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val stopped = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { normalized.booleanRegions(stopped).hasNext() }
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
    fun `invocation observations accumulate conditional theory regions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            assertIs<QfNraResult.Satisfiable>(invocation.check())

            assertEquals(2L, invocation.stats.booleanTheoryRegions)
        }
    }

    @Test
    fun `numeric conjunctions retain a single complete region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse("$declarations (assert (= x0 3))")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Satisfiable>(result)
        assertEquals(1L, result.stats.booleanTheoryRegions)
    }
}
