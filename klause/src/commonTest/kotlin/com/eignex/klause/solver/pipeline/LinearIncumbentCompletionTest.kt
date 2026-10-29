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
    fun `granting an exhausted containing allowance should not restart completion`() {
        val funding = SourceLpFunding(1L, 64L * 1024L * 1024L)
        var attempts = 0L

        LinearIncumbentCompletion(SourceLpBudget(funding = funding)) { stage, count ->
            if (stage == "Attempts") attempts += count
        }.use { completion ->
            repeat(3) {
                funding.grant(2_000_000L)
                assertNull(completion.complete(source, seed, Cancellation.Never))
            }
        }

        assertTrue(funding.exhausted)
        assertEquals(0L, attempts)
    }
}
