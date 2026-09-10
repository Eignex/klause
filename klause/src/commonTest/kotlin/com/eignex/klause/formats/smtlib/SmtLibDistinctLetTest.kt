package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs

class SmtLibDistinctLetTest {

    @Test
    fun `a let-bound distinct rejects equal values`() {
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
