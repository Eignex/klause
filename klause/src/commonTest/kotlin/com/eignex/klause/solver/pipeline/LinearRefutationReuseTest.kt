package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
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
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRefutationReuseTest {
    @Test
    fun `resolution should imply a bound before either input has a unit prefix`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val scope = SourceScope(SourceRevision("resolved-unit", "exact-affine"))
        val source = SourceProofContext(scope, setOf(x, y), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                y to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val cache = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
        val learned = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never, resolve = true)
        learned.name(x, BigFraction.ZERO, 0)
        learned.name(y, BigFraction.ZERO, 2)
        for (upper in listOf(true, false)) {
            val condition = SourceCondition().bound(x, if (upper) BigFraction.ZERO else BigFraction.ONE, upper)
                .bound(y, BigFraction.ZERO, true)
            val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE,
                    SourceDerivation.Premise(if (upper) "left" else "right")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper)),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(y, true)),
            )))
            val evidence = SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, conflict)
            cache.retain(evidence)
            learned.retain(evidence)
        }
        assertNull(cache.unit(SourceCondition()))

        val unit = assertNotNull(learned.unit(SourceCondition()))

        assertEquals(y, unit.coordinate)
        assertEquals(BigFraction.ZERO, unit.threshold)
        assertTrue(unit.upper)
        assertTrue(unit.unfixedPivot)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority().atScope(unit.evidence.scope)).verify(unit.evidence))
    }

    @Test
    fun `named complementary conflicts should exclude a region neither proof matches`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val window = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val opening = SourceCondition(upper = mapOf(window to BigFraction.ZERO))
        val scope = SourceScope(SourceRevision("resolution", "exact-affine"), opening)
        val source = SourceProofContext(scope, setOf(x, y, z, window, bool), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                y to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), mapOf(0 to true)),
            "right" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                z to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val left = SourceEvidence(scope.copy(condition = opening.pin(0, true)
            .bound(x, BigFraction.ZERO, true).bound(y, BigFraction.ZERO, true)), SourceClaim.Refutation,
            LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("left")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, true)),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(y, true)),
            ))))
        val right = SourceEvidence(scope.copy(condition = opening.pin(0, true)
            .bound(x, BigFraction.ONE, false).bound(z, BigFraction.ZERO, true)), SourceClaim.Refutation,
            LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("right")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, false)),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(z, true)),
            ))))
        val cache = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
        var stopped = false
        val learned = LinearRefutationReuse(source, SourceLpBudget(), Cancellation { stopped }, resolve = true)
        learned.name(x, BigFraction.ZERO, 2)
        learned.name(y, BigFraction.ZERO, 4)
        learned.name(z, BigFraction.ZERO, 6)
        learned.retain(left)
        stopped = true
        learned.retain(right)
        assertNull(learned.observations()["numericalRefutationReuseResolutionProofs"])
        stopped = false
        for (proof in listOf(left, right)) {
            cache.retain(proof)
            learned.retain(proof)
        }
        stopped = true
        assertNull(learned.find(opening.pin(0, true).bound(y, BigFraction.ZERO, true)
            .bound(z, BigFraction.ZERO, true)))
        stopped = false
        for (endpoint in listOf(BigFraction.ZERO, BigFraction.MINUS_ONE)) {
            val region = opening.pin(0, true).bound(y, endpoint, true).bound(z, endpoint, true)
            assertNull(cache.find(region))

            val excluded = assertNotNull(learned.find(region))

            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority().atScope(excluded.scope)).verify(excluded))
        }
        assertNull(learned.find(opening.pin(0, true).bound(y, BigFraction.ONE, true)
            .bound(z, BigFraction.ZERO, true)))
        assertNull(learned.find(SourceCondition(bools = mapOf(0 to true),
            upper = mapOf(y to BigFraction.ZERO, z to BigFraction.ZERO))))
        assertNull(learned.find(opening.pin(0, false).bound(y, BigFraction.ZERO, true)
            .bound(z, BigFraction.ZERO, true)))
        assertEquals(2L, learned.observations()["numericalRefutationReuseResolutionHits"])
        assertEquals(2L, learned.observations()["numericalRefutationReuseResolutionUnfixedHits"])
    }

    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
    private val scope = SourceScope(SourceRevision("reuse-source", "exact-affine"),
        objective = SourceObjective("reuse-objective", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(x, other), mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
    ))
    private val proof = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper = true)),
    )))
    private val restricted = scope.copy(condition = SourceCondition(
        lower = mapOf(other to BigFraction.ONE), upper = mapOf(x to BigFraction.ZERO)))
    private val evidence = SourceEvidence(restricted, SourceClaim.Refutation, proof)

    @Test
    fun `an unnamed real predicate should retain cache reuse without admitting resolution`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never, resolve = true)
        reuse.retain(evidence)

        val reused = assertNotNull(reuse.find(restricted.condition))

        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority().atScope(reused.scope)).verify(reused))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseResolutionNameDeclines"])
        assertNull(reuse.observations()["numericalRefutationReuseResolutionProofs"])
    }

    @Test
    fun `one missing integer side should imply its exact complement before a full match`() {
        val left = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val right = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        for (upper in listOf(true, false)) {
            val coefficient = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val context = SourceProofContext(scope, setOf(left, right), mapOf("sum" to SourcePremise(
                SourceInequality(mapOf(left to coefficient, right to coefficient), BigFraction.MINUS_ONE))))
            val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("sum")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(left, upper)),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(right, upper)),
            )))
            val reuse = LinearRefutationReuse(context, SourceLpBudget(), Cancellation.Never)
            val full = SourceCondition().bound(left, BigFraction.ZERO, upper).bound(right, BigFraction.ZERO, upper)
            reuse.retain(SourceEvidence(scope.copy(condition = full), SourceClaim.Refutation, conflict))
            for (endpoint in listOf(BigFraction.ZERO, coefficient)) {
                val prefix = SourceCondition().bound(left, endpoint, upper)
                assertNull(reuse.find(prefix))

                val unit = assertNotNull(reuse.unit(prefix))

                assertEquals(right, unit.coordinate)
                assertEquals(upper, unit.upper)
                assertEquals(if (upper) BigFraction.ZERO else BigFraction.MINUS_ONE, unit.threshold)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(context.withLinearAuthority().atScope(unit.evidence.scope))
                        .verify(unit.evidence))
            }
            assertNull(reuse.unit(SourceCondition().bound(left, coefficient.negated(), upper)))
            assertNull(reuse.unit(full))
            assertNull(reuse.unit(SourceCondition().bound(left, BigFraction.ZERO, upper)
                .bound(right, coefficient.negated(), !upper)))
        }
    }

    @Test
    fun `unit exclusions should keep Boolean guards and the opening cutoff`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val opening = SourceCondition(upper = mapOf(other to BigFraction.ZERO))
        val local = SourceProofContext(scope.copy(condition = opening), setOf(integer, bool, x, other),
            mapOf("lower" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to true))))
        val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(integer, true)),
        )))
        val reuse = LinearRefutationReuse(local, SourceLpBudget(), Cancellation.Never)
        reuse.retain(SourceEvidence(local.scope.copy(condition = opening.pin(0, true)
            .bound(integer, BigFraction.ZERO, true)), SourceClaim.Refutation, conflict))

        assertNotNull(reuse.unit(opening.pin(0, true)))
        assertNull(reuse.unit(opening))
        assertNull(reuse.unit(opening.pin(0, false)))
        assertNull(reuse.unit(SourceCondition(bools = mapOf(0 to true))))
        assertNotNull(reuse.unit(opening.pin(0, true)))
    }

    @Test
    fun `stopped unit verification should leave the prefix unchanged`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val local = SourceProofContext(scope, setOf(integer), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))))
        val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(integer, true)),
        )))
        var polls = 0
        var stopAfter = Int.MAX_VALUE
        val reuse = LinearRefutationReuse(local, SourceLpBudget(), Cancellation { ++polls >= stopAfter })
        reuse.retain(SourceEvidence(scope.copy(condition = SourceCondition(upper = mapOf(integer to BigFraction.ZERO))),
            SourceClaim.Refutation, conflict))
        polls = 0
        stopAfter = 20

        assertNull(reuse.unit(SourceCondition()))

        polls = 0
        stopAfter = Int.MAX_VALUE
        assertNotNull(reuse.unit(SourceCondition()))
    }

    @Test
    fun `real and nonintegral missing sides should decline unit propagation`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
        reuse.retain(evidence)
        assertNull(reuse.unit(SourceCondition()))
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val local = SourceProofContext(scope, setOf(integer), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))))
        val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
            SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(integer, true)),
        )))
        val integral = LinearRefutationReuse(local, SourceLpBudget(), Cancellation.Never)
        integral.retain(SourceEvidence(scope.copy(condition = SourceCondition(upper =
            mapOf(integer to BigFraction.ofLong(2).reciprocal()))), SourceClaim.Refutation, conflict))

        assertNull(integral.unit(SourceCondition()))
    }

    @Test
    fun `support reduction should reuse a checked refutation after an unrelated bound changes`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
        reuse.retain(evidence)
        val condition = SourceCondition(upper = mapOf(x to BigFraction.ZERO, other to BigFraction.MINUS_ONE))

        val reused = assertNotNull(reuse.find(condition))

        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority().atScope(reused.scope)).verify(reused))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseReducedScopes"])
    }

    @Test
    fun `support lookup should accept equal and stronger bounds`() {
        for (upper in listOf(true, false)) {
            val coefficient = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val context = SourceProofContext(scope, setOf(x), mapOf("bound" to SourcePremise(
                SourceInequality(mapOf(x to coefficient), BigFraction.MINUS_ONE))))
            val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("bound")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper)),
            )))
            val condition = SourceCondition().bound(x, BigFraction.ZERO, upper)
            val reuse = LinearRefutationReuse(context, SourceLpBudget(), Cancellation.Never)
            reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, conflict))

            for (value in listOf(BigFraction.ZERO, coefficient)) {
                assertNotNull(reuse.find(SourceCondition().bound(x, value, upper)))
            }
        }
    }

    @Test
    fun `support lookup should reject weaker and unrelated bounds`() {
        for (upper in listOf(true, false)) {
            val coefficient = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val context = SourceProofContext(scope, setOf(x), mapOf("bound" to SourcePremise(
                SourceInequality(mapOf(x to coefficient), BigFraction.MINUS_ONE))))
            val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("bound")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper)),
            )))
            val condition = SourceCondition().bound(x, BigFraction.ZERO, upper)
            val reuse = LinearRefutationReuse(context, SourceLpBudget(), Cancellation.Never)
            reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, conflict))
            val conditions = listOf(SourceCondition(),
                SourceCondition().bound(x, coefficient.negated(), upper),
                SourceCondition().bound(x, coefficient.negated(), !upper))

            for (candidate in conditions) assertNull(reuse.find(candidate))
        }
    }

    @Test
    fun `reuse should retain the invocation window when a proof has smaller support`() {
        val window = SourceCondition(lower = mapOf(other to BigFraction.ZERO),
            upper = mapOf(other to BigFraction.ONE))
        val local = source.atScope(scope.copy(condition = window))
        val reuse = LinearRefutationReuse(local, SourceLpBudget(), Cancellation.Never)
        val conditioned = SourceCondition(window.bools, window.lower, window.upper + (x to BigFraction.ZERO))
        reuse.retain(SourceEvidence(local.scope.copy(condition = conditioned), SourceClaim.Refutation, proof))

        assertNull(reuse.find(SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        assertNotNull(reuse.find(conditioned))
    }

    @Test
    fun `reuse should retain complete Boolean substitutions after numeric support reduction`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope, source.coordinates + bool, source.premises)
        val reuse = LinearRefutationReuse(guarded, SourceLpBudget(), Cancellation.Never)
        val condition = SourceCondition(bools = mapOf(0 to true), upper = mapOf(x to BigFraction.ZERO))
        reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, proof))

        assertNull(reuse.find(SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        assertNull(reuse.find(SourceCondition(bools = mapOf(0 to false), upper = mapOf(x to BigFraction.ZERO))))
        assertNotNull(reuse.find(condition))
    }

    @Test
    fun `a proof with open Boolean coordinates should not enter reuse`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope, source.coordinates + bool, source.premises)
        val reuse = LinearRefutationReuse(guarded, SourceLpBudget(), Cancellation.Never)

        reuse.retain(evidence)

        assertNull(reuse.find(restricted.condition))
        assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
    }

    @Test
    fun `implicit integer activation bounds should survive a rejected support reduction`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val rows = mapOf("guarded" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE),
            emptyMap(), mapOf(integer to BigFraction.ZERO)))
        val guarded = SourceProofContext(scope, setOf(integer), rows)
        val reuse = LinearRefutationReuse(guarded, SourceLpBudget(), Cancellation.Never)
        val condition = SourceCondition(lower = mapOf(integer to BigFraction.ZERO),
            upper = mapOf(integer to BigFraction.ZERO))
        val derived = LinearPinnedRefutation(SourceDerivation.Premise("guarded"))

        reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, derived))

        assertNull(reuse.find(SourceCondition()))
        assertNotNull(reuse.find(condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseReductionRejected"])
    }

    @Test
    fun `changed source semantics and objective identities should reject retained evidence`() {
        val foreign = listOf(
            restricted.copy(revision = scope.revision.copy(identity = "edited-source")),
            restricted.copy(revision = scope.revision.copy(semantics = "tolerance")),
            restricted.copy(objective = checkNotNull(scope.objective).copy(identity = "edited-objective")),
            restricted.copy(objective = checkNotNull(scope.objective).copy(sense = ObjectiveSense.MAXIMIZE)),
        )

        for (otherScope in foreign) {
            val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
            reuse.retain(SourceEvidence(otherScope, SourceClaim.Refutation, proof))

            assertNull(reuse.find(restricted.condition))
        }
    }

    @Test
    fun `proofs should be freshly checked against rows with matching identifiers`() {
        val changed = SourceProofContext(scope, setOf(x, other), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val reuse = LinearRefutationReuse(changed, SourceLpBudget(), Cancellation.Never)

        reuse.retain(evidence)

        assertNull(reuse.find(restricted.condition))
    }

    @Test
    fun `objective bounds should not enter refutation reuse`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)

        reuse.retain(SourceEvidence(restricted, SourceClaim.Bound(BigFraction.ONE), proof))

        assertNull(reuse.find(restricted.condition))
    }

    @Test
    fun `cancelled import verification should preserve the retained alternative for a later check`() {
        var polls = 0
        var stopAfter = Int.MAX_VALUE
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation { ++polls >= stopAfter })
        reuse.retain(evidence)
        polls = 0
        stopAfter = 20

        assertNull(reuse.find(restricted.condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseRetained"])
        polls = 0
        stopAfter = Int.MAX_VALUE
        assertNotNull(reuse.find(restricted.condition))
    }

    @Test
    fun `an exhausted cumulative grant should withhold reuse without discarding a proof`() {
        val funding = SourceLpFunding(1_000_000L, 1_000_000L).apply { grant(1_000_000L) }
        val budget = SourceLpBudget(funding = funding)
        val reuse = LinearRefutationReuse(source, budget, Cancellation.Never)
        reuse.retain(evidence)
        funding.grant(0L)

        assertNull(reuse.find(restricted.condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseRetained"])
        assertTrue(budget.measuredConflictCertificationWork > 0L)
        assertTrue(funding.allocated > 0L)
    }

    @Test
    fun `reuse allocation admission should retire the cache before exhausting numerical funding`() {
        for (allocation in listOf(32_768L, 1_000_000L)) {
            val funding = SourceLpFunding(1_000_000L, allocation).apply { grant(1_000_000L) }
            val reuse = LinearRefutationReuse(source, SourceLpBudget(funding = funding), Cancellation.Never,
                maximumAllocation = 8192L)
            reuse.retain(evidence)
            assertNotNull(reuse.find(restricted.condition))

            repeat(16) { reuse.find(restricted.condition) }

            assertNull(reuse.find(restricted.condition))
            assertEquals(1L, reuse.observations()["numericalRefutationReuseAllocationStops"])
            assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
            assertTrue(funding.allocated <= minOf(8192L, allocation / 8L))
            assertFalse(funding.exhausted)
        }
    }

    @Test
    fun `source snapshot admission should charge every premise before a work pause`() {
        val rows = source.premises + (0 until 1000).associate {
            "unused/$it" to SourcePremise(SourceInequality(emptyMap(), BigFraction.ZERO))
        }
        val context = SourceProofContext(scope, source.coordinates, rows)
        val funding = SourceLpFunding(1_000_000L, 16_000_000L).apply { grant(32L) }
        val budget = SourceLpBudget(funding = funding)
        val reuse = LinearRefutationReuse(context, budget, Cancellation.Never)

        reuse.retain(evidence)

        assertTrue(budget.measuredConflictCertificationWork >= rows.size)
        assertNull(reuse.find(restricted.condition))
        assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
    }

    @Test
    fun `source metadata scanning should stop at its scratch allocation allowance`() {
        val rows = source.premises + (0 until 100).associate {
            "unused/$it" to SourcePremise(SourceInequality(emptyMap(), BigFraction.ZERO))
        }
        val context = SourceProofContext(scope, source.coordinates, rows)
        val funding = SourceLpFunding(1_000_000L, 96_000L).apply { grant(1_000_000L) }
        val reuse = LinearRefutationReuse(context, SourceLpBudget(funding = funding), Cancellation.Never,
            maximumAllocation = 6_000L)

        reuse.retain(evidence)

        assertFalse(funding.exhausted)
        assertTrue(funding.allocated <= 6_000L)
        assertNull(reuse.find(restricted.condition))
        assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
    }

    @Test
    fun `entry eviction should keep the newer proof and charge cleanup work`() {
        val budget = SourceLpBudget()
        val reuse = LinearRefutationReuse(source, budget, Cancellation.Never, maximumEntries = 1)
        reuse.retain(evidence)
        val stronger = scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.MINUS_ONE)))
        reuse.retain(SourceEvidence(stronger, SourceClaim.Refutation, proof))

        assertNull(reuse.find(SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        assertNotNull(reuse.find(stronger.condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseEvictions"])
        val before = budget.measuredConflictCertificationWork
        reuse.clear()
        assertTrue(budget.measuredConflictCertificationWork > before)
        assertEquals(0L, reuse.observations()["numericalRefutationReuseBytes"])
    }

    @Test
    fun `proofs exceeding the retention allocation should leave all queries open`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never, maximumBytes = 64L)

        reuse.retain(evidence)

        assertNull(reuse.find(restricted.condition))
        assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
    }

    @Test
    fun `source lifecycle should distinguish a checked lookup from consumed pruning`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
        reuse.retain(evidence)
        assertNotNull(reuse.find(restricted.condition))
        assertNull(reuse.observations()["learningSourceRefutationConsumed"])

        assertNotNull(reuse.find(restricted.condition, consume = true))
        assertNotNull(reuse.find(restricted.condition, consume = true))
        reuse.clear()

        val report = reuse.observations()
        assertEquals(1L, report["learningSourceRefutationConstructed"])
        assertEquals(1L, report["learningSourceRefutationAdmitted"])
        assertEquals(1L, report["learningSourceRefutationAsserted"])
        assertEquals(1L, report["learningSourceRefutationConsumed"])
        assertEquals(1L, report["learningSourceRefutationRetired"])
    }
}
