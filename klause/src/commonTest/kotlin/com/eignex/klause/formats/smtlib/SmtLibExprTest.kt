package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmtLibExprTest {
    private fun parse(text: String): BakedProblem = SmtLib.parse("$text\n(check-sat)").model.bake()

    @Test
    fun `an asserted or should post a single clause over its disjuncts`() {
        val p = parse("(declare-const p Bool) (declare-const q Bool) (declare-const r Bool) (assert (or p q r))")

        assertEquals(1, p.factors.size)
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size)
    }

    @Test
    fun `a chained implication should negate every antecedent but the last operand`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (assert (=> a b c))")

        assertEquals(1, p.factors.size)
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size)
        assertEquals(3, p.numBoolVars)
    }

    @Test
    fun `directly asserted Boolean bindings should retain outer local values`() {
        val source = parse(
            "(declare-const p Bool) (assert (not p)) " +
                "(assert (let ((p true)) (let ((bound p)) (let ((p false)) bound))))",
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `reified Boolean bindings should retain their enclosing values`() {
        val source = parse(
            "(declare-const p Bool) (assert (not p)) " +
                "(assert (let ((bound p)) (let ((p true)) (not bound))))",
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `simultaneous Boolean binding values should exclude their peer bindings`() {
        val source = parse(
            "(declare-const p Bool) (assert p) (assert (let ((p false) (bound p)) bound))",
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `shared Boolean bindings should retain their value across caller scopes`() {
        val source = parse(
            """
                (declare-const p Bool)
                (assert (not p))
                (assert (let ((bound p))
                    (and (let ((p true)) (not bound)) (let ((p false)) (not bound)))))
            """.trimIndent(),
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }
}
