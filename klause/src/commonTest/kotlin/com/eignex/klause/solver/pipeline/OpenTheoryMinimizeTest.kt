package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.lowering.mps.MpsConstraint
import com.eignex.klause.lowering.mps.MpsModel
import com.eignex.klause.lowering.mps.MpsObjective
import com.eignex.klause.lowering.mps.MpsVar
import com.eignex.klause.lowering.mps.toProblem
import com.eignex.klause.lowering.smtlib.SmtLib
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.TheoryParams
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

class OpenTheoryMinimizeTest {

    private fun modelOf(body: String) = SmtLib.parse(
        """
            (set-logic QF_LIA)
            $body
            (check-sat)
        """.trimIndent(),
    )

    @Test
    fun `minimizing an open column descends to the bound its rows imply`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (assert (>= x 3))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = OpenTheoryMinimizer(parsed.model, objective).minimize()

        assertEquals("3", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `minimizing a negated column descends to the far side of its range`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (assert (<= x 5))
                (assert (>= x (- 4)))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = -1L })

        val result = OpenTheoryMinimizer(parsed.model, objective).minimize()

        assertEquals("-5", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `a model with no feasible assignment is infeasible rather than unbounded`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (assert (>= x 3))
                (assert (<= x 1))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        assertIs<OpenTheoryOptimum.Infeasible>(OpenTheoryMinimizer(parsed.model, objective).minimize())
    }

    @Test
    fun `a spent budget reports the incumbent as a bound rather than an optimum`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (assert (>= x 3))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = OpenTheoryMinimizer(parsed.model, objective)
            .minimize(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryOptimum.Bounded>(result)
    }

    @Test
    fun `an open MPS model with an objective reaches its optimum instead of being refused`() {
        // The column is open above, so the model takes a theory route rather than finite CP; before an
        // objective existed on that route the front-end refused the model outright.
        val compiled = MpsModel(
            "m",
            ObjectiveDirection.MINIMIZE,
            MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            listOf(MpsVar("x", integer = true, lower = null, upper = null)),
            listOf(MpsConstraint("c", intArrayOf(0), doubleArrayOf(1.0), lower = 7.0, upper = null)),
        ).toProblem()

        val result = OpenTheoryMinimizer(compiled.model, compiled.objective!!).minimize()

        assertEquals("7", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }
}
