package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityTraversalTest {
    @Test
    fun `a later incumbent should close a checked partial bound without another LP query`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
            val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
            val sign = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val scope = SourceScope(SourceRevision("partial-bound-closure", "exact-affine"),
                objective = SourceObjective("weighted", sense))
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2),
                    y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2))),
                "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                    y to BigFraction.ofLong(-2)), BigFraction.ofLong(-2))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ofLong(2) * sign, y to sign))
            val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
            var blocked = false
            var operations = 0L
            val budget = SourceLpBudget(funding = funding, onWork = {
                operations += it.operations
                if (!blocked && operations == 2L) {
                    blocked = true
                    funding.grant(0L)
                }
            })
            var standing: Pair<Sample, BigFraction>? = null
            LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
                LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { standing },
                    borrowedAuthority = authority, useGuidance = true, pointCuts = true,
                    aggregateCuts = true).use { component ->
                    val session = SearchComponentSet(listOf(component)).session()
                    assertEquals(ComponentResult.Consistent, session.initialize())
                    assertEquals(SearchNodeDisposition.Pause, component.beforeBranch(session))
                    assertTrue(blocked)
                    for (category in listOf("WithoutIncumbent", "ShortOfIncumbent", "ReachingIncumbent")) {
                        assertEquals(0L, component.observations()["numericalBounds$category"])
                        assertEquals(0L, component.observations()["numericalCutBounds$category"])
                    }
                    val before = budget.operations
                    standing = Sample(booleanArrayOf(), longArrayOf(0, 2)) to BigFraction.ofLong(2) * sign

                    val disposition = component.beforeBranch(session)

                    assertEquals(SearchNodeDisposition.Prune, disposition)
                    assertEquals(before, budget.operations)
                    assertEquals(0L, component.observations()["numericalPendingLeaves"])
                    assertEquals(1L, component.observations()["numericalPartialBoundClosures"])
                    val terminal = assertNotNull(component.completed())
                    assertEquals(SourceValue.Rational(BigFraction.ofLong(2) * sign),
                        assertIs<SourceClaim.Optimal>(terminal.claim).value)
                    assertEquals(SourceEvidenceCheck.Checked,
                        SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
                }
            }
        }
    }

    @Test
    fun `a checked partial bound should remain pending below a minimizing incumbent`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val scope = SourceScope(SourceRevision("partial-bound-open", "exact-affine"),
            objective = SourceObjective("weighted", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2),
                y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2))),
            "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(-2))),
            "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(x to BigFraction.ofLong(2), y to BigFraction.ONE))
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        var blocked = false
        var operations = 0L
        val budget = SourceLpBudget(funding = funding, onWork = {
            operations += it.operations
            if (!blocked && operations == 2L) {
                blocked = true
                funding.grant(0L)
            }
        })
        var standing: Pair<Sample, BigFraction>? = null
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { standing },
                borrowedAuthority = authority, useGuidance = true, pointCuts = true,
                aggregateCuts = true).use { component ->
                val session = SearchComponentSet(listOf(component)).session()
                assertEquals(ComponentResult.Consistent, session.initialize())
                assertEquals(SearchNodeDisposition.Pause, component.beforeBranch(session))
                assertTrue(blocked)
                val before = budget.operations
                standing = Sample(booleanArrayOf(), longArrayOf(2, 0)) to BigFraction.ofLong(4)

                val disposition = component.beforeBranch(session)

                assertEquals(SearchNodeDisposition.Pause, disposition)
                assertEquals(before, budget.operations)
                assertEquals(1L, component.observations()["numericalPendingLeaves"])
                assertEquals(0L, component.observations()["numericalIncumbentBoundClosures"])
                assertNull(component.completed())
            }
        }
    }

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

    @Test
    fun `nonterminal bounds should distinguish the standing incumbent comparison`() {
        for (sense in ObjectiveSense.entries) {
            for ((value, comparison) in listOf(null to "WithoutIncumbent",
                BigFraction.ONE to "ShortOfIncumbent", BigFraction.ZERO to "ReachingIncumbent")) {
                val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
                val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
                val sign = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
                val scope = SourceScope(SourceRevision("bound-comparison", "exact-affine"),
                    objective = SourceObjective("real", sense))
                val source = SourceProofContext(scope, setOf(integer, real), mapOf(
                    "integer" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                        BigFraction.MINUS_ONE)),
                    "real" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                ), mapOf(real to sign))
                val standing = value?.let {
                    Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(it.toDouble()),
                        exactReals = listOf(it)) to it * sign
                }
                LinearAuthorityTraversal(source, SourceLpBudget(), Cancellation.Never, { false }, {},
                    { standing }, useGuidance = true, useCuts = false).use { component ->
                    val session = SearchComponentSet(listOf(component)).session(atoms = SearchAtomRegistry(0))
                    assertEquals(ComponentResult.Consistent, session.initialize())

                    val disposition = component.beforeBranch(session)

                    val reaches = comparison == "ReachingIncumbent"
                    assertEquals(if (reaches) SearchNodeDisposition.Prune else SearchNodeDisposition.Expand,
                        disposition)
                    val observations = component.observations()
                    assertEquals(1L, observations["numericalCertifiedBounds"])
                    for (category in listOf("WithoutIncumbent", "ShortOfIncumbent", "ReachingIncumbent")) {
                        assertEquals(if (category == comparison) 1L else 0L,
                            observations["numericalBounds$category"])
                        assertEquals(0L, observations["numericalCutBounds$category"])
                    }
                    assertEquals(if (reaches) 1L else 0L, observations["numericalIncumbentBoundClosures"])
                }
            }
        }
    }

    @Test
    fun `cut supported comparisons should be a subset of nonterminal bound comparisons`() {
        for (sense in ObjectiveSense.entries) {
            val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
            val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
            val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
            val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
            val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val sign = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val scope = SourceScope(SourceRevision("cut-bound-comparison", "exact-affine"),
                objective = SourceObjective("sum", sense))
            val source = SourceProofContext(scope, setOf(integer, x, y, z, real), mapOf(
                "mixed" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
                "integer" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "real" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "xy" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                    y to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
                "xz" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                    z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
                "yz" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE,
                    z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
            ) + listOf(x, y, z).associate { coordinate ->
                "lower-" + coordinate.id to SourcePremise(
                    SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            }, listOf(integer, x, y, z, real).associateWith { sign })
            val point = Sample(booleanArrayOf(), longArrayOf(2, 1, 1, 0), doubleArrayOf(0.0),
                exactReals = listOf(BigFraction.ZERO))
            LinearAuthorityTraversal(source, SourceLpBudget(), Cancellation.Never, { false }, {},
                { point to BigFraction.ofLong(4) * sign }, useGuidance = true).use { component ->
                val session = SearchComponentSet(listOf(component)).session(atoms = SearchAtomRegistry(0))
                assertEquals(ComponentResult.Consistent, session.initialize())

                val disposition = component.beforeBranch(session)

                assertEquals(SearchNodeDisposition.Expand, disposition)
                val observations = component.observations()
                assertEquals(1L, observations["numericalBoundsShortOfIncumbent"])
                assertEquals(1L, observations["numericalCutBoundsShortOfIncumbent"])
                assertEquals(0L, observations["numericalIncumbentBoundClosures"])
                assertTrue((observations["numericalCutLifecycleSOURCE_MIXED_ROUNDING_CHECKED_BOUND"] ?: 0L) > 0L)
            }
        }
    }

    @Test
    fun `terminal bounds should remain separate from nonterminal incumbent comparisons`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val scope = SourceScope(SourceRevision("terminal-bound-comparison", "exact-affine"),
            objective = SourceObjective("integer", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(integer), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO))),
            mapOf(integer to BigFraction.ONE))
        LinearAuthorityTraversal(source, SourceLpBudget(), Cancellation.Never, { false }, {}, { null },
            useGuidance = true, useCuts = false).use { component ->
            val session = SearchComponentSet(listOf(component)).session(atoms = SearchAtomRegistry(0))
            assertEquals(ComponentResult.Consistent, session.initialize())

            val disposition = component.beforeBranch(session)

            assertEquals(SearchNodeDisposition.Prune, disposition)
            assertIs<SourceClaim.Optimal>(assertNotNull(component.completed()).claim)
            val observations = component.observations()
            assertEquals(1L, observations["numericalCertifiedBounds"])
            for (category in listOf("WithoutIncumbent", "ShortOfIncumbent", "ReachingIncumbent")) {
                assertEquals(0L, observations["numericalBounds$category"])
                assertEquals(0L, observations["numericalCutBounds$category"])
            }
        }
    }
}
