package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibBooleanDefinitionsTest {
    @Test
    fun `cancelled empty Boolean definitions should not emit a constant`() {
        for (conjunction in listOf(false, true)) {
            val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI,
                strictBounds = false, sourceCancellation = Cancellation { true })

            assertFailsWith<PolynomialSourceInterrupted> {
                if (conjunction) builder.reifyAnd(emptyList()) else builder.reifyOr(emptyList())
            }

            assertTrue(builder.factors.isEmpty())
            assertEquals(0, builder.nextBool)
        }
    }

    @Test
    fun `one comparison should not allocate a singleton Boolean wrapper`() {
        for (sort in listOf("Int", "Real")) {
            val source = SmtLib.parse("(declare-const x $sort) (assert (not (<= x 0)))")

            assertEquals(1, source.model.numBoolVars)
        }
    }

    @Test
    fun `empty Boolean definitions should reuse the library constant`() {
        for (operation in listOf("and", "or")) {
            val source = SmtLib.parse("(declare-const p Bool) (assert (= p ($operation)))")

            assertEquals(2, source.model.numBoolVars)
        }
    }

    @Test
    fun `neutral and repeated operands should retain the source literal truth`() {
        for (expression in listOf("(and p true p)", "(or p false p)")) {
            for (truth in listOf(false, true)) {
                val source = SmtLib.parse(
                    """
                        (declare-const p Bool)
                        (declare-const q Bool)
                        (declare-const x Real)
                        (assert (= p $truth))
                        (assert (= q $expression))
                    """.trimIndent(),
                )

                val result = BacktrackSolver.source(source.model).solve()

                val point = assertIs<OpenTheoryResult.Sat>(result).assignment
                assertEquals(truth, point.boolValue(source.boolVarNames.getValue("q")))
            }
        }
    }

    @Test
    fun `absorbing and complementary operands should retain constant truth`() {
        val expressions = listOf(
            "(and p false)" to false,
            "(or p true)" to true,
            "(and p (not p))" to false,
            "(or p (not p))" to true,
        )
        for ((expression, expected) in expressions) {
            val source = SmtLib.parse(
                """
                    (declare-const p Bool)
                    (declare-const q Bool)
                    (declare-const x Real)
                    (assert (= q $expression))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            val point = assertIs<OpenTheoryResult.Sat>(result).assignment
            assertEquals(expected, point.boolValue(source.boolVarNames.getValue("q")))
        }
    }

    @Test
    fun `Boolean equivalence identities should preserve either input polarity`() {
        for (truth in listOf(false, true)) {
            val expressions = listOf(
                "(= p p)" to true,
                "(= p (not p))" to false,
                "(= p true)" to truth,
                "(= p false)" to !truth,
                "(= true p)" to truth,
                "(= false p)" to !truth,
            )
            for ((expression, expected) in expressions) {
                val source = SmtLib.parse(
                    """
                        (declare-const p Bool)
                        (declare-const q Bool)
                        (declare-const x Real)
                        (assert (= p $truth))
                        (assert (= q $expression))
                    """.trimIndent(),
                )

                val result = BacktrackSolver.source(source.model).solve()

                val point = assertIs<OpenTheoryResult.Sat>(result).assignment
                assertEquals(expected, point.boolValue(source.boolVarNames.getValue("q")))
            }
        }
    }

    @Test
    fun `Boolean simplification should retain child source validation`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const b Bool) (assert (= b (or true (> (/ 1.0 0.0) 0.0))))")
        }
    }
}
