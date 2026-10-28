package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
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
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAggregateBranchesTest {
    private val integers = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
    private val scope = SourceScope(SourceRevision("thin-aggregate", "exact-affine"),
        objective = SourceObjective("zero", ObjectiveSense.MINIMIZE))
    private val weights = integers.mapIndexed { index, id -> id to BigFraction.ofLong(101L + index) }.toMap()
    private val rows = mapOf(
        "lower" to SourcePremise(SourceInequality(weights.mapValues { it.value.negated() },
            BigFraction.ofLong(-150))),
        "upper" to SourcePremise(SourceInequality(weights, BigFraction.ofLong(151))),
    ) + integers.associate { id -> "lower/${id.id}" to
        SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE), BigFraction.ZERO)) }
    private val source = SourceProofContext(scope, integers.toSet(), rows, emptyMap())

    @Test
    fun `a thin row pair should propose a primitive adjacent partition`() {
        val row = LinearAggregateBranches(SourceLpBudget()).propose(source, Cancellation.Never)

        assertEquals(SourceInequality(integers.associateWith { BigFraction.ONE }, BigFraction.ONE), row)
    }

    @Test
    fun `missing shift bounds should retain coordinate fallback`() {
        val unbounded = SourceProofContext(scope, integers.toSet(), rows.filterKeys { !it.startsWith("lower/") })

        val row = LinearAggregateBranches(SourceLpBudget()).propose(unbounded, Cancellation.Never)

        assertNull(row)
    }

    @Test
    fun `unequal primitive terms should preserve the integer activity lattice`() {
        val ids = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val coefficients = ids.zip(listOf(101L, 102L, 201L, 202L).map(BigFraction::ofLong)).toMap()
        val premises = mapOf(
            "lower" to SourcePremise(SourceInequality(coefficients.mapValues { it.value.negated() },
                BigFraction.ofLong(-450))),
            "upper" to SourcePremise(SourceInequality(coefficients, BigFraction.ofLong(451))),
        ) + ids.associate { id -> "lower/${id.id}" to
            SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE), BigFraction.ZERO)) }
        val context = SourceProofContext(scope, ids.toSet(), premises)

        val row = LinearAggregateBranches(SourceLpBudget()).propose(context, Cancellation.Never)

        assertEquals(SourceInequality(ids.zip(listOf(1L, 1L, 2L, 2L).map(BigFraction::ofLong)).toMap(),
            BigFraction.ofLong(4)), row)
    }

    @Test
    fun `aggregate proposals should spend the containing allocation allowance`() {
        val funding = SourceLpFunding(1_000_000L, 1L).also { it.grant(1_000_000L) }
        val policy = LinearAggregateBranches(SourceLpBudget(funding = funding))

        val row = policy.propose(source, Cancellation.Never)

        assertNull(row)
        assertTrue(funding.exhausted)
        assertTrue(policy.allocation > 0L)
    }

    @Test
    fun `open aggregate children should produce checked source coverage`() {
        val result = LinearAuthorityInvocation(source, TheoryParams(sourceLpOperations = 16),
            aggregateBranching = true).use { it.run { _, _ -> } }

        val evidence = assertNotNull(result.terminal)
        assertEquals(SourceClaim.Refutation, evidence.claim)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("numericalAggregateSplits"))
    }

    @Test
    fun `declared finite coordinates should retain checked aggregate coverage`() {
        val model = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(
            Linear(longArrayOf(101, 102), intArrayOf(0, 1), LinearOp.GE, 150L),
            Linear(longArrayOf(101, 102), intArrayOf(0, 1), LinearOp.LE, 151L),
        ))
        val sourceScope = scope.copy(revision = SourceRevision("finite-aggregate", "klause-ir-exact-v1"))
        val context = assertNotNull(model.sourceProofContext(sourceScope,
            LinearObjective(intCoefficients = longArrayOf(0, 0))))

        val result = LinearAuthorityInvocation(context, TheoryParams(sourceLpOperations = 16),
            aggregateBranching = true).use { it.run { _, _ -> } }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceClaim.Refutation, terminal.claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(context.withLinearAuthority()).verify(terminal))
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("numericalAggregateSplits"))
    }

    @Test
    fun `aggregate coverage should survive backjump replay and restart`() {
        val budget = SourceLpBudget(maxOperations = 16)
        LinearAuthorityTraversal(source, budget, Cancellation.Never, { false }, {}, { null },
            aggregateBranching = true).use { component ->
            val session = SearchComponentSet(listOf(component)).session()
            assertEquals(ComponentResult.Consistent, session.initialize())
            assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
            val alternatives = assertNotNull(component.nextBranch(session))
            assertEquals(ComponentResult.Consistent, session.push(alternatives.first()))
            assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))
            val operations = budget.operations
            session.popTo(0)

            assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
            assertEquals(ComponentResult.Consistent, session.push(alternatives.first()))
            assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))

            assertEquals(operations, budget.operations)
            assertEquals(ComponentResult.Consistent, session.restart())
            assertEquals(SearchNodeDisposition.Expand, component.beforeBranch(session))
            assertEquals(ComponentResult.Consistent, session.push(alternatives.last()))
            assertEquals(SearchNodeDisposition.Prune, component.beforeBranch(session))
            session.popTo(0)
            val terminal = assertNotNull(component.completed())
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `a guarded aggregate refutation should preserve the feasible Boolean alternative`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = rows.mapValues { (id, premise) ->
            if (id == "lower" || id == "upper") SourcePremise(premise.inequality, mapOf(0 to false)) else premise
        }
        val context = SourceProofContext(scope, source.coordinates + bool, guarded, mapOf(bool to BigFraction.ONE))

        val result = LinearAuthorityInvocation(context, TheoryParams(sourceLpOperations = 16),
            boundOrdered = true, aggregateBranching = true).use { it.run { _, _ -> } }

        val terminal = assertNotNull(result.terminal)
        assertTrue(assertIs<SourceClaim.Optimal>(terminal.claim).sample.boolValue(0))
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(context.withLinearAuthority()).verify(terminal))
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("numericalAggregateSplits"))
    }
}
