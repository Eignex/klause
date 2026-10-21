package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmtLibBoundsTest {
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
