package com.eignex.klause.lp.optimization

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CutFamily
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
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearAuthorityBoundBaselineTest : RuntimeInitializedTest() {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("baseline", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(x), mapOf(
        "weak" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
        "strong" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ofLong(-2))),
    ), mapOf(x to BigFraction.ONE))
    private val weak = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ONE),
        LinearPinnedObjective(SourceDerivation.Premise("weak")))
    private val strong = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ofLong(2)),
        LinearPinnedObjective(SourceDerivation.Premise("strong")))

    @Test
    fun `a stronger uncut certificate should preserve ordinary point guidance without cut attribution`() {
        val baseline = LinearAuthorityBoundBaseline(SourceLpBudget()) { _, _ -> }
        val ordinary = LinearAuthorityResult(bound = weak, split = x to BigFraction.ONE,
            relaxedPoint = mapOf(x to 1.5), advisoryPoint = mapOf(x to 1.25),
            cutBoundFamilies = setOf(CutFamily.SOURCE_ROUNDING))

        val result = baseline.advance(source, ExecutionControl.Never,
            { LinearAuthorityResult(bound = strong) }, { ordinary })

        assertSame(strong, result.bound)
        assertEquals(ordinary.split, result.split)
        assertEquals(ordinary.relaxedPoint, result.relaxedPoint)
        assertEquals(ordinary.advisoryPoint, result.advisoryPoint)
        assertTrue(result.cutBoundFamilies.isEmpty())
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
            .verify(assertNotNull(result.bound)))
    }

    @Test
    fun `a completed baseline should report its primal costs once`() {
        val baseline = LinearAuthorityBoundBaseline(SourceLpBudget()) { _, _ -> }
        val paid = LinearAuthorityResult(bound = strong, primalStages = mapOf("Work" to 3L, "MaxRows" to 9L))
        val ordinary = LinearAuthorityResult(bound = weak, primalStages = mapOf("Work" to 5L, "MaxRows" to 4L))
        val first = baseline.advance(source, ExecutionControl.Never, { paid }, { ordinary })

        val repeated = baseline.advance(source, ExecutionControl.Never,
            { error("completed baseline was repeated") }, { ordinary })

        assertEquals(mapOf("Work" to 8L, "MaxRows" to 9L), first.primalStages)
        assertEquals(ordinary.primalStages, repeated.primalStages)
    }

    @Test
    fun `an unavailable baseline bound should not repeat on integer descendants`() {
        val baseline = LinearAuthorityBoundBaseline(SourceLpBudget()) { _, _ -> }
        baseline.advance(source, ExecutionControl.Never, { LinearAuthorityResult() }, { LinearAuthorityResult() })
        val child = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE))))

        baseline.advance(child, ExecutionControl.Never,
            { error("completed baseline was repeated") }, { LinearAuthorityResult() })

        assertEquals(1L, baseline.observations["numericalCutBaselineCompleted"])
        assertEquals(1L, baseline.observations["numericalCutBaselineBoundUnavailable"])
    }

    @Test
    fun `a paused baseline should finish before the ordinary query starts`() {
        val baseline = LinearAuthorityBoundBaseline(SourceLpBudget()) { _, _ -> }
        val first = baseline.advance(source, ExecutionControl.Never,
            { LinearAuthorityResult(bound = strong, paused = true, split = x to BigFraction.ONE) },
            { error("ordinary query started before the baseline completed") })

        val resumed = baseline.advance(source, ExecutionControl.Never,
            { LinearAuthorityResult(bound = strong) }, { LinearAuthorityResult(bound = weak) })

        assertTrue(first.paused)
        assertSame(strong, first.bound)
        assertNull(first.split)
        assertSame(strong, resumed.bound)
        assertEquals(1L, baseline.observations["numericalCutBaselineCompleted"])
    }

    @Test
    fun `a conditional baseline bound should stay out of an unrestricted result`() {
        val baseline = LinearAuthorityBoundBaseline(SourceLpBudget()) { _, _ -> }
        val child = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE))))
        baseline.advance(child, ExecutionControl.Never,
            { LinearAuthorityResult(bound = strong.copy(scope = child.scope)) }, { LinearAuthorityResult() })

        val result = baseline.advance(source, ExecutionControl.Never,
            { error("completed baseline was repeated") }, { LinearAuthorityResult() })

        assertNull(result.bound)
    }

    @Test
    fun `a paused ordinary query should retain its completed uncut certificate`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val context = SourceProofContext(scope, setOf(x, real), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2)), BigFraction.ofLong(-3))),
            "real lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "real upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ONE)),
        ), mapOf(x to BigFraction.ONE))
        val funding = SourceLpFunding(2_000_000L, 8_000_000L).apply { grant(2_000_000L) }
        var blocked = false
        var operations = 0L
        val budget = SourceLpBudget(funding = funding, onWork = {
            operations += it.operations
            if (!blocked && operations == 2L) {
                blocked = true
                funding.grant(0L)
            }
        })
        LinearAuthority(budget, retainLp = true).use { authority ->
            val paused = authority.advanceWithPointCuts(context, ExecutionControl.Never, preserveUncutBound = true)
            assertTrue(paused.paused)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(paused.bound).claim as SourceClaim.Bound).value)
            assertNull(paused.split)
            assertTrue(paused.relaxedPoint.isEmpty())

            funding.grant(2_000_000L)
            val completed = authority.advanceWithPointCuts(context, ExecutionControl.Never, preserveUncutBound = true)

            assertFalse(completed.paused)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(completed.bound).claim as SourceClaim.Bound).value)
            assertEquals(2, budget.operations)
            assertEquals(1L, authority.cutObservations()["numericalCutBaselineCompleted"])
            assertEquals(1L, authority.cutObservations()["numericalCutBaselineQueries"])
            assertNull(completed.primalStages["PrimalFractional"])
            val recipes = authority.cutObservations().filterKeys {
                it.startsWith("numericalCutLifecycleRECIPE_") && it.endsWith("_PreparationWork")
            }.values.sum()
            assertEquals(budget.measuredPreparationWork, recipes)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context.withLinearAuthority())
                .verify(assertNotNull(completed.bound)))
        }
    }
}
