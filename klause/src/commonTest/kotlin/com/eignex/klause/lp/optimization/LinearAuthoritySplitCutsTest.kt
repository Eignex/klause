package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
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
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearAuthoritySplitCutsTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("split", "exact-affine"))
    private val left = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ONE),
        BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal())
    private val right = SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE), BigFraction.ofLong(2))
    private val source = SourceProofContext(scope, setOf(x, real), linkedMapOf(
        "left" to SourcePremise(left), "right" to SourcePremise(right)))
    private val rows = listOf(
        LinearAuthoritySplitRow(LinearAuthorityAggregateTerm(0, "left", left), left.coefficients),
        LinearAuthoritySplitRow(LinearAuthorityAggregateTerm(1, "right", right), right.coefficients))
    private val point = mapOf(x to 0.5, real to 1.5)

    @Test
    fun `signed slack cancellation should prove a mixed cut without a continuous bound`() {
        val cut = LinearAuthoritySplitCuts.prepare(source, rows, point, Cancellation.Never).single()

        assertEquals(mapOf(x to BigFraction.ofLong(6), real to BigFraction.ofLong(4)), cut.inequality.coefficients)
        assertEquals(BigFraction.ofLong(8), cut.inequality.rhs)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(CutFamily.SOURCE_AGGREGATION, cut.family)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `signed slack proofs should support both pivot and anchor orientations`() {
        for (sign in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            for (reversed in listOf(false, true)) {
                val inequalities = listOf(left, right).map {
                    SourceInequality(it.coefficients + (real to sign), it.rhs)
                }.let { if (reversed) it.reversed() else it }
                val premises = inequalities.mapIndexed { index, row -> "row $index" to SourcePremise(row) }.toMap()
                val context = SourceProofContext(scope, setOf(x, real), premises)
                val inputs = inequalities.mapIndexed { index, row ->
                    LinearAuthoritySplitRow(LinearAuthorityAggregateTerm(index, "row $index", row), row.coefficients)
                }

                val cut = LinearAuthoritySplitCuts.prepare(context, inputs, point, Cancellation.Never).single()

                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            }
        }
    }

    @Test
    fun `an uncanceled continuous coordinate should require an original bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val changed = SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)
        val context = SourceProofContext(scope, setOf(x, real, residual), linkedMapOf(
            "left" to SourcePremise(changed), "right" to SourcePremise(right)))
        val inputs = listOf(LinearAuthoritySplitRow(LinearAuthorityAggregateTerm(0, "left", changed),
            changed.coefficients), rows[1])

        val cuts = LinearAuthoritySplitCuts.prepare(context, inputs, point, Cancellation.Never)

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `uncanceled coordinates should use the stated bound normalization`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        for (side in listOf(BigFraction.ofLong(-2), BigFraction.ofLong(2))) {
            val changed = SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)
            val bound = SourceInequality(mapOf(residual to side), BigFraction.ZERO)
            val context = SourceProofContext(scope, setOf(x, real, residual), linkedMapOf(
                "left" to SourcePremise(changed), "right" to SourcePremise(right), "bound" to SourcePremise(bound)))
            val inputs = listOf(LinearAuthoritySplitRow(LinearAuthorityAggregateTerm(0, "left", changed),
                changed.coefficients), rows[1])

            val cut = LinearAuthoritySplitCuts.prepare(context, inputs, point, Cancellation.Never).single()

            assertEquals(setOf(0, 1, 2), cut.owners)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `a guarded original row should not supply an unconditional slack`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val context = SourceProofContext(scope, setOf(x, real, bool), linkedMapOf(
            "left" to SourcePremise(left, mapOf(0 to true)), "right" to SourcePremise(right)))

        val cuts = LinearAuthoritySplitCuts.prepare(context, rows, point, Cancellation.Never)

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `signed slack proofs should retain their invocation and source scope`() {
        val scoped = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ZERO))))
        val cut = LinearAuthoritySplitCuts.prepare(scoped, rows, point, Cancellation.Never).single()
        val foreign = source.atScope(scope.copy(revision = SourceRevision("foreign", "exact-affine")))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(scoped).verify(
            SourceEvidence(scoped.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        for (context in listOf(source, foreign)) {
            assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(
                SourceEvidence(context.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `an interrupted split proof should publish no partial proposal`() {
        var stopped = false
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthoritySplitCuts.prepare(source, rows, point, Cancellation { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateSplitProofTerms") stopped = true
            })

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateSplitStopped"])
    }

    @Test
    fun `split discovery should retain work storage scalar support and proof limits`() {
        val indices: List<Map<Pair<SourceCoordinate, Boolean>, List<LinearAuthoritySourceRow>>?> =
            listOf(null, mapOf((real to true) to rows))
        for (incidence in indices) for (limits in listOf(LinearAuthorityAggregateLimits(work = 1L),
            LinearAuthorityAggregateLimits(allocation = 1L), LinearAuthorityAggregateLimits(scalarBits = 1),
            LinearAuthorityAggregateLimits(support = 1), LinearAuthorityAggregateLimits(proofBytes = 1L))) {
            val observations = mutableMapOf<String, Long>()

            val cuts = LinearAuthoritySplitCuts.prepare(source, rows, point, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limits, incidence = incidence)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        }
    }

    @Test
    fun `point selection should consume a checked split cut in the objective bound`() {
        val objective = scope.copy(objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))
        val context = SourceProofContext(objective, setOf(x, real), linkedMapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO))),
            mapOf(x to BigFraction.ofLong(-3), real to BigFraction.ofLong(-2)))
        for ((enabled, expected) in listOf(false to BigFraction.ofLong(-9) * BigFraction.ofLong(2).reciprocal(),
            true to BigFraction.ofLong(-4))) {
            val capture = LinearAuthorityCapture(4, 50_000L, 2_000_000L, Cancellation.Never)
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = enabled).use { authority ->
                authority.capture = capture
                val result = authority.advanceWithPointCuts(context, Cancellation.Never)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context.withLinearAuthority()).verify(
                    assertNotNull(result.bound)))
                if (enabled) {
                    assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
                    assertTrue(capture.records.any { record -> record.cuts.any { it.installed && it.checked } })
                }
            }
        }
    }

    @Test
    fun `shared incidence should leave the production slice for a checked split`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val loose = SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE,
            residual to BigFraction.ONE), BigFraction.ofLong(100))
        val premises = linkedMapOf("left" to SourcePremise(left), "right" to SourcePremise(right))
        repeat(1800) { index -> premises["loose $index"] = SourcePremise(loose) }
        val context = SourceProofContext(scope, setOf(x, real, residual), premises)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(context, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits, point = point + (residual to 0.0))

        assertEquals(1, cuts.size, observations.toString())
        val cut = cuts.single()

        assertEquals(mapOf(x to BigFraction.ofLong(6), real to BigFraction.ofLong(4)), cut.inequality.coefficients)
        assertEquals(BigFraction.ofLong(8), cut.inequality.rhs)
        assertEquals(1L, observations["AggregateSplitBorrowedIncidence"])
        assertEquals(null, observations["AggregateSplitBoundVisits"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `a continuous prefix should leave room for checked integer anchor rows`() {
        val first = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val second = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val loose = SourceInequality(mapOf(first to BigFraction.ONE, second to BigFraction.ONE),
            BigFraction.ofLong(100))
        val premises = linkedMapOf<String, SourcePremise>()
        repeat(1800) { index -> premises["loose $index"] = SourcePremise(loose) }
        premises["left"] = SourcePremise(left)
        premises["right"] = SourcePremise(right)
        val context = SourceProofContext(scope, setOf(x, real, first, second), premises)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(context, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits, point = point + mapOf(first to 0.0, second to 0.0))

        assertEquals(1, cuts.size, observations.toString())
        val cut = cuts.single()

        assertEquals(mapOf(x to BigFraction.ofLong(6), real to BigFraction.ofLong(4)), cut.inequality.coefficients)
        assertEquals(BigFraction.ofLong(8), cut.inequality.rhs)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `an interrupted endpoint scan should withhold the split proposal`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val changed = SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)
        val context = SourceProofContext(scope, setOf(x, real, residual), linkedMapOf(
            "left" to SourcePremise(changed), "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO))))
        val inputs = listOf(LinearAuthoritySplitRow(LinearAuthorityAggregateTerm(0, "left", changed),
            changed.coefficients), rows[1])
        var stopped = false

        val cuts = LinearAuthoritySplitCuts.prepare(context, inputs, point, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateSplitBoundVisits") stopped = true })

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }


}
