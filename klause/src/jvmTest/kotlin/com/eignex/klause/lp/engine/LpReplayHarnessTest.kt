package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpReplayHarnessTest {
    @Test
    fun `bounded replay utility persists and reports the fixed manifest`() {
        val manifest = LpWave0ReplaySlice.workloads()
        val directory = Files.createTempDirectory("lp-replay-w04-")
        val reports = try {
            manifest.map { workload ->
                val file = directory.resolve("${workload.name}.klpc")
                Files.write(file, workload.capture.encode())
                LpReplay.replay(LpCapture.decode(Files.readAllBytes(file)), IndependentExactValidator)
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }

        val allSteps = reports.flatMap { it.steps }
        assertEquals(manifest.size, reports.size)
        assertTrue(
            allSteps.none { it.independentCheck.validation == LpIndependentValidation.REFUTED },
            allSteps.joinToString {
                "${it.operation}:${it.candidate}:${it.productionVerdict}:${it.independentCheck.validation}"
            },
        )
        assertTrue(allSteps.any { it.independentCheck.claim == LpIndependentClaim.PROVED_OPTIMUM })
        assertTrue(allSteps.any { it.independentCheck.claim == LpIndependentClaim.PROVED_INFEASIBLE })
        assertTrue(allSteps.any { it.hasFeasibleWitness })
        assertTrue(allSteps.any { it.independentCheck.claim == LpIndependentClaim.CERTIFIED_BOUND })
        assertTrue(allSteps.any { it.productionVerdict == LpVerdict.INDETERMINATE })

        println(reportLine(reports))
    }

    @Test
    fun `replay validator compares integer models with the continuous relaxation`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(2L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val settings = LpReplaySettings(
            label = "integer-relaxation",
            seed = LpWave0ReplaySlice.SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
        )

        val step = LpReplay.replay(
            LpCapture.capture(model, settings, listOf(LpReplayEvent.Solve())),
            IndependentExactValidator,
        ).steps.single()

        assertEquals(0.5, Double.fromBits(checkNotNull(step.objectiveBits)))
        assertEquals(1L, step.exactLowerBound)
        assertEquals(LpIndependentValidation.VALIDATED, step.independentCheck.validation)
        assertEquals(LpIndependentClaim.CERTIFIED_BOUND, step.independentCheck.claim)

        val integerOptimumCandidate = LpReplayStep(
            step.eventIndex,
            step.operation,
            step.candidate,
            step.productionVerdict,
            1.0.toRawBits(),
            step.primalBits,
            step.exactLowerBound,
            step.hasFeasibleWitness,
            step.hasCertifiedBound,
            step.hasInfeasibilityProof,
            step.metrics,
            step.certifiers,
            step.exactInputAttempts,
            step.exactInputAccepted,
            step.certificationCapability,
            step.enforcedRows,
        )
        assertEquals(
            LpIndependentValidation.REFUTED,
            IndependentExactValidator.validate(model, integerOptimumCandidate).validation,
        )
    }

    @Test
    fun `unattained strict infimum validates only the feasible witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)
        val settings = LpReplaySettings(
            label = "strict-infimum",
            seed = LpWave0ReplaySlice.SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
        )

        val step = LpReplay.replay(
            LpCapture.capture(model, settings, listOf(LpReplayEvent.Solve())),
            IndependentExactValidator,
        ).steps.single()

        assertEquals(LpIndependentValidation.VALIDATED, step.independentCheck.validation)
        assertEquals(LpIndependentClaim.FEASIBLE_WITNESS, step.independentCheck.claim)
    }

    private fun reportLine(reports: List<LpReplayReport>): String {
        val steps = reports.flatMap { it.steps }
        val verdicts = LpVerdict.entries.joinToString(",") { verdict ->
            "$verdict=${steps.count { it.productionVerdict == verdict }}"
        }
        val validations = LpIndependentValidation.entries.joinToString(",") { validation ->
            "$validation=${steps.count { it.independentCheck.validation == validation }}"
        }
        val totalPivots = reports.sumOf { it.pivots }
        val totalWork = reports.sumOf { it.workOps }
        return "LP_REPLAY label=${LpWave0ReplaySlice.LABEL} seed=${LpWave0ReplaySlice.SEED} " +
            "workloads=${reports.size} " +
            "solver=persistent componentSplit=false refactorUpdateLimit=$DEFAULT_REFACTOR_UPDATE_LIMIT " +
            "budgets=per-workload cache=none verdicts=[$verdicts] " +
            "validations=[$validations] pivots=$totalPivots workOps=$totalWork " +
            "persistence=correctness-only"
    }

    internal object IndependentExactValidator : LpReplayValidator {
        override fun validate(model: LpModel, step: LpReplayStep): LpIndependentCheck {
            if (step.productionVerdict == null || step.certificationCapability == LpCertificationCapability.NO_CLAIM) {
                return LpIndependentCheck(LpIndependentValidation.DECLINED, LpIndependentClaim.NONE)
            }
            return when (val reference = LpReferenceAdapter().solve(model, step.enforcedRows)) {
                is LpReferenceResult.Declined ->
                    LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))

                LpReferenceResult.Infeasible -> validateInfeasible(step)

                is LpReferenceResult.Feasible -> validateFeasible(model, step, reference)
            }
        }

        private fun validateInfeasible(step: LpReplayStep): LpIndependentCheck {
            if (step.certificationCapability == LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE) {
                return LpIndependentCheck(
                    if (step.candidate == LpCandidateKind.FLOAT_INFEASIBILITY) {
                        LpIndependentValidation.VALIDATED
                    } else {
                        LpIndependentValidation.REFUTED
                    },
                    LpIndependentClaim.CANDIDATE_HINT,
                )
            }
            return if (step.productionVerdict == LpVerdict.INFEASIBLE && step.hasInfeasibilityProof) {
                LpIndependentCheck(LpIndependentValidation.VALIDATED, LpIndependentClaim.PROVED_INFEASIBLE)
            } else {
                LpIndependentCheck(LpIndependentValidation.REFUTED, candidateClaim(step))
            }
        }

        private fun validateFeasible(
            model: LpModel,
            step: LpReplayStep,
            reference: LpReferenceResult.Feasible,
        ): LpIndependentCheck {
            if (step.productionVerdict == LpVerdict.INFEASIBLE) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.PROVED_INFEASIBLE)
            }
            if (step.hasFeasibleWitness &&
                LpReferenceAdapter().accepts(model, step.primalBits, step.enforcedRows) != true
            ) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.FEASIBLE_WITNESS)
            }
            val expected = reference.objective as? LpReferenceObjective.Bound ?: return when (reference.objective) {
                LpReferenceObjective.Unbounded -> LpIndependentCheck(
                    if (step.productionVerdict == LpVerdict.OPTIMAL) {
                        LpIndependentValidation.REFUTED
                    } else {
                        LpIndependentValidation.DECLINED
                    },
                    candidateClaim(step),
                )

                LpReferenceObjective.ProbeBoundDecline ->
                    LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))

                is LpReferenceObjective.Bound -> error("bound handled above")
            }
            val lower = step.exactLowerBound
            if (lower != null && exceedsCeiling(lower, expected.lower)) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.CERTIFIED_BOUND)
            }
            val objective = step.objectiveBits?.let(Double::fromBits)?.let(BigFraction::ofDouble)
                ?: return LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
            if (step.candidate == LpCandidateKind.FLOAT_OPTIMUM && objective != expected.lower) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, candidateClaim(step))
            }
            if (step.certificationCapability == LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE) {
                return LpIndependentCheck(
                    if (step.candidate == LpCandidateKind.FLOAT_OPTIMUM) {
                        LpIndependentValidation.VALIDATED
                    } else {
                        LpIndependentValidation.REFUTED
                    },
                    LpIndependentClaim.CANDIDATE_HINT,
                )
            }
            val claim = when {
                step.hasFeasibleWitness && expected.attained -> LpIndependentClaim.PROVED_OPTIMUM
                step.hasFeasibleWitness -> LpIndependentClaim.FEASIBLE_WITNESS
                step.hasCertifiedBound -> LpIndependentClaim.CERTIFIED_BOUND
                else -> LpIndependentClaim.CANDIDATE_HINT
            }
            val validation = if (step.productionVerdict == LpVerdict.OPTIMAL ||
                step.hasFeasibleWitness || step.hasCertifiedBound
            ) {
                LpIndependentValidation.VALIDATED
            } else {
                LpIndependentValidation.DECLINED
            }
            return LpIndependentCheck(validation, claim)
        }

        private fun exceedsCeiling(value: Long, reference: BigFraction): Boolean =
            value != Long.MIN_VALUE && BigFraction.ofLong(value - 1L) >= reference

        private fun candidateClaim(step: LpReplayStep): LpIndependentClaim = when {
            step.hasInfeasibilityProof -> LpIndependentClaim.PROVED_INFEASIBLE
            step.hasFeasibleWitness -> LpIndependentClaim.FEASIBLE_WITNESS
            step.hasCertifiedBound -> LpIndependentClaim.CERTIFIED_BOUND
            step.candidate != LpCandidateKind.NONE -> LpIndependentClaim.CANDIDATE_HINT
            else -> LpIndependentClaim.NONE
        }
    }

    private companion object {
        init {
            val model = LpBuilder().build(Sense.MINIMIZE)
            val settings = LpReplaySettings(
                "w0.4-warmup",
                LpWave0ReplaySlice.SEED,
                LpReplaySolverKind.PERSISTENT,
                componentSplit = false,
            )
            LpReplay.replay(LpCapture.capture(model, settings, listOf(LpReplayEvent.Solve())))
        }
    }
}
