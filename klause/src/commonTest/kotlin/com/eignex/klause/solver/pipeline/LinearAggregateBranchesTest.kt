package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.ir.ObjectiveSense
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
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
