package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearIncumbentExchangeTest : RuntimeInitializedTest() {
    @Test
    fun `a paused exchange should retain its proposal without claiming another attempt`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val budget = SourceLpBudget(funding = SourceLpFunding(100_000_000, 100_000_000)
            .also { it.grant(100_000_000) })
        var claims = 0
        var paused = false
        assertNotNull(LinearIncumbentExchange.prepare(source, center, budget, ExecutionControl.Never,
            claim = { claims++; true }, observe = { stage, _ -> if (stage == "Attempts") paused = true }))
            .use { exchange ->
                assertNull(exchange.advance(ExecutionControl { paused }))
                assertEquals(1, claims)

                val point = assertNotNull(exchange.advance(ExecutionControl.Never))

                assertEquals(0L, point.intValue(0))
                assertEquals(1, claims)
            }
    }

    @Test
    fun `exchanges should optimize the continuous residual while retaining receiving pins`() {
        for ((pinned, wide) in listOf(false to false, true to false, false to true, true to true)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
                factors = arrayOf(Linear(intArrayOf(0, 1), doubleArrayOf(2.0, 1.0),
                    intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.0)), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
            val objective = LinearObjective(intCoefficients = longArrayOf(3, 1), realCoefficients = doubleArrayOf(1.0))
            val original = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
                ExecutionControl.Never))
            val source = if (pinned) original.atScope(original.scope.copy(condition =
                original.scope.condition.bound(SourceCoordinate(SourceCoordinate.Kind.Integer, 0),
                    BigFraction.ONE, upper = false))) else original
            val center = Sample(booleanArrayOf(), if (wide) longArrayOf() else longArrayOf(1, 1), doubleArrayOf(0.0),
                exactReals = listOf(BigFraction.ZERO), exactInts = if (wide) listOf(BIG_ONE, BIG_ONE) else null)
            val funding = SourceLpFunding(100_000_000, 100_000_000).also { it.grant(100_000_000) }
            val budget = SourceLpBudget(funding = funding)
            val query = assertNotNull(IncumbentProximity.prepare(source, center, BigFraction.ofLong(4),
                budget, ExecutionControl.Never))
            var claims = 0

            val point = assertNotNull(LinearIncumbentExchange.prepare(query, center, budget,
                ExecutionControl.Never, claim = { claims++; true }, observe = { _, _ -> }).use { exchange ->
                assertNotNull(exchange).advance(ExecutionControl.Never)
            })

            assertEquals(1, claims)
            assertContentEquals(if (pinned) longArrayOf(1, 0) else longArrayOf(0, 1), point.ints)
            assertEquals(listOf(if (pinned) BigFraction.ZERO else BigFraction.ONE), point.exactReals)
            assertEquals(BigFraction.ofLong(if (pinned) 3 else 2), objective.evaluateExact(point))
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(point), SourceDerivation.SourceWitness)))
        }
    }

    @Test
    fun `private exchanges should share the containing decision and check limits`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = arrayOf(Linear(intArrayOf(0, 1), doubleArrayOf(2.0, 1.0),
                intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(3, 1), realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1, 1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        val result = LinearAuthorityInvocation(source,
            TheoryParams(sourceIncumbentCompletion = true, maxLeaves = 1, maxDecisions = 1),
            external = { center }, boundOrdered = true, incumbentProximity = true,
            incumbentCompletionCheck = { _, _, _ -> true }).use { it.run { _, _ -> } }

        assertEquals(BigFraction.ofLong(2), result.value)
        assertEquals(1L, result.stats.openTheory.openTheoryDecisions)
        assertEquals(1L, result.stats.openTheory.openTheoryChecks)
        assertNull(result.terminal)
    }

    @Test
    fun `original rejection should withhold private exchange improvements`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        val result = LinearAuthorityInvocation(source, TheoryParams(sourceIncumbentCompletion = true),
            external = { center }, boundOrdered = true, incumbentProximity = true,
            incumbentCompletionCheck = { point, _, _ -> point.intValue(0) == 1L }).use { it.run { _, _ -> } }

        assertTrue((result.stats.smt.completeSearchStages?.get("numericalProximityRejected") ?: 0L) > 0)
        assertEquals(0L, result.stats.smt.completeSearchStages?.get("numericalProximityImprovements") ?: 0L)
    }
}
