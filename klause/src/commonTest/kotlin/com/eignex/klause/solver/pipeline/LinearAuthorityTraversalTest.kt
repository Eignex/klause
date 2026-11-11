package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.LpSolveContext
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
import com.eignex.klause.solver.search.SearchDecision
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
    fun `global numerical bounds should require every Boolean alternative`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val b = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
            val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val sign = if (sense == ObjectiveSense.MINIMIZE) BigFraction.MINUS_ONE else BigFraction.ONE
            val scope = SourceScope(SourceRevision("numeric-global-coverage", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, setOf(b, x), linkedMapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(4))),
                "false" to SourcePremise(SourceInequality(mapOf(x to sign), sign * BigFraction.ofLong(2)),
                    guards = mapOf(0 to false)),
                "true" to SourcePremise(SourceInequality(mapOf(x to sign), sign * BigFraction.ofLong(3)),
                    guards = mapOf(0 to true)),
            ), mapOf(x to BigFraction.ONE))
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
            var standing: Pair<Sample, BigFraction>? = null

            LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { standing },
                useGuidance = true, useCuts = false).use { component ->
                val session = SearchComponentSet(listOf(component)).session()
                assertEquals(ComponentResult.Consistent, session.initialize())
                assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
                for (pin in listOf(false, true)) {
                    val value = if (sense == ObjectiveSense.MAXIMIZE && pin) 3L else 2L
                    standing = Sample(booleanArrayOf(sense == ObjectiveSense.MAXIMIZE && pin), longArrayOf(),
                        doubleArrayOf(value.toDouble()), exactReals = listOf(BigFraction.ofLong(value))) to
                        BigFraction.ofLong(value)
                    assertEquals(ComponentResult.Consistent, session.push(SearchDecision.Bool(Lit.make(0, pin))))
                    assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))
                    session.popTo(0)

                    component.updateGlobalBound()

                    if (!pin) assertNull(component.rootNumerical)
                }

                val expected = if (sense == ObjectiveSense.MINIMIZE) 2.0 else 3.0
                assertEquals(expected, assertNotNull(assertNotNull(component.rootNumerical).bound), 0.000001)
                assertNull(component.rootBound)
                assertNull(component.completed())
                assertEquals(0L, budget.measuredBoundCertificationWork)
                assertEquals(0L, budget.measuredConflictCertificationWork)
            }
        }
    }

    @Test
    fun `stopped numerical coverage should withhold global authority and retain its work`() {
        for (stop in listOf("funding", "cancellation")) {
            val b = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
            val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val scope = SourceScope(SourceRevision("numeric-coverage-stop", "linear"),
                objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
            val source = SourceProofContext(scope, setOf(b, x), mapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(-2))),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            ), mapOf(x to BigFraction.ONE))
            val funding = SourceLpFunding(10_000_000L, 64_000_000L).also { it.grant(10_000_000L) }
            var cancelCoverage = false
            var cancelled = false
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical }, funding = funding,
                onWork = { if (cancelCoverage && it.preparationWork > 0L) cancelled = true })
            val standing = Sample(booleanArrayOf(false), longArrayOf(), doubleArrayOf(2.0),
                exactReals = listOf(BigFraction.ofLong(2))) to BigFraction.ofLong(2)

            LinearAuthorityTraversal(source, budget, Cancellation { cancelled }, { false }, {}, { standing },
                useGuidance = true, useCuts = false).use { component ->
                val session = SearchComponentSet(listOf(component)).session()
                assertEquals(ComponentResult.Consistent, session.initialize())
                for (pin in listOf(false, true)) {
                    assertEquals(ComponentResult.Consistent, session.push(SearchDecision.Bool(Lit.make(0, pin))))
                    assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))
                    session.popTo(0)
                }
                val work = budget.measuredPreparationWork
                if (stop == "funding") funding.grant(0L) else cancelCoverage = true

                component.updateGlobalBound()

                assertNull(component.rootNumerical)
                assertEquals(work + if (stop == "cancellation") 1L else 0L, budget.measuredPreparationWork)
                cancelCoverage = false
                cancelled = false
                funding.grant(funding.remainingWork)
                component.updateGlobalBound()
                assertEquals(2.0, assertNotNull(component.rootNumerical).bound)
                assertNull(component.rootBound)
            }
        }
    }

    @Test
    fun `numerical frontier ranks should spend the current grant without creating exact evidence`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val scope = SourceScope(SourceRevision("numeric-frontier-rank", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, setOf(x), mapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(-2))),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            ), mapOf(x to BigFraction.ONE))
            val value = if (sense == ObjectiveSense.MINIMIZE) 2L else 3L
            val standing = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble()),
                exactReals = listOf(BigFraction.ofLong(value))) to BigFraction.ofLong(value)
            val funding = SourceLpFunding(10_000_000L, 64_000_000L).also { it.grant(10_000_000L) }
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical }, funding = funding)

            LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { standing },
                useGuidance = true, useCuts = false).use { component ->
                val session = SearchComponentSet(listOf(component)).session()
                assertEquals(ComponentResult.Consistent, session.initialize())
                assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))
                val expected = BigFraction.ofLong(if (sense == ObjectiveSense.MINIMIZE) value else -value)
                val work = budget.measuredPreparationWork

                assertEquals(expected, component.frontierRank())

                assertTrue(budget.measuredPreparationWork > work)
                funding.grant(0L)
                assertNull(component.frontierRank())
                assertNull(component.rootBound)
                assertEquals(0L, budget.measuredBoundCertificationWork)
            }
        }
    }

    @Test
    fun `numerical global coverage should exclude refuted alternatives`() {
        for (allRefuted in listOf(false, true)) {
            val b = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
            val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val scope = SourceScope(SourceRevision("numeric-global-refutation", "linear"),
                objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
            val source = SourceProofContext(scope, setOf(b, x), mapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(-2))),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
                "false" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(if (allRefuted) -4L else -2L)), guards = mapOf(0 to false)),
                "true" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(-4)), guards = mapOf(0 to true)),
            ), mapOf(x to BigFraction.ONE))
            val standing = Sample(booleanArrayOf(false), longArrayOf(), doubleArrayOf(2.0),
                exactReals = listOf(BigFraction.ofLong(2))) to BigFraction.ofLong(2)
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })

            LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {},
                { standing.takeUnless { allRefuted } }, useGuidance = true, useCuts = false).use { component ->
                val session = SearchComponentSet(listOf(component)).session()
                assertEquals(ComponentResult.Consistent, session.initialize())
                for (pin in listOf(false, true)) {
                    assertEquals(ComponentResult.Consistent, session.push(SearchDecision.Bool(Lit.make(0, pin))))
                    assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))
                    session.popTo(0)
                }

                component.updateGlobalBound()

                val claim = assertNotNull(component.rootNumerical)
                assertEquals(allRefuted, claim.infeasible)
                assertEquals(if (allRefuted) null else 2.0, claim.bound)
                assertNull(component.rootBound)
                assertEquals(0L, budget.measuredConflictCertificationWork)
            }
        }
    }

    @Test
    fun `an inherited numerical bound should prune a child without another LP query`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val z = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("numeric-inherited-bound", "linear"),
            objective = SourceObjective("z", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x, z), linkedMapOf(
            "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE)),
            "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
            "z lower" to SourcePremise(SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
            "z upper" to SourcePremise(SourceInequality(mapOf(z to BigFraction.ONE), BigFraction.ofLong(2))),
        ), mapOf(z to BigFraction.ONE))
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        var standing: Pair<Sample, BigFraction>? = null

        LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { standing },
            useGuidance = true, inheritBounds = true, useCuts = false).use { component ->
            val session = SearchComponentSet(listOf(component)).session()
            assertEquals(ComponentResult.Consistent, session.initialize())
            assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
            val operations = budget.operations
            val alternative = assertNotNull(component.nextBranch(session)).first()
            standing = Sample(booleanArrayOf(), longArrayOf(1L), doubleArrayOf(1.0),
                exactReals = listOf(BigFraction.ONE)) to BigFraction.ONE
            assertEquals(ComponentResult.Consistent, session.push(alternative))

            val disposition = component.beforeBranch(session)

            assertEquals(SearchNodeDisposition.Prune, disposition)
            assertEquals(operations, budget.operations)
            assertEquals(1L, component.observations()["numericalInheritedProjectionBoundClosures"])
            assertNull(component.rootBound)
            assertNull(component.completed())
        }
    }

    @Test
    fun `numerical source bounds should prune a reached incumbent without creating an exact proof`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val scope = SourceScope(SourceRevision("numeric-bound-prune", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, setOf(x), linkedMapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ofLong(-2))),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            ), mapOf(x to BigFraction.ONE))
            val value = if (sense == ObjectiveSense.MINIMIZE) 2L else 3L
            val standing = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble()),
                exactReals = listOf(BigFraction.ofLong(value)))
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })

            LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {},
                { standing to BigFraction.ofLong(value) }, useGuidance = true, useCuts = false).use { component ->
                val session = SearchComponentSet(listOf(component)).session()
                assertEquals(ComponentResult.Consistent, session.initialize())

                val disposition = component.beforeBranch(session)

                assertEquals(SearchNodeDisposition.Prune, disposition)
                assertEquals(value.toDouble(), assertNotNull(component.rootNumerical).bound)
                assertNull(component.rootBound)
                assertNull(component.completed())
                assertEquals(1L, component.observations()["numericalProjectionBoundPrunes"])
                assertEquals(0L, budget.measuredBoundCertificationWork)
            }
        }
    }

    @Test
    fun `numerical source contradictions should prune without creating an exact refutation`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val y = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val scope = SourceScope(SourceRevision("numeric-conflict-prune", "linear"),
            objective = SourceObjective("sum", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "low sum" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2))),
            "high sum" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ONE)),
            "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
            "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
        ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })

        LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { null },
            useGuidance = true, useCuts = false).use { component ->
            val session = SearchComponentSet(listOf(component)).session()
            assertEquals(ComponentResult.Consistent, session.initialize())

            val disposition = component.beforeBranch(session)

            assertEquals(SearchNodeDisposition.Prune, disposition)
            assertTrue(assertNotNull(component.rootNumerical).infeasible)
            assertNull(component.rootBound)
            assertNull(component.completed())
            assertEquals(1L, component.observations()["numericalProjectionConflictPrunes"])
            assertEquals(0L, budget.measuredConflictCertificationWork)
        }
    }

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
