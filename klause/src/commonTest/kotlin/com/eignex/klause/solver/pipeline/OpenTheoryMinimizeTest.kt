package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheoryMinimizeTest {

    @Test
    fun `replacing an open objective should start from the unbounded source root`() {
        val parsed =
            modelOf(
                """
            (declare-const x Int)
            (assert (>= x 2))
            (assert (<= x 5))
                """.trimIndent(),
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
    fun `the optimization envelope should report the whole descent time`() {
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
    fun `minimizing an open column should descend to the bound its rows imply`() {
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
        assertTrue(optimum.stats.lp.standalonePasses.sum > 0.0)
    }

    @Test
    fun `a sliced descent should reach the optimum an unsliced one proves`() {
        val parsed = stepped()
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = -1L })
        val minimizer = OpenTheoryMinimizer(parsed.model, objective)
        val unsliced = assertIs<OpenTheoryOptimum.Optimal>(minimizer.minimize())

        val sliced = minimizer.descent(TheoryParams()).use { descent ->
            var verdict: OpenTheoryOptimum? = null
            while (verdict == null) verdict = descent.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) { _, _ -> }
            verdict
        }

        assertEquals(unsliced.value, assertIs<OpenTheoryOptimum.Optimal>(sliced).value)
    }

    @Test
    fun `a round refuting another arm bound should leave the optimum to that arm`() {
        val parsed = stepped()
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })
        val minimizer = OpenTheoryMinimizer(parsed.model, objective)

        val result = minimizer.descent(TheoryParams()) { BIG_ZERO }.use { descent ->
            descent.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceWork = -1L) { _, _ -> }
        }

        val bounded = assertIs<OpenTheoryOptimum.Bounded>(result)
        assertEquals(TerminationReason.SearchExhausted, bounded.reason)
        assertNull(bounded.incumbent)
    }

    @Test
    fun `a repeated opening bound should not be closed twice`() {
        val parsed = modelOf("(declare-const x Int)")
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = OpenTheoryMinimizer(parsed.model, objective).minimize()

        assertIs<OpenTheoryOptimum.Unbounded>(result)
        assertEquals(2.0, result.stats.lp.standalonePasses.sum)
    }

    @Test
    fun `a model with no feasible assignment should be infeasible rather than unbounded`() {
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
    fun `a budget spent before the first witness should bound the optimum by nothing`() {
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
        assertNull(result.incumbent)
        assertNull(result.value)
    }

    @Test
    fun `a proved optimum should be attained by the assignment reported with it`() {
        val parsed = stepped()
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = assertIs<OpenTheoryOptimum.Optimal>(OpenTheoryMinimizer(parsed.model, objective).minimize())

        assertEquals("0", result.value.toString())
        assertEquals("0", result.assignment.intValue(x))
    }

    @Test
    fun `a budget spent mid descent should bound the optimum by the standing incumbent`() {
        val values = listOf(2L, 4L).map { decisions ->
            val parsed = stepped()
            val x = parsed.intVarNames.getValue("x")
            val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

            val minimizer = OpenTheoryMinimizer(
                parsed.model,
                objective,
                PresolveConfig.NONE,
                solutionSetSensitive = false,
                presolveCancellation = Cancellation.Never,
                presolveBudget = null,
            )
            val result = assertIs<OpenTheoryOptimum.Bounded>(
                minimizer.minimize(TheoryParams(maxDecisions = decisions, openHintFlips = 0L)),
            )

            assertEquals(com.eignex.klause.solver.result.TerminationReason.BudgetExhausted, result.reason)
            val incumbent = assertNotNull(result.incumbent)
            val value = assertNotNull(result.value)
            val yValue = incumbent.intValue(parsed.intVarNames.getValue("y")).toInt()
            val zValue = incumbent.intValue(parsed.intVarNames.getValue("z")).toInt()
            assertEquals((yValue + zValue).toString(), incumbent.intValue(x))
            assertEquals(incumbent.intValue(x), value.toString())
            value
        }
        assertTrue(values[1] < values[0])
    }

    @Test
    fun `default integer point guidance should attain a source optimum within two decisions`() {
        val parsed = stepped()
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })
        val minimizer = OpenTheoryMinimizer(
            parsed.model,
            objective,
            PresolveConfig.NONE,
            solutionSetSensitive = false,
            presolveCancellation = Cancellation.Never,
            presolveBudget = null,
        )

        val result = assertIs<OpenTheoryOptimum.Optimal>(minimizer.minimize(TheoryParams(maxDecisions = 2)))

        assertEquals("0", result.value.toString())
        for (variable in listOf("x", "y", "z")) {
            assertEquals("0", result.assignment.intValue(parsed.intVarNames.getValue(variable)))
        }
        assertTrue(result.stats.openHints.usage.componentSelections > 0L)
    }

    @Test
    fun `an objective no row bounds below should be unbounded`() {
        val parsed = modelOf("(declare-const x Int)")
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = assertIs<OpenTheoryOptimum.Unbounded>(OpenTheoryMinimizer(parsed.model, objective).minimize())

        assertEquals(result.value.toString(), result.witness.intValue(x))
        assertTrue(result.stats.smt.sourceLp.operations > 0)
        assertTrue(result.stats.smt.sourceLp.floatWork > 0)
    }

    @Test
    fun `an objective unbounded through the column it negates should be unbounded`() {
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
    fun `an objective unbounded inside one disjunct should be unbounded`() {
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
    fun `an optimum above the 64 bit range should be reached rather than refuted`() {
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

    @Test
    fun `an objective weighting no column should be optimal at its constant`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int)
                (assert (>= x 3))
                (check-sat)
            """.trimIndent(),
        )
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars), constant = 4L)

        val result = OpenTheoryMinimizer(parsed.model, objective).minimize()

        assertEquals("4", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `a route with no integer column should be optimal at a constant objective`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LRA)
                (declare-const y Real)
                (assert (>= y 1.0))
                (check-sat)
            """.trimIndent(),
        )

        val result = assertIs<OpenTheoryOptimum.Optimal>(
            OpenTheoryMinimizer(parsed.model, LinearObjective(constant = 4L)).minimize(),
        )

        assertEquals("4", result.value.toString())
        assertTrue(TheoryCompletion.parseRational(result.assignment.realValue(0)) >= BigFraction.ONE)
    }

    @Test
    fun `an infeasible model with a constant objective should be infeasible`() {
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
