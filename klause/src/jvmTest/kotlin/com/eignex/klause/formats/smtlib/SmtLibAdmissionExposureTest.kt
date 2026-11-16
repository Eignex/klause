package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class SmtLibAdmissionExposureTest(private val source: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sources(): List<Array<String>> = listOf(
            """
            (declare-const a (Array Bool Bool))
            (define-fun read ((i Bool)) Bool (select a i))
            (assert (select a true))
            (assert (let ((i false)) (not (read true))))
            """,
            """
            (define-sort A () (Array Bool Bool))
            (declare-const a0 A)
            (declare-fun f (A) Bool)
            (define-fun read ((a A)) Bool (f a))
            (assert (let ((a (store a0 true true))) (distinct (read a) (f a))))
            """,
            """
            (declare-datatype Box ((box (value Bool))))
            (define-fun read ((b Box)) Bool (value b))
            (assert (let ((b (box true))) (not (read b))))
            """,
            """
            (declare-fun f (Bool) Bool)
            (define-fun read ((p Bool)) Bool (f p))
            (assert (let ((p true)) (distinct (read p) (f p))))
            """,
        ).map { arrayOf(it.trimIndent()) }
    }

    @Test
    fun `ground helper exposure should preserve source contradictions`() {
        val parsed = SmtLib.parse(source)

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
