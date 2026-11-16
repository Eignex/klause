package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtRecursiveSessionTest {
    @Test
    fun `inconsistent recursive equations have a sound finite refutation`() {
        val source = "(define-fun-rec f ((x Int)) Int (+ 1 (f x))) (check-sat)"

        val result = solveSmtRecursiveSession(source, ::solve)

        assertEquals("unsat", result.status)
    }

    @Test
    fun `a universally decreasing recursive definition has a total checked witness`() {
        val source = """
            (define-fun-rec f ((x Int)) Int (ite (<= x 0) 0 (+ 1 (f (- x 1)))))
            (assert (= (f 4) 4))
            (check-sat)
        """.trimIndent()

        val result = solveSmtRecursiveSession(source, ::solve)

        assertEquals("sat", result.status)
        assertEquals("7", result.model?.evaluate(SExprReader("(f 7)").readAll().single()))
    }

    @Test
    fun `a nonterminating equation can admit a certified total interpretation`() {
        val source = "(define-fun-rec f ((x Int)) Int (f x)) (assert (= (f 3) 7)) (check-sat)"

        val result = solveSmtRecursiveSession(source, ::solve)

        assertEquals("sat", result.status)
        assertEquals("7", result.model?.evaluate(SExprReader("(f 3)").readAll().single()))
    }

    private fun solve(source: String): SmtSessionResult {
        val parsed = SmtLib.parse(source)
        return when (val result = BacktrackSolver.source(parsed.model).solve()) {
            is OpenTheoryResult.Sat -> SmtSessionResult("sat", parsed.reconstructSessionModel(result.assignment))
            is OpenTheoryResult.Unsat -> SmtSessionResult("unsat")
            is OpenTheoryResult.Unknown -> SmtSessionResult("unknown")
        }
    }
}
