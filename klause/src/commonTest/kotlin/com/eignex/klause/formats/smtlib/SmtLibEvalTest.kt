package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertIs

class SmtLibEvalTest {
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
