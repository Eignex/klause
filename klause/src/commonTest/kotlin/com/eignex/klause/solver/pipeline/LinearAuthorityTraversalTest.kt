package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearAuthorityTraversalTest {
    @Test
    fun `checked mixed cuts should be credited when the source frontier closes a node`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("mixed-cut-use", "exact-affine"),
            objective = SourceObjective("sum", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(integer, real), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "integer" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "real" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(integer to BigFraction.ONE, real to BigFraction.ONE))
        val point = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.0))
        LinearAuthorityTraversal(source, SourceLpBudget(), Cancellation.Never, { false }, {},
            { point to BigFraction.ofLong(2) }, useGuidance = true).use { component ->
            val session = SearchComponentSet(listOf(component)).session(atoms = SearchAtomRegistry(0))
            assertEquals(ComponentResult.Consistent, session.initialize())

            val disposition = component.beforeBranch(session)

            assertEquals(SearchNodeDisposition.Prune, disposition)
            val observations = component.observations()
            val prefix = "numericalCutLifecycleSOURCE_MIXED_ROUNDING_"
            assertTrue((observations[prefix + "CHECKED_BOUND"] ?: 0L) > 0L)
            assertTrue((observations[prefix + "BOUND_PRUNE"] ?: 0L) +
                (observations[prefix + "ATTAINED_OPTIMUM"] ?: 0L) > 0L)
        }
    }

    @Test
    fun `a source grant pause should retain a named split without another numerical query`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val scope = SourceScope(SourceRevision("grant-pause", "exact-affine"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(integer), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE))),
            mapOf(integer to BigFraction.ONE))
        val funding = SourceLpFunding(1_000_000L, 1_000_000L).apply { grant(1_000_000L) }
        val budget = SourceLpBudget(funding = funding)
        LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { null },
            useRefutationPropagation = true).use { component ->
            val session = SearchComponentSet(listOf(component)).session(atoms = SearchAtomRegistry(0))
            assertEquals(ComponentResult.Consistent, session.initialize())
            assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
            val operations = budget.operations
            funding.grant(0L)

            assertEquals(SearchNodeDisposition.Pause, component.beforeBranch(session))
            funding.grant(1L)
            assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
            val alternatives = assertNotNull(component.nextBranch(session))

            assertEquals(2, alternatives.size)
            assertTrue(alternatives.all { session.atomLiteral(it) != null })
            assertTrue(funding.paused)
            assertEquals(operations, budget.operations)
        }
    }
}
