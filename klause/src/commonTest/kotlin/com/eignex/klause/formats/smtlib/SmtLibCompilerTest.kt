package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.toSample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibCompilerTest {
    @Test
    fun `private binary selection indexes should preserve declared source values in mixed sums`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Int)
            (assert (<= 0 x 5)) (assert b)
            (assert (= (+ x (* 3 (ite b 1 0))) 5))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("2", assignment.intValue(parsed.intVarNames.getValue("x")))
        assertEquals(true, assignment.boolValue(parsed.boolVarNames.getValue("b")))
        assertTrue(parsed.model.factors.filterIsInstance<ExactLinear>().any { row ->
            (0 until row.size).any { Term.isBool(row.ref(it)) } && row.variables.ints.isNotEmpty()
        })
    }

    @Test
    fun `binary arithmetic substitution should retain the compiled objective binding`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Int)
            (assert (= x 2)) (assert (not b))
            (minimize (+ x (ite b 1 0)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(BigFraction.ofLong(2),
            parsed.objective!!.toLinearObjective().evaluateExact(assignment.toSample(parsed.model)))
    }

    @Test
    fun `declared binary integers should retain their source coordinates`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Int)
            (assert (<= 0 x 1)) (assert (= b (= x 1))) (assert b)
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(1, parsed.model.numIntVars)
        assertEquals("1", assignment.intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `an asserted equality should construct its normalized linear row`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (= (+ x 2) 5))")

        assertEquals(mapOf("x" to 0), parsed.intVarNames)
        val row = parsed.model.factors.single() as Linear
        assertContentEquals(intArrayOf(0), row.vars)
        assertContentEquals(longArrayOf(1), row.integerConstants!!.coeffs)
        assertEquals(LinearOp.EQ, row.op)
        assertEquals(3L, row.integerConstants!!.bound)
    }

    @Test
    fun `a duplicate declaration should be rejected`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (declare-const x Bool)")
        }

        assertTrue(error.message!!.contains("duplicate declaration of 'x'"))
    }

    @Test
    fun `a malformed command should report its expected argument count`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int unexpected)")
        }

        assertTrue(error.message!!.contains("declare-const expects 2 arguments"))
    }

    @Test
    fun `a malformed ite should report a format error`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (assert (= x (ite true 1)))")
        }

        assertTrue(error.message!!.contains("ite"))
    }

    @Test
    fun `a macro should reject unsupported parameter sorts`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(define-fun f ((x BitVec)) Int x)")
        }

        assertTrue(error.message!!.contains("unsupported sort 'BitVec'"))
    }

    @Test
    fun `an unknown command should be rejected`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (asert (= x 1))")
        }

        assertTrue(error.message!!.contains("unsupported command 'asert'"))
    }

    @Test
    fun `a let-bound distinct should reject equal values`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-fun x () Int)
                (declare-fun y () Int)
                (assert (= x 0))
                (assert (= y 0))
                (assert (let ((distinct-values (distinct x y))) distinct-values))
                (check-sat)
            """.trimIndent(),
            unboundedIntLo = -1,
            unboundedIntHi = 1,
        )

        assertIs<SolveResult.Unsat>(BacktrackSolver(parsed.model.bake()).solve(BacktrackParams()))
    }
}
