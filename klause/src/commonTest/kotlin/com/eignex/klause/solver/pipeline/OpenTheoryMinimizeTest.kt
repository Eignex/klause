package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.mps.MpsConstraint
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.formats.mps.MpsObjective
import com.eignex.klause.formats.mps.MpsVar
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

class OpenTheoryMinimizeTest {

    private fun modelOf(body: String) = SmtLib.parse(
        """
            (set-logic QF_LIA)
            $body
            (check-sat)
        """.trimIndent(),
    )

    /** `x = y + z` over `y ∈ {0, 20}` and `z ∈ {0, 7}`, so minimizing `x` descends 27 → 20 → 7 → 0. */
    private fun stepped() = modelOf(
        """
            (declare-const x Int)
            (declare-const y Int)
            (declare-const z Int)
            (assert (or (= y 0) (= y 20)))
            (assert (or (= z 0) (= z 7)))
            (assert (= x (+ y z)))
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
    fun `a budget spent before the first witness bounds the optimum by nothing`() {
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
        assertNull(result.incumbent, "nothing was proved feasible")
        assertNull(result.value)
    }

    @Test
    fun `a proved optimum is attained by the assignment reported with it`() {
        val parsed = stepped()
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = assertIs<OpenTheoryOptimum.Optimal>(OpenTheoryMinimizer(parsed.model, objective).minimize())

        assertEquals("0", result.value.toString())
        assertEquals("0", result.assignment.intValue(x))
    }

    @Test
    fun `a budget spent mid-descent bounds the optimum by the standing incumbent`() {
        // 27 is the first witness and 20 the improvement installed over it, so a bound of 20 is one the
        // descent reached only by replacing what it already had.
        for ((decisions, bound) in listOf(4L to "27", 20L to "20")) {
            val parsed = stepped()
            val x = parsed.intVarNames.getValue("x")
            val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

            val result = assertIs<OpenTheoryOptimum.Bounded>(
                OpenTheoryMinimizer(parsed.model, objective).minimize(TheoryParams(maxDecisions = decisions)),
            )

            assertEquals(bound, result.value.toString(), "bound after $decisions decisions")
            assertEquals(bound, result.incumbent?.intValue(x), "incumbent after $decisions decisions")
        }
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

        val result = OpenTheoryMinimizer(compiled.model, compiled.objective!!.toLinearObjective()).minimize()

        assertEquals("7", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `an objective no row bounds below is unbounded rather than descended`() {
        val parsed = modelOf("(declare-const x Int)")
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = assertIs<OpenTheoryOptimum.Unbounded>(OpenTheoryMinimizer(parsed.model, objective).minimize())

        assertEquals(result.value.toString(), result.witness.intValue(x))
    }

    @Test
    fun `an objective unbounded through the column it negates is unbounded`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (assert (>= x 0))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = -1L })

        assertIs<OpenTheoryOptimum.Unbounded>(OpenTheoryMinimizer(parsed.model, objective).minimize())
    }

    @Test
    fun `an objective unbounded inside one disjunct is unbounded`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (declare-const y Int)
                (assert (or (<= x 0) (>= x 10)))
                (assert (= y 4))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        assertIs<OpenTheoryOptimum.Unbounded>(OpenTheoryMinimizer(parsed.model, objective).minimize())
    }

    @Test
    fun `an objective bounded only by its disjuncts reaches its optimum`() {
        val parsed = stepped()
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = assertIs<OpenTheoryOptimum.Optimal>(OpenTheoryMinimizer(parsed.model, objective).minimize())

        assertEquals("0", result.value.toString())
    }

    @Test
    fun `an optimum above the 64-bit range is reached rather than refuted`() {
        val parsed = modelOf(
            """
                (declare-const x Int)
                (assert (>= x 170141183460469231731687303715884105728))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = assertIs<OpenTheoryOptimum.Optimal>(OpenTheoryMinimizer(parsed.model, objective).minimize())

        assertEquals("170141183460469231731687303715884105728", result.value.toString())
        assertEquals("170141183460469231731687303715884105728", result.assignment.intValue(x))
    }
}
