package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.mps.MpsConstraint
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.formats.mps.MpsObjective
import com.eignex.klause.formats.mps.MpsVar
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

class OpenTheoryMinimizeTest {

    @Test
    fun `replacing an open objective starts from the original unbounded source root`() {
        val parsed =
            modelOf(
                """
            (declare-const x Int)
            (assert (>= x 2))
            (assert (<= x 5))
        """.trimIndent()
            )
        val x = parsed.intVarNames.getValue("x")
        val positive = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })
        val negative = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = -1L })
        val first = OpenTheoryMinimizer(parsed.model, positive)
        assertEquals("2", assertIs<OpenTheoryOptimum.Optimal>(first.minimize()).value.toString())

        val second = first.replacingObjective(negative)
        assertEquals("-5", assertIs<OpenTheoryOptimum.Optimal>(second.minimize()).value.toString())
        assertEquals(
            "2",
            assertIs<OpenTheoryOptimum.Optimal>(second.replacingObjective(positive).minimize()).value.toString(),
        )
    }

    @Test
    fun `optimization envelope reports the whole descent time`() {
        val round = SolveStats(run = RunStats(backend = "exact-lia", wallMs = 25, timedOut = true))
        val envelope = SolveStats(run = RunStats(backend = "exact-lia", wallMs = 1_000))

        assertEquals(
            RunStats(backend = "exact-lia", wallMs = 1_000, timedOut = true),
            round.withOptimizationEnvelope(envelope).run,
        )
    }

    private fun modelOf(body: String) = SmtLib.parse(
        """
            (set-logic QF_LIA)
            $body
            (check-sat)
        """.trimIndent(),
    )

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

        val optimum = assertIs<OpenTheoryOptimum.Optimal>(result)
        assertEquals("3", optimum.value.toString())
        assertTrue(optimum.stats.lp.standalonePasses.sum > 0.0, "bound-closing LP work must reach the result")
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
        val values = listOf(4L, 10L).map { decisions ->
            val parsed = stepped()
            val x = parsed.intVarNames.getValue("x")
            val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

            val result = assertIs<OpenTheoryOptimum.Bounded>(
                OpenTheoryMinimizer(parsed.model, objective).minimize(TheoryParams(maxDecisions = decisions)),
            )

            assertEquals(com.eignex.klause.solver.result.TerminationReason.BudgetExhausted, result.reason)
            val incumbent = assertNotNull(result.incumbent)
            val value = assertNotNull(result.value)
            val yValue = incumbent.intValue(parsed.intVarNames.getValue("y")).toInt()
            val zValue = incumbent.intValue(parsed.intVarNames.getValue("z")).toInt()
            assertTrue(yValue in setOf(0, 20))
            assertTrue(zValue in setOf(0, 7))
            assertEquals((yValue + zValue).toString(), incumbent.intValue(x))
            assertEquals(incumbent.intValue(x), value.toString())
            assertTrue(value.toString() in setOf("0", "7", "20", "27"))
            value
        }
        assertTrue(values[1] < values[0])
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
        assertTrue(result.stats.smt.simplexAttempts > 0)
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
