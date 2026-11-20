package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class JointLinearRepairIncumbentTest(private val completion: Boolean) : RuntimeInitializedTest() {
    @Test
    fun `checked centers should improve within the existing LP operation allowance`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = Array(3) { id ->
                Linear(intArrayOf(1, 1), intArrayOf(id, (id + 1) % 3), LinearOp.LE, 1)
            }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(0, 0, 0), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = completion, maxFlips = 1, sourceLpOperations = 2),
            external = { seed }, incumbentProximity = true,
            incumbentCompletionCheck = { _, _, _ -> true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val result = assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(BigFraction.MINUS_ONE, objective.evaluateExact(result.sample))
                assertTrue(repair.isProximityDiscovery(result.sample))
                assertEquals(if (completion) 0L else 1L,
                    repair.stats.smt.completeSearchStages?.get("jointRepairRootQueries") ?: 0L)
            }
        }
    }

    @Test
    fun `checked centers should improve through a complete assignment exchange`() {
        val pairs = listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3)
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4) { 1 }, null, null),
            factors = pairs.map { (a, b) -> Linear(intArrayOf(1, 1), intArrayOf(a, b), LinearOp.EQ, 1) }
                .toTypedArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(1, 0, 0, 1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = completion, maxFlips = 3, sourceLpOperations = 4),
            external = { seed }, incumbentProximity = true,
            incumbentCompletionCheck = { _, _, _ -> true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val result = assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(BigFraction.ZERO, objective.evaluateExact(result.sample))
                assertTrue(repair.isProximityDiscovery(result.sample))
            }
        }
    }

    @Test
    fun `a declined support component should leave later private roots available`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(34), LongArray(34) { 1 }, null, null),
            factors = arrayOf(Linear(IntArray(33) { 1 }, IntArray(33) { it }, LinearOp.EQ, 0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = LongArray(34) { if (it == 33) -1 else 0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), LongArray(34), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = completion, maxFlips = 7),
            sourceIntegers = intArrayOf(0, 1, 33) + IntArray(31) { it + 2 },
            external = { seed }, incumbentProximity = true,
            incumbentCompletionCheck = { _, _, _ -> true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val result = assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(BigFraction.MINUS_ONE, objective.evaluateExact(result.sample))
                assertTrue(repair.isProximityDiscovery(result.sample))
                if (!completion) assertEquals(2L, repair.stats.smt.completeSearchStages
                    ?.get("jointRepairProximityNeighborhoodWidthDeclines"))
            }
        }
    }

    @Test
    fun `original source rejection should withhold a private center`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = Array(3) { id ->
                Linear(intArrayOf(1, 1), intArrayOf(id, (id + 1) % 3), LinearOp.LE, 1)
            }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(0, 0, 0), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = completion, maxFlips = 1, sourceLpOperations = 2),
            external = { seed }, incumbentProximity = true,
            incumbentCompletionCheck = { _, value, _ -> value < BigFraction.ZERO }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                assertIs<MinimizeResult.Unknown>(search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(0L, repair.stats.smt.completeSearchStages?.get("jointRepairProximityCenters") ?: 0L)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "completion={0}")
        fun cases() = listOf(false, true).map { arrayOf(it) }
    }
}
