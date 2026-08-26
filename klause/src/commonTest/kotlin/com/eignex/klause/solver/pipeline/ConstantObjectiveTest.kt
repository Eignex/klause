package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConstantObjectiveTest {

    private fun parsed() = SmtLib.parse(
        """
            (set-logic QF_LIA)
            (declare-const x Int)
            (assert (>= x 3))
            (check-sat)
        """.trimIndent(),
    )

    @Test
    fun `an objective weighting no column is optimal at its constant`() {
        // A row bounding a constant objective would have no terms at all, which is not a constraint.
        val parsed = parsed()
        val objective = LinearObjective(
            intCoefficients = LongArray(parsed.model.numIntVars),
            constant = 4L,
        )

        val result = OpenTheoryMinimizer(parsed.model, objective).minimize()

        assertEquals("4", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `an infeasible model with a constant objective is infeasible rather than optimal`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int)
                (assert (>= x 3))
                (assert (<= x 1))
                (check-sat)
            """.trimIndent(),
        )
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars), constant = 4L)

        assertIs<OpenTheoryOptimum.Infeasible>(OpenTheoryMinimizer(parsed.model, objective).minimize())
    }
}
