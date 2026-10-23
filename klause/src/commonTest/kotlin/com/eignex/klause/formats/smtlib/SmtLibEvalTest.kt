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

class SmtLibEvalTest {
    @Test
    fun `nested Boolean chains should share the flat gate definition`() {
        for (operator in listOf("and", "or")) {
            val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI, strictBounds = false)
            SExprReader("(declare-const a Bool) (declare-const b Bool) " +
                "(declare-const c Bool) (declare-const d Bool)").readAll().forEach(builder::command)

            val nested = builder.compileBool(SExprReader("($operator ($operator a b) ($operator c d))")
                .readAll().single())
            val flat = builder.compileBool(SExprReader("($operator a b c d)").readAll().single())

            assertEquals(nested, flat)
            assertEquals(5, builder.nextBool)
        }
    }

    @Test
    fun `Boolean chain operands should retain let and helper scopes`() {
        for ((operator, expected) in listOf("and" to false, "or" to true)) {
            val source = SmtLib.parse(
                """
                    (declare-const a Bool) (declare-const b Bool) (declare-const result Bool)
                    (define-fun value ((p Bool)) Bool ($operator a p))
                    (assert (= a false)) (assert (= b false))
                    (assert (let ((a true))
                        (= result ($operator ($operator (value b) a)
                            (let ((a b) (b a)) ($operator a b))))))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertEquals(expected, assertIs<OpenTheoryResult.Sat>(result).assignment
                .boolValue(source.boolVarNames.getValue("result")))
        }
    }

    @Test
    fun `cancelled Boolean chain expansion should not emit a gate`() {
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 4L
            override fun charge(units: Long) { charged += units }
        }
        val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI,
            strictBounds = false, sourceCancellation = stop)
        val expression = SExprReader("(and (and true true) (and true true))").readAll().single()

        assertFailsWith<PolynomialSourceInterrupted> { builder.compileBool(expression) }

        assertEquals(0, builder.nextBool)
        assertTrue(builder.factors.isEmpty())
    }

    @Test
    fun `Boolean chain operands should report the first malformed expression`() {
        val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI, strictBounds = false)
        val expression = SExprReader("(and false (and (not true false) (ite true true)))").readAll().single()

        val error = assertFailsWith<UnsupportedSmtException> { builder.compileBool(expression) }

        assertTrue(error.message!!.contains("not expects 1 argument"))
    }

    @Test
    fun `recursive helpers should be rejected before body expansion`() {
        for (source in listOf(
            "(define-fun value () Int value) (assert (= value 1))",
            "(define-fun value ((n Int)) Int (value n)) (assert (= (value 1) 1))",
        )) {
            assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
        }
    }

    @Test
    fun `helpers should reject arguments outside their declared sorts even when unused`() {
        for ((sort, argument) in listOf("Real" to "false", "Int" to "1.5", "Bool" to "1")) {
            val source = "(define-fun accepts ((p $sort)) Bool true) (assert (accepts $argument))"

            assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
        }
    }

    @Test
    fun `real helper parameters should promote integer arguments before evaluating the body`() {
        val source = SmtLib.parse(
            "(declare-const x Real) (define-fun half ((p Real)) Real (/ p 2.0)) " +
                "(assert (= x (half 3))) (assert (= x 1.5))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `helper arguments should use the caller while free names use declarations`() {
        val source = SmtLib.parse(
            """
                (declare-const x Int)
                (define-fun f ((p Int)) Int (+ x p))
                (assert (= x 1))
                (assert (let ((x 2)) (= (+ (f x) x) 5)))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `nested helper calls should restore each enclosing scope`() {
        val source = SmtLib.parse(
            """
                (declare-const x Int)
                (define-fun f ((p Int)) Int (+ x p))
                (define-fun g ((q Int)) Int (+ (f q) x))
                (assert (= x 1))
                (assert (let ((x 100)) (= (+ (g 2) x) 104)))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `zero arity real helpers should exclude caller bindings`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (define-fun value () Real x)
                (assert (= x 1.0))
                (assert (let ((x 2.0)) (= value 1.0)))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `zero arity Boolean helpers should exclude caller bindings`() {
        for (truth in listOf(false, true)) {
            val source = SmtLib.parse(
                "(declare-const b Bool) (define-fun flag () Bool b) " +
                    "(assert (= b $truth)) (assert (let ((b ${!truth})) (= flag $truth)))",
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Sat>(result)
        }
    }

    @Test
    fun `local let bindings should use their own sorts when shadowing helper names`() {
        for (text in listOf(
            "(define-fun value () Int 3) (assert (let ((value false)) (= value false)))",
            "(define-fun value () Bool true) (assert (let ((value 1.5)) (= value 1.5)))",
        )) {
            val source = SmtLib.parse(text)

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Sat>(result)
        }
    }
}
