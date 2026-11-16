package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibBoundsTest {
    @Test
    fun `private quotient coordinates should retain their declared open bounds during inference`() {
        val original = captureSmtSource(SExprReader("(declare-const x Int)").readAll(), Cancellation.Never)
        val commands = SExprReader("(declare-const x Int) (declare-const quotient Int) " +
            "(assert (= x 1)) (assert (= (+ (* 16 quotient) x) 241))").readAll()
        val source = lowerSmtReduction(original.copy(commands = commands), 0, 2, ProblemSettings(),
            Cancellation.Never, resolveSmtLibSemantics(0, 2))

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment

        assertEquals("15", assignment.intValue(source.encodingIntVarNames.getValue("quotient")))
        assertEquals(setOf("x"), source.intVarNames.keys)
    }

    @Test
    fun `source integer endpoint overrides should remain premises during inference`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (= x 3))", unboundedIntLo = 0, unboundedIntHi = 2)

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `scalar multiplication and trivial powers should not require product bound closure`() {
        for (term in listOf("(* 3 x)", "(* x 3)", "(* (- 3) x)", "(* (+ 1 2) x)",
            "(* 3 (* 2 x))", "(^ x 0)", "(^ x 1)")) {
            val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, strictBounds = false)
            SExprReader("(declare-const x Int) (assert (= $term 6))").readAll().forEach(builder::command)

            assertFalse(builder.hasOpenProductOperand(), term)
        }
    }

    @Test
    fun `nonconstant products and powers should require open operand bounds`() {
        for (term in listOf("(* x x)", "(* (+ x 1) y)", "(* (to_real x) r)", "(^ x 2)")) {
            val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, strictBounds = false)
            SExprReader("(declare-const x Int) (declare-const y Int) (declare-const r Real) " +
                "(assert (= $term 6))").readAll().forEach(builder::command)

            assertTrue(builder.hasOpenProductOperand(), term)
        }
    }
}
