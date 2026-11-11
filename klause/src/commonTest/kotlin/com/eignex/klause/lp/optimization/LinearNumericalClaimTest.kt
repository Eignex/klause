package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearNumericalClaimTest {
    @Test
    fun `a numerical bound should apply only under its original or stronger restrictions`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-scope", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val origin = source.atScope(scope.copy(condition = scope.condition.bound(x, BigFraction.ofLong(2), false)))
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(origin, 2.0, false, budget, Cancellation.Never))

        val narrower = source.atScope(scope.copy(condition = scope.condition.bound(x, BigFraction.ofLong(3), false)))
        val wider = source.atScope(scope.copy(condition = scope.condition.bound(x, BigFraction.ONE, false)))

        assertTrue(claim.appliesTo(narrower, budget, Cancellation.Never))
        assertFalse(claim.appliesTo(wider, budget, Cancellation.Never))
        assertFalse(claim.appliesTo(source, budget, Cancellation.Never))
    }

    @Test
    fun `numerical claims should reject changed revision objective or direction`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-invocation", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, budget, Cancellation.Never))

        for (changed in listOf(
            scope.copy(revision = SourceRevision("other", "linear")),
            scope.copy(objective = SourceObjective("other", ObjectiveSense.MINIMIZE)),
            scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)),
        )) {
            assertFalse(claim.appliesTo(source.atScope(changed), budget, Cancellation.Never))
        }
    }

    @Test
    fun `a fresh source binding should reject an earlier numerical bound`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-binding", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val rebound = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, budget, Cancellation.Never))

        val applicable = claim.appliesTo(rebound, budget, Cancellation.Never)

        assertFalse(applicable)
    }

    @Test
    fun `exact consumers should reject numerical authority`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-mode", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, budget, Cancellation.Never))

        val exact = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, successful -> successful })
        val applicable = claim.appliesTo(source, SourceLpBudget(solveContext = { exact }), Cancellation.Never)

        assertFalse(applicable)
    }

    @Test
    fun `scope implication should require its containing work allowance`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-funding", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val initial = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, initial, Cancellation.Never))
        val funding = SourceLpFunding(1L, 4096L).apply { grant(1L) }
        val budget = SourceLpBudget(funding = funding, solveContext = { LpSolveContext.Numerical })
        val child = source.atScope(scope.copy(condition = scope.condition.bound(x, BigFraction.ofLong(3), false)))

        val applicable = claim.appliesTo(child, budget, Cancellation.Never)

        assertFalse(applicable)
    }

    @Test
    fun `cancelled scope verification should withhold authority and retain charged work`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-cancellation", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x), emptyMap(), mapOf(x to BigFraction.ONE))
        val initial = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, initial, Cancellation.Never))
        val funding = SourceLpFunding(1000L, 4096L).apply { grant(1000L) }
        var cancelled = false
        val budget = SourceLpBudget(funding = funding, solveContext = { LpSolveContext.Numerical },
            onWork = { if (it.preparationWork > 0L) cancelled = true })
        val child = source.atScope(scope.copy(condition = scope.condition.bound(x, BigFraction.ofLong(3), false)))

        val applicable = claim.appliesTo(child, budget, Cancellation { cancelled })

        assertFalse(applicable)
        assertTrue(funding.spent > 0L)
    }
}
