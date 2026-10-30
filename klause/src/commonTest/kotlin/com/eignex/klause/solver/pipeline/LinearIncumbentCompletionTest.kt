package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceSemanticsChecker
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearIncumbentCompletionTest {
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("incumbent", "exact"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))
    private val third = BigFraction.ofLong(3).reciprocal()
    private val source = SourceProofContext(scope, setOf(integer, real), mapOf(
        "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ofLong(3),
            integer to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ofLong(-3),
            integer to BigFraction.ONE), BigFraction.ZERO)),
    ), mapOf(real to BigFraction.ONE), objectiveConstant = BigFraction.ONE)
    private val seed = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.333))

    @Test
    fun `companions should reuse an original checked completion of the same full point`() {
        val shared = LinearIncumbentSharing()
        var checks = 0
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> checks++; true }
        val producerBudget = SourceLpBudget(maxOperations = 0)
        val budget = SourceLpBudget(maxOperations = 0)
        val offered = seed.copy(reals = doubleArrayOf(third.toDouble()))

        LinearIncumbentCompletion(producerBudget, originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, offered, Cancellation.Never))
            LinearIncumbentCompletion(budget, originalCheck, shared) { _, _ -> }.use { consumer ->
                val point = assertNotNull(consumer.complete(source, offered.copy(), Cancellation.Never))

                assertEquals(listOf(third), point.exactReals)
                assertEquals(1, checks)
                assertEquals(0L, budget.measuredRefinementWork)
            }
        }
    }

    @Test
    fun `shared lookup and snapshots should spend consumer funding without charging the producer`() {
        val shared = LinearIncumbentSharing()
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> true }
        val producerFunding = SourceLpFunding(2_000_000L, 64L * 1024L * 1024L).also { it.grant(2_000_000L) }
        val consumerFunding = SourceLpFunding(2_000_000L, 64L * 1024L * 1024L).also { it.grant(2_000_000L) }

        LinearIncumbentCompletion(SourceLpBudget(funding = producerFunding), originalCheck, shared) { _, _ -> }
            .use { producer ->
                assertNotNull(producer.complete(source, seed, Cancellation.Never))
                val spent = producerFunding.spent
                LinearIncumbentCompletion(SourceLpBudget(funding = consumerFunding), originalCheck, shared) { _, _ -> }
                    .use { consumer -> assertNotNull(consumer.complete(source, seed, Cancellation.Never)) }

                assertTrue(consumerFunding.spent > 0L)
                assertTrue(consumerFunding.allocated > 0L)
                assertEquals(spent, producerFunding.spent)
            }
    }

    @Test
    fun `shared completions should not cross a changed original checker`() {
        val shared = LinearIncumbentSharing()
        var rejectedChecks = 0
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> true }

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, seed, Cancellation.Never))
            LinearIncumbentCompletion(SourceLpBudget(), { _, _, _ -> rejectedChecks++; false }, shared) { _, _ -> }
                .use { consumer -> assertNull(consumer.complete(source, seed, Cancellation.Never)) }
        }

        assertEquals(1, rejectedChecks)
    }

    @Test
    fun `shared completions should not cross changed source restrictions or rows`() {
        val contexts = listOf(
            SourceProofContext(scope.copy(condition = scope.condition.bound(integer, BigFraction.ZERO, true)),
                source.coordinates, source.premises, source.objective, source.objectiveConstant),
            SourceProofContext(scope, source.coordinates, source.premises +
                ("restricted" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO))),
                source.objective, source.objectiveConstant),
        )
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> true }
        val shared = LinearIncumbentSharing()

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, seed, Cancellation.Never))
            for (context in contexts) {
                LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { consumer ->
                    assertNull(consumer.complete(context, seed, Cancellation.Never))
                }
            }
        }
    }

    @Test
    fun `shared completion should retain the offered coordinate frame identity`() {
        val shared = LinearIncumbentSharing()
        var checks = 0
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> checks++; true }

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, seed.withCoordinateFrame(Any()), Cancellation.Never))
            LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { consumer ->
                assertNotNull(consumer.complete(source, seed.withCoordinateFrame(Any()), Cancellation.Never))
            }
        }

        assertEquals(2, checks)
    }

    @Test
    fun `shared completion should not bypass an unfunded consumer snapshot`() {
        val shared = LinearIncumbentSharing()
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> true }
        val funding = SourceLpFunding(2_000_000L, 100L).also { it.grant(2_000_000L) }

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, seed, Cancellation.Never))
            LinearIncumbentCompletion(SourceLpBudget(funding = funding), originalCheck, shared) { _, _ -> }
                .use { consumer -> assertNull(consumer.complete(source, seed, Cancellation.Never)) }
        }

        assertEquals(0L, funding.allocated)
    }

    @Test
    fun `shared completion should reserve its retained exact scalar payload`() {
        val shared = LinearIncumbentSharing()
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> true }
        val funding = SourceLpFunding(2_000_000L, 672L).also { it.grant(2_000_000L) }

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, seed, Cancellation.Never))
            LinearIncumbentCompletion(SourceLpBudget(maxOperations = 0, funding = funding), originalCheck, shared)
                { _, _ -> }.use { consumer -> assertNull(consumer.complete(source, seed, Cancellation.Never)) }
        }
    }

    @Test
    fun `closing a producer should release its shared completions`() {
        val shared = LinearIncumbentSharing()
        var checks = 0
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> checks++; true }

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { producer ->
            assertNotNull(producer.complete(source, seed, Cancellation.Never))
        }
        LinearIncumbentCompletion(SourceLpBudget(), originalCheck, shared) { _, _ -> }.use { consumer ->
            assertNotNull(consumer.complete(source, seed, Cancellation.Never))
        }

        assertEquals(2, checks)
    }

    @Test
    fun `full shared retention should preserve older points without refusing fresh completion`() {
        val shared = LinearIncumbentSharing()
        var checks = 0
        val originalCheck: (Sample, BigFraction, Cancellation) -> Boolean = { _, _, _ -> checks++; true }
        val first = Sample(booleanArrayOf(), longArrayOf(3), doubleArrayOf(1.0))
        val producerBudget = SourceLpBudget(maxOperations = 0)

        LinearIncumbentCompletion(producerBudget, originalCheck, shared) { _, _ -> }.use { producer ->
            for (value in 1L..16L) {
                val offered = Sample(booleanArrayOf(), longArrayOf(3 * value), doubleArrayOf(value.toDouble()))
                assertNotNull(producer.complete(source, offered, Cancellation.Never))
            }
            LinearIncumbentCompletion(SourceLpBudget(maxOperations = 0), originalCheck, shared) { _, _ -> }
                .use { consumer ->
                    assertNotNull(consumer.complete(source,
                        Sample(booleanArrayOf(), longArrayOf(51), doubleArrayOf(17.0)), Cancellation.Never))
                    assertNotNull(consumer.complete(source, first, Cancellation.Never))
                }
        }

        assertEquals(17, checks)
    }

    @Test
    fun `an advisory continuous point should reconstruct without a residual LP operation`() {
        val offered = seed.copy(reals = doubleArrayOf(third.toDouble()))
        val funding = SourceLpFunding(2_000_000L, 64L * 1024L * 1024L).also { it.grant(2_000_000L) }
        val budget = SourceLpBudget(maxOperations = 0, funding = funding)
        var originalChecks = 0

        val point = LinearIncumbentCompletion(budget, originalCheck = { _, value, _ ->
            originalChecks++
            value == BigFraction.ONE + third
        }) { _, _ -> }.use {
            assertNotNull(it.complete(source, offered, Cancellation.Never))
        }

        assertEquals(listOf(third), point.exactReals)
        assertEquals(1, originalChecks)
        assertEquals(0, budget.operations)
        assertTrue(funding.spent > 0L)
        assertTrue(funding.allocated > 0L)
    }

    @Test
    fun `unusable advisory continuous coordinates should retain residual completion`() {
        for (value in listOf(0.333, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val offered = seed.copy(reals = doubleArrayOf(value))

            val point = LinearIncumbentCompletion(SourceLpBudget()) { _, _ -> }.use {
                assertNotNull(it.complete(source, offered, Cancellation.Never))
            }

            assertEquals(listOf(third), point.exactReals)
        }
    }

    @Test
    fun `a reconstructed continuous point should still require its original source check`() {
        val offered = seed.copy(reals = doubleArrayOf(third.toDouble()))
        var originalChecks = 0

        LinearIncumbentCompletion(SourceLpBudget(maxOperations = 0), originalCheck = { _, _, _ ->
            originalChecks++
            false
        }) { _, _ -> }.use {
            assertNull(it.complete(source, offered, Cancellation.Never))
            assertNull(it.complete(source, offered.copy(), Cancellation.Never))
        }

        assertEquals(1, originalChecks)
    }

    @Test
    fun `advisory discrete coordinates should complete with an exact original objective`() {
        val funding = SourceLpFunding(2_000_000L, 64L * 1024L * 1024L).also { it.grant(2_000_000L) }
        val budget = SourceLpBudget(funding = funding)

        val point = LinearIncumbentCompletion(budget) { _, _ -> }.use {
            assertNotNull(it.complete(source, seed, Cancellation.Never))
        }

        assertEquals(seed.intValue(0), point.intValue(0))
        assertEquals(listOf(third), point.exactReals)
        assertEquals(BigFraction.ONE + third, source.linearValue(point, Cancellation.Never))
        assertTrue(budget.measuredPreparationWork > 0L)
        assertTrue(budget.measuredRefinementWork > 0L)
        assertTrue(budget.measuredBoundCertificationWork > 0L)
        assertTrue(funding.allocated > 0L)
    }

    @Test
    fun `an exact payload should survive an exhausted advisory allowance unchanged`() {
        val point = seed.copy(reals = doubleArrayOf(third.toDouble()), exactReals = listOf(third))
        val budget = SourceLpBudget(funding = SourceLpFunding(0L, 0L))

        LinearIncumbentCompletion(budget) { _, _ -> }.use {
            assertSame(point, it.complete(source, point, Cancellation.Never))
        }
    }

    @Test
    fun `oversized advisory integers should be refused before retaining their payload`() {
        val wide = bigIntOf(1) shl 4096
        val offered = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactInts = listOf(wide))
        var attempts = 0L
        var declines = 0L

        LinearIncumbentCompletion(SourceLpBudget()) { stage, count ->
            if (stage == "Attempts") attempts += count
            if (stage == "SeedScalarLimit") declines += count
        }.use { completion ->
            repeat(2) { assertNull(completion.complete(source, offered, Cancellation.Never)) }
        }

        assertEquals(0L, attempts)
        assertEquals(2L, declines)
    }

    @Test
    fun `an unfunded seed snapshot should not consume retained attempt capacity`() {
        val funding = SourceLpFunding(2_000_000L, 100L).also { it.grant(2_000_000L) }
        var attempts = 0L

        LinearIncumbentCompletion(SourceLpBudget(funding = funding)) { stage, count ->
            if (stage == "Attempts") attempts += count
        }.use { completion ->
            repeat(2) { assertNull(completion.complete(source, seed, Cancellation.Never)) }
        }

        assertEquals(0L, attempts)
        assertEquals(0L, funding.allocated)
    }

    @Test
    fun `failed completion should reuse copied points after an intervening source context`() {
        val rows = mapOf("negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE),
            BigFraction.ZERO)))
        val contexts = listOf(
            SourceProofContext(scope, setOf(integer, real), rows, source.objective),
            SourceProofContext(scope, setOf(integer, real), rows, source.objective, BigFraction.ONE),
        )
        val stages = mutableListOf<String>()
        LinearIncumbentCompletion(SourceLpBudget()) { stage, _ -> stages += stage }.use {
            for (context in contexts + contexts.first()) {
                assertNull(it.complete(context, seed.copy(), Cancellation.Never))
            }
        }

        assertEquals(2, stages.count { it == "Attempts" })
        assertEquals(1, stages.count { it == "DeclineReused" })
    }

    @Test
    fun `changed source rows should permit a previously refused discrete seed`() {
        val restricted = SourceProofContext(scope, source.coordinates, mapOf(
            "negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE), BigFraction.ZERO)),
        ), source.objective, source.objectiveConstant)

        LinearIncumbentCompletion(SourceLpBudget()) { _, _ -> }.use {
            assertNull(it.complete(restricted, seed, Cancellation.Never))
            val point = assertNotNull(it.complete(source, seed, Cancellation.Never))

            assertEquals(listOf(third), point.exactReals)
        }
    }

    @Test
    fun `failed completion identity should include source restrictions objective semantics and checker`() {
        val rejected = object : SourceSemanticsChecker {
            override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Rejected("source domain hole")
            override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Unavailable("no ray")
        }
        val rows = mapOf("negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE),
            BigFraction.ZERO)))
        val contexts = listOf(
            SourceProofContext(scope, source.coordinates, rows, source.objective),
            SourceProofContext(scope, source.coordinates, mapOf(
                "negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE), BigFraction.MINUS_ONE)),
            ), source.objective),
            SourceProofContext(scope.copy(revision = SourceRevision("other", "exact")), source.coordinates,
                rows, source.objective),
            SourceProofContext(scope.copy(condition = scope.condition.bound(integer, BigFraction.ZERO, true)),
                source.coordinates, rows, source.objective),
            SourceProofContext(scope.copy(objective = SourceObjective("other", ObjectiveSense.MAXIMIZE)),
                source.coordinates, rows, source.objective),
            SourceProofContext(scope, source.coordinates, rows, mapOf(real to BigFraction.MINUS_ONE)),
            SourceProofContext(scope, source.coordinates, rows, source.objective, BigFraction.ONE),
            SourceProofContext(scope, source.coordinates, rows, source.objective, semanticsChecker = rejected),
            SourceProofContext(scope, source.coordinates, rows, source.objective,
                semantics = SourceSemantics(SourceFormat.IR).resolve()),
        )
        var attempts = 0L
        LinearIncumbentCompletion(SourceLpBudget()) { stage, count -> if (stage == "Attempts") attempts += count }.use {
            for (context in contexts + contexts) assertNull(it.complete(context, seed, Cancellation.Never))
        }

        assertEquals(contexts.size.toLong(), attempts)
    }

    @Test
    fun `Boolean and integer seed pins should select their source guarded rows`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope, setOf(bool, integer, real), mapOf(
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), third),
                mapOf(0 to true), mapOf(integer to BigFraction.ONE)),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), third.negated()),
                mapOf(0 to true), mapOf(integer to BigFraction.ONE)),
        ), mapOf(real to BigFraction.ONE))
        val offered = Sample(booleanArrayOf(true), longArrayOf(1), doubleArrayOf(0.0))

        val point = LinearIncumbentCompletion(SourceLpBudget()) { _, _ -> }.use {
            assertNotNull(it.complete(guarded, offered, Cancellation.Never))
        }

        assertTrue(point.boolValue(0))
        assertEquals(1L, point.intValue(0))
        assertEquals(listOf(third), point.exactReals)
    }

    @Test
    fun `an original objective rejection should remain distinct from a completed working point`() {
        var originalChecks = 0
        var checked = 0L

        LinearIncumbentCompletion(SourceLpBudget(), originalCheck = { _, _, _ ->
            originalChecks++
            false
        }) { stage, count -> if (stage == "Checked") checked += count }.use {
            assertNull(it.complete(source, seed, Cancellation.Never))
            assertNull(it.complete(source, seed.copy(), Cancellation.Never))
        }

        assertEquals(1, originalChecks)
        assertEquals(0L, checked)
    }

    @Test
    fun `original source rejection should withhold an otherwise exact completion`() {
        val checker = object : SourceSemanticsChecker {
            override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Rejected("source domain hole")
            override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Unavailable("no ray")
        }
        val original = SourceProofContext(scope, source.coordinates, source.premises, source.objective,
            semanticsChecker = checker)
        val stages = mutableListOf<String>()

        LinearIncumbentCompletion(SourceLpBudget()) { stage, _ -> stages += stage }.use {
            assertNull(it.complete(original, seed, Cancellation.Never))
            assertNull(it.complete(original, seed.copy(), Cancellation.Never))
        }

        assertEquals(1, stages.count { it == "Rejected" })
        assertEquals(1, stages.count { it == "DeclineReused" })
    }

    @Test
    fun `cancellation during original checking should publish no point and permit a later resume`() {
        var cancelled = false
        var interrupt = true
        val checker = object : SourceSemanticsChecker {
            override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck {
                if (interrupt) cancelled = true
                return if (cancellation()) SourceEvidenceCheck.Unavailable("cancelled") else SourceEvidenceCheck.Checked
            }
            override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation) =
                SourceEvidenceCheck.Unavailable("no ray")
        }
        val original = SourceProofContext(scope, source.coordinates, source.premises, source.objective,
            semanticsChecker = checker)

        LinearIncumbentCompletion(SourceLpBudget()) { _, _ -> }.use {
            assertNull(it.complete(original, seed, Cancellation { cancelled }))
            cancelled = false
            interrupt = false
            assertNotNull(it.complete(original, seed, Cancellation { cancelled }))
        }
    }

    @Test
    fun `bounded retention should refuse fresh seeds without retrying exhausted failures`() {
        val impossible = SourceProofContext(scope, source.coordinates, mapOf(
            "negative" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE), BigFraction.ZERO)),
        ), source.objective)
        var attempts = 0L
        var reused = 0L
        var exhausted = 0L

        LinearIncumbentCompletion(SourceLpBudget()) { stage, count ->
            when (stage) {
                "Attempts" -> attempts += count
                "DeclineReused" -> reused += count
                "RetentionExhausted" -> exhausted += count
            }
        }.use {
            repeat(17) { id ->
                assertNull(it.complete(impossible, seed.copy(reals = doubleArrayOf(id.toDouble())), Cancellation.Never))
            }
            assertNull(it.complete(impossible, seed.copy(reals = doubleArrayOf(0.0)), Cancellation.Never))
        }

        assertEquals(16L, attempts)
        assertEquals(1L, exhausted)
        assertEquals(1L, reused)
    }

    @Test
    fun `an exhausted completion should not restart after another funding grant`() {
        val funding = SourceLpFunding(2_000_000L, 64L * 1024L * 1024L)
        var attempts = 0L
        var recoveries = 0L
        var reused = 0L

        LinearIncumbentCompletion(SourceLpBudget(maxOperations = 0, funding = funding)) { stage, count ->
            when (stage) {
                "Attempts" -> attempts += count
                "PrimalRecoveryAttempts" -> recoveries += count
                "DeclineReused" -> reused += count
            }
        }.use { completion ->
            repeat(3) {
                funding.grant(2_000_000L)
                assertNull(completion.complete(source, seed, Cancellation.Never))
            }
        }

        assertTrue(funding.spent > 0L)
        assertEquals(1L, attempts)
        assertEquals(1L, recoveries)
        assertEquals(2L, reused)
    }
}
