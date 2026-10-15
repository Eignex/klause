package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmtLibRealSelectionTest {
    @Test
    fun `real conditionals retain exact rational affine branches`() {
        for (branch in listOf(true, false)) {
            val parsed = SmtLib.parse("""
                (declare-const b Bool) (declare-const x Real) (declare-const y Real)
                (assert ${if (branch) "b" else "(not b)"}) (assert (= x (/ 2 3)))
                (assert (= y (ite b (+ x (/ 1 3)) (- x (/ 1 3)))))
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

            assertEquals(if (branch) "1" else "1/3",
                assertIs<OpenTheoryResult.Sat>(result, result.toString()).assignment
                    .realValue(parsed.realVarNames.getValue("y")))
            assertEquals(1, parsed.model.factors.filterIsInstance<RealElement>().size)
        }
    }

    @Test
    fun `shared real conditional guards cannot choose incompatible branches`() {
        val parsed = SmtLib.parse("""
            (declare-const b Bool) (declare-const x Real) (declare-const y Real)
            (assert (= x (ite b 0.0 1.0))) (assert (= y (ite b 1.0 0.0)))
            (assert (= (+ x y) 0.0))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(randomSeed = 0, maxDecisions = 100))

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
