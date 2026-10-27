package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
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
