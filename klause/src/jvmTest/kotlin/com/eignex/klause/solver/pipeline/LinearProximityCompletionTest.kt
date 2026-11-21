package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearProximityCompletionTest : RuntimeInitializedTest() {
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val source = SourceProofContext(SourceScope(SourceRevision("rounded-private", "exact"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(integer, real), mapOf(
        "sum-upper" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE,
            real to BigFraction.ONE), BigFraction.ONE)),
        "sum-lower" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE,
            real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
    ), mapOf(real to BigFraction.ONE))
    private val center = Sample(booleanArrayOf(), longArrayOf(0), doubleArrayOf(1.0))
    private val half = BigFraction.ofLong(2).reciprocal()
    private val result = LinearAuthorityResult(relaxedPoint = mapOf(integer to 0.5, real to 0.5),
        fractional = listOf(integer to half), split = integer to BigFraction.ZERO)

    @Test
    fun `rounded ties should complete continuous recourse away from the center`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session()
        var decisions = 0
        LinearProximityCompletion(source, center, SourceLpBudget(funding = funding), {
            decisions++
            true
        },
            { _, _ -> }).use { completion ->

            assertFalse(completion.advance(result, session, ExecutionControl.Never))

            val point = assertNotNull(completion.takeChecked())
            assertEquals(1L, point.intValue(0))
            assertEquals(listOf(BigFraction.ZERO), point.exactReals)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(point), SourceDerivation.SourceWitness)))
            assertFalse(completion.advance(result, session, ExecutionControl.Never))
            assertNull(completion.takeChecked())
            assertEquals(1, decisions)
        }
    }

    @Test
    fun `rounded completion should retain receiving integer pins`() {
        val pinned = source.atScope(source.scope.copy(condition = SourceCondition(
            lower = mapOf(integer to BigFraction.ZERO), upper = mapOf(integer to BigFraction.ZERO))))
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session()
        LinearProximityCompletion(pinned, center, SourceLpBudget(funding = funding), { true },
            { _, _ -> }).use { completion ->

            assertFalse(completion.advance(result, session, ExecutionControl.Never))

            val point = assertNotNull(completion.takeChecked())
            assertEquals(0L, point.intValue(0))
            assertEquals(listOf(BigFraction.ONE), point.exactReals)
        }
    }

    @Test
    fun `refused trial decisions should withhold completion without retrying`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val budget = SourceLpBudget(funding = funding)
        val session = SearchComponentSet(emptyList()).session()
        var decisions = 0
        LinearProximityCompletion(source, center, budget, {
            decisions++
            false
        }, { _, _ -> }).use { completion ->

            assertFalse(completion.advance(result, session, ExecutionControl.Never))
            assertFalse(completion.advance(result, session, ExecutionControl.Never))

            assertNull(completion.takeChecked())
            assertEquals(1, decisions)
            assertEquals(0, budget.operations)
        }
    }

    @Test
    fun `exhausted checks should withhold the rounded trial`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val budget = SourceLpBudget(funding = funding)
        val session = SearchComponentSet(emptyList()).session(0)
        var decisions = 0
        LinearProximityCompletion(source, center, budget, {
            decisions++
            true
        }, { _, _ -> }).use { completion ->

            assertFalse(completion.advance(result, session, ExecutionControl.Never))

            assertNull(completion.takeChecked())
            assertEquals(0, decisions)
            assertEquals(0, budget.operations)
        }
    }

    @Test
    fun `paused recovery should retain its trial claim`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val budget = SourceLpBudget(funding = funding)
        val session = SearchComponentSet(emptyList()).session(1)
        var decisions = 0
        var paused = false
        LinearProximityCompletion(source, center, budget, {
            decisions++
            true
        }, { stage, _ ->
            if (stage == "PrimalRecoveryWork" && !paused) {
                paused = true
                funding.grant(0)
            }
        }).use { completion ->

            assertTrue(completion.advance(result, session, ExecutionControl.Never))
            assertTrue(paused)
            funding.grant(1_000_000)
            assertFalse(completion.advance(result, session, ExecutionControl.Never))

            assertNotNull(completion.takeChecked())
            assertEquals(1, decisions)
        }
    }

    @Test
    fun `cancelled preparation should not claim a trial`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session()
        var decisions = 0
        LinearProximityCompletion(source, center, SourceLpBudget(funding = funding), {
            decisions++
            true
        },
            { _, _ -> }).use { completion ->

            assertTrue(completion.advance(result, session, ExecutionControl { true }))

            assertNull(completion.takeChecked())
            assertEquals(0, decisions)
        }
    }

    @Test
    fun `infeasible rounded coordinates should withhold a witness without retrying`() {
        val restricted = SourceProofContext(source.scope, source.coordinates, source.premises +
            ("integer-upper" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE),
                BigFraction.ZERO))), source.objective)
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val budget = SourceLpBudget(maxOperations = 1, funding = funding)
        val session = SearchComponentSet(emptyList()).session(1)
        var decisions = 0
        LinearProximityCompletion(restricted, center, budget, {
            decisions++
            true
        }, { _, _ -> }).use { completion ->

            assertFalse(completion.advance(result, session, ExecutionControl.Never))
            assertFalse(completion.advance(result, session, ExecutionControl.Never))

            assertNull(completion.takeChecked())
            assertEquals(1, decisions)
            assertTrue(budget.operations <= 1)
        }
    }

    @Test
    fun `closing paused recovery should discard its pending point`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session(1)
        var decisions = 0
        val completion = LinearProximityCompletion(source, center, SourceLpBudget(funding = funding),
            {
                decisions++
                true
            }, { stage, _ -> if (stage == "PrimalRecoveryWork") funding.grant(0) })
        assertTrue(completion.advance(result, session, ExecutionControl.Never))

        completion.close()
        funding.grant(1_000_000)

        assertFalse(completion.advance(result, session, ExecutionControl.Never))
        assertNull(completion.takeChecked())
        assertEquals(1, decisions)
    }

    @Test
    fun `an offer ending before preparation should resume without losing the trial`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session(1)
        var paused = true
        var decisions = 0
        val stop = ExecutionControl { paused }
        LinearProximityCompletion(source, center, SourceLpBudget(funding = funding), {
            decisions++
            true
        },
            { _, _ -> }).use { completion ->
            assertTrue(completion.advance(result, session, stop))
            assertEquals(0, decisions)

            paused = false
            assertFalse(completion.advance(result, session, stop))

            assertNotNull(completion.takeChecked())
            assertEquals(1, decisions)
        }
    }

    @Test
    fun `an offer ending during funded preparation should retain its point cursor`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session(1)
        var interrupted = false
        var paused = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            if (!interrupted && it.preparationWork > 0) {
                interrupted = true
                paused = true
            }
        })
        var decisions = 0
        val stop = ExecutionControl { paused }
        LinearProximityCompletion(source, center, budget, {
            decisions++
            true
        }, { _, _ -> }).use { completion ->
            assertTrue(completion.advance(result, session, stop))
            assertTrue(interrupted)
            assertEquals(0, decisions)

            paused = false
            assertFalse(completion.advance(result, session, stop))

            assertNotNull(completion.takeChecked())
            assertEquals(1, decisions)
        }
    }

    @Test
    fun `interrupted completion setup should decline without repeating its reservation`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session(1)
        var paused = false
        var reservations = 0
        val budget = SourceLpBudget(funding = funding, onWork = {
            if (it.preparationWork >= 16 && ++reservations == 2) paused = true
        })
        var decisions = 0
        val stop = ExecutionControl { paused }
        LinearProximityCompletion(source, center, budget, {
            decisions++
            true
        }, { _, _ -> }).use { completion ->
            assertFalse(completion.advance(result, session, stop))
            assertTrue(paused)
            val work = funding.spent
            val allocation = funding.allocated

            paused = false
            assertFalse(completion.advance(result, session, stop))

            assertNull(completion.takeChecked())
            assertEquals(work, funding.spent)
            assertEquals(allocation, funding.allocated)
            assertEquals(1, decisions)
        }
    }

    @Test
    fun `interrupted seed preflight should decline without repeating its work`() {
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val session = SearchComponentSet(emptyList()).session(1)
        var paused = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            if (it.preparationWork == 1L) paused = true
        })
        var decisions = 0
        val stop = ExecutionControl { paused }
        LinearProximityCompletion(source, center, budget, {
            decisions++
            true
        }, { _, _ -> }).use { completion ->
            assertFalse(completion.advance(result, session, stop))
            assertTrue(paused)
            val work = funding.spent
            val allocation = funding.allocated

            paused = false
            assertFalse(completion.advance(result, session, stop))

            assertNull(completion.takeChecked())
            assertEquals(work, funding.spent)
            assertEquals(allocation, funding.allocated)
            assertEquals(1, decisions)
        }
    }
}
