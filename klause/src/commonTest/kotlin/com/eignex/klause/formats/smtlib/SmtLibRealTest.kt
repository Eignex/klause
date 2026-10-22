package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryCompletion
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmtLibRealTest {
    @Test
    fun `identical folded real comparisons should share one predicate literal`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= a (<= x 1.0)))
                (assert (= b (<= (+ x 0.0) 1.0)))
            """.trimIndent(),
        )

        val predicates = source.model.factors.filterIsInstance<ExactLinear>()
            .filter { it.activator != LinearRow.ALWAYS }

        assertEquals(1, predicates.size)
    }

    @Test
    fun `repeated real predicates should agree in either Boolean polarity`() {
        for (truth in listOf(false, true)) {
            val source = SmtLib.parse(
                """
                    (declare-const x Real)
                    (declare-const a Bool)
                    (declare-const b Bool)
                    (assert (= a (> x 0.0)))
                    (assert (= b (> x 0.0)))
                    (assert (= a $truth))
                    (assert (= b ${!truth}))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Unsat>(result)
        }
    }

    @Test
    fun `strict and closed real predicates should retain different boundary truth`() {
        for ((closed, strict) in listOf("<=" to "<", ">=" to ">")) {
            val source = SmtLib.parse(
                """
                    (declare-const x Real)
                    (declare-const a Bool)
                    (declare-const b Bool)
                    (assert (= x 0.0))
                    (assert (= a ($closed x 0.0)))
                    (assert (= b ($strict x 0.0)))
                    (assert a)
                    (assert (not b))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Sat>(result)
        }
    }

    @Test
    fun `opposite real comparison directions should retain different truth`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= x 1.0))
                (assert (= a (< x 0.0)))
                (assert (= b (> x 0.0)))
                (assert (not a))
                (assert b)
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `integer and real predicate coordinates should retain separate namespaces`() {
        val source = SmtLib.parse(
            """
                (declare-const n Int)
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= n 0))
                (assert (= x 1.0))
                (assert (= a (< (to_real n) 0.5)))
                (assert (= b (< x 0.5)))
                (assert a)
                (assert (not b))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `nearby wide rational predicates should retain exact truth`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= x 1.0))
                (assert (= a (< x (/ 1152921504606846977.0 1152921504606846976.0))))
                (assert (= b (< x 1.0)))
                (assert a)
                (assert (not b))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `shared real predicates should retain helper and local coordinate scopes`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (define-fun positive () Bool (> x 0.0))
                (assert (= x 1.0))
                (assert (= y (- 1.0)))
                (assert (let ((x y)) (= a positive)))
                (assert (let ((x y)) (= b (> x 0.0))))
                (assert a)
                (assert (not b))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `each source parse should allocate its own real predicate names`() {
        SmtLib.parse("(declare-const x Real) (assert (or (> x 0.0) false))")
        val source = SmtLib.parse(
            """
                (declare-const y Real)
                (declare-const a Bool)
                (assert (not a))
                (assert (= y 1.0))
                (assert (or (> y 0.0) false))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `wide real coefficients should retain original coordinates and exact witness values`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* $coefficient x) 1))")

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(
            BigFraction.of(BIG_ONE, parseBigInt(coefficient)),
            TheoryCompletion.parseRational(point.realValue(0)),
        )
        assertEquals(1, source.model.numRealVars)
        assertEquals(1, source.model.factors.filterIsInstance<ExactLinear>().size)
    }

    @Test
    fun `wide integer bindings should retain exact coefficients in real comparisons`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse(
            "(declare-const n Int) (declare-const x Real) (assert (= n 1)) " +
                "(assert (let ((wide (* $coefficient n))) (= wide x)))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(BigFraction.of(parseBigInt(coefficient), BIG_ONE),
            TheoryCompletion.parseRational(point.realValue(source.realVarNames.getValue("x"))))
        assertEquals(1, source.model.numRealVars)
    }

    @Test
    fun `wide reified strict comparisons should preserve an excluded boundary`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse(
            "(declare-const x Real) (assert (= (* $coefficient x) 1)) " +
                "(assert (or (< (* $coefficient x) 0) (> (* $coefficient x) 1)))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(1, source.model.numRealVars)
    }

    @Test
    fun `wide rational integer comparisons should preserve reified lattice truth`() {
        val numerator = "100000000000000000000000000000000000000000000000001"
        val denominator = "100000000000000000000000000000000000000000000000000"
        val source = SmtLib.parse(
            "(declare-const n Int) (declare-const b Bool) (assert (= n 1)) " +
                "(assert (= b (< (/ (* $numerator (to_real n)) $denominator) 1))) (assert (not b))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("1", point.intValue(source.intVarNames.getValue("n")))
        assertEquals(false, point.boolValue(source.boolVarNames.getValue("b")))
        assertEquals(0, source.model.numRealVars)
    }
}
