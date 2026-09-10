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
        assertEquals(1L, step.integerObjectiveLowerBound)
        assertEquals(LpIndependentValidation.VALIDATED, step.independentCheck.validation)
        assertEquals(LpIndependentClaim.PROVED_OPTIMUM, step.independentCheck.claim)

        val integerOptimumCandidate = LpReplayStep(
            step.eventIndex,
            step.operation,
            step.candidate,
            step.productionVerdict,
            1.0.toRawBits(),
            step.primalBits,
            step.integerObjectiveLowerBound,
            step.hasFeasibleWitness,
            step.hasCertifiedBound,
            step.hasInfeasibilityProof,
            step.metrics,
            step.certifiers,
            step.exactInputAttempts,
            step.exactInputAccepted,
            step.certificationCapability,
            step.enforcedRows,
            exactWitness = step.exactWitness,
            rationalLowerBound = BigFraction.ONE,
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

    @Test
    fun `nonoptimal feasible witness is not upgraded by an attained reference optimum`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.GE, 1.0)
        }.build(Sense.MINIMIZE)

        listOf(LpCandidateKind.FLOAT_OPTIMUM, LpCandidateKind.FLOAT_BOUND).forEach { candidate ->
            val check = IndependentExactValidator.validate(
                model,
                fabricatedStep(candidate, objective = 0.5, primal = 1.0),
            )

            assertEquals(LpIndependentValidation.VALIDATED, check.validation, candidate.name)
            assertEquals(LpIndependentClaim.FEASIBLE_WITNESS, check.claim, candidate.name)
        }
    }

    @Test
    fun `unbounded objective preserves a valid feasibility witness`() {
        val model = LpBuilder().apply {
            addOpenAboveVar(0L, cost = -1L)
        }.build(Sense.MINIMIZE)

        val check = IndependentExactValidator.validate(
            model,
            fabricatedStep(LpCandidateKind.NONE, objective = null, primal = 0.0),
        )

        assertEquals(LpIndependentValidation.VALIDATED, check.validation)
        assertEquals(LpIndependentClaim.FEASIBLE_WITNESS, check.claim)
    }

    @Test
    fun `an infeasible reference does not refute a certified lower bound`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val step = fabricatedStep(
            LpCandidateKind.FLOAT_BOUND,
            objective = null,
            primal = 0.0,
            verdict = LpVerdict.CERTIFIED_BOUND,
            lowerBound = BigFraction.ofLong(2L),
            hasWitness = false,
        )

        val check = IndependentExactValidator.validate(model, step)

        assertEquals(LpIndependentValidation.VALIDATED, check.validation)
        assertEquals(LpIndependentClaim.CERTIFIED_BOUND, check.claim)
    }

    @Test
    fun `an infeasible reference leaves an evidence free indeterminate result unresolved`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val step = fabricatedStep(
            LpCandidateKind.NONE,
            objective = null,
            primal = 0.0,
            verdict = LpVerdict.INDETERMINATE,
            hasWitness = false,
        )

        val check = IndependentExactValidator.validate(model, step)

        assertEquals(LpIndependentValidation.DECLINED, check.validation)
        assertEquals(LpIndependentClaim.NONE, check.claim)
    }

    @Test
    fun `a bounded reference refutes an unbounded claim despite a feasible witness`() {
        val model = LpBuilder().apply { addVar(0L, 1L, cost = 1L) }.build(Sense.MINIMIZE)
        val step = fabricatedStep(LpCandidateKind.NONE, null, 0.0, verdict = LpVerdict.UNBOUNDED)

        val check = IndependentExactValidator.validate(model, step)

        assertEquals(LpIndependentValidation.REFUTED, check.validation)
    }

    @Test
    fun `an unbounded reference cannot validate a missing recession proof`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)
        val step = fabricatedStep(LpCandidateKind.NONE, null, 0.0, verdict = LpVerdict.UNBOUNDED)

        val check = IndependentExactValidator.validate(model, step)

        assertEquals(LpIndependentValidation.DECLINED, check.validation)
    }

    private fun fabricatedStep(
        candidate: LpCandidateKind,
        objective: Double?,
        primal: Double,
        verdict: LpVerdict = LpVerdict.FEASIBLE,
        lowerBound: BigFraction? = null,
        hasWitness: Boolean = true,
    ): LpReplayStep = LpReplayStep(
        eventIndex = 0,
        operation = LpReplayOperation.SOLVE,
        candidate = candidate,
        productionVerdict = verdict,
        objectiveBits = objective?.toRawBits(),
        primalBits = longArrayOf(primal.toRawBits()),
        integerObjectiveLowerBound = null,
        hasFeasibleWitness = hasWitness,
        hasCertifiedBound = lowerBound != null,
        hasInfeasibilityProof = false,
        metrics = LpSolveMetrics(),
        certifiers = emptyList(),
        exactInputAttempts = 0,
        exactInputAccepted = 0,
        rationalLowerBound = lowerBound,
    )

    internal object IndependentExactValidator : LpReplayValidator {
        override fun validate(model: LpModel, step: LpReplayStep): LpIndependentCheck {
            if (step.productionVerdict == null || step.certificationCapability == LpCertificationCapability.NO_CLAIM) {
                return LpIndependentCheck(LpIndependentValidation.DECLINED, LpIndependentClaim.NONE)
            }
            val adapter = LpReferenceAdapter()
            return when (val reference = adapter.solve(model, step.enforcedRows)) {
                is LpReferenceResult.Declined ->
                    LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))

                LpReferenceResult.Infeasible -> validateInfeasible(step)

                is LpReferenceResult.Feasible -> validateFeasible(model, step, reference, adapter)
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
            if (step.productionVerdict == LpVerdict.INDETERMINATE &&
                step.candidate == LpCandidateKind.NONE && !step.hasFeasibleWitness &&
                !step.hasCertifiedBound && !step.hasInfeasibilityProof
            ) {
                return LpIndependentCheck(LpIndependentValidation.DECLINED, LpIndependentClaim.NONE)
            }
            if (step.productionVerdict == LpVerdict.CERTIFIED_BOUND &&
                step.hasCertifiedBound && step.rationalLowerBound != null && !step.hasFeasibleWitness
            ) {
                return LpIndependentCheck(LpIndependentValidation.VALIDATED, LpIndependentClaim.CERTIFIED_BOUND)
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
            adapter: LpReferenceAdapter,
        ): LpIndependentCheck {
            if (step.productionVerdict == LpVerdict.INFEASIBLE) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.PROVED_INFEASIBLE)
            }
            if (step.productionVerdict == LpVerdict.UNBOUNDED) {
                val validation = if (reference.objective is LpReferenceObjective.Bound) {
                    LpIndependentValidation.REFUTED
                } else {
                    // Replay does not retain an exact recession direction to check this proof strength.
                    LpIndependentValidation.DECLINED
                }
                return LpIndependentCheck(validation, LpIndependentClaim.NONE)
            }
            val witnessObjective = if (step.hasFeasibleWitness) {
                val valid = step.exactWitness?.let { adapter.acceptsExact(model, it, step.enforcedRows) }
                    ?: adapter.accepts(model, step.primalBits, step.enforcedRows)
                if (valid != true) {
                    return LpIndependentCheck(
                        LpIndependentValidation.REFUTED,
                        LpIndependentClaim.FEASIBLE_WITNESS,
                    )
                }
                checkNotNull(
                    step.exactWitness?.let { adapter.exactObjective(model, it, step.enforcedRows) }
                        ?: adapter.objective(model, step.primalBits, step.enforcedRows),
                )
            } else {
                null
            }
            val expected = reference.objective as? LpReferenceObjective.Bound ?: return when (reference.objective) {
                LpReferenceObjective.Unbounded -> when {
                    step.hasCertifiedBound -> LpIndependentCheck(
                        LpIndependentValidation.REFUTED,
                        LpIndependentClaim.CERTIFIED_BOUND,
                    )

                    witnessObjective != null -> LpIndependentCheck(
                        LpIndependentValidation.VALIDATED,
                        LpIndependentClaim.FEASIBLE_WITNESS,
                    )

                    step.candidate == LpCandidateKind.FLOAT_OPTIMUM -> LpIndependentCheck(
                        LpIndependentValidation.REFUTED,
                        LpIndependentClaim.CANDIDATE_HINT,
                    )

                    else -> LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
                }

                LpReferenceObjective.ProbeBoundDecline ->
                    LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))

                is LpReferenceObjective.Bound -> error("bound handled above")
            }
            val lower = step.rationalLowerBound
            if (lower != null && lower > expected.lower) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.CERTIFIED_BOUND)
            }
            if (step.productionVerdict == LpVerdict.ATTAINED_OPTIMUM &&
                (lower != witnessObjective || lower != expected.lower || !expected.attained)
            ) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.PROVED_OPTIMUM)
            }
            if (!step.hasFeasibleWitness && !step.hasCertifiedBound &&
                step.candidate == LpCandidateKind.FLOAT_OPTIMUM
            ) {
                val objective = step.objectiveBits?.let(Double::fromBits)?.let(BigFraction::ofDouble)
                    ?: return LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
                if (objective != expected.lower) {
                    return LpIndependentCheck(LpIndependentValidation.REFUTED, candidateClaim(step))
                }
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
                step.productionVerdict == LpVerdict.ATTAINED_OPTIMUM -> LpIndependentClaim.PROVED_OPTIMUM
                step.hasFeasibleWitness -> LpIndependentClaim.FEASIBLE_WITNESS
                step.hasCertifiedBound -> LpIndependentClaim.CERTIFIED_BOUND
                else -> candidateClaim(step)
            }
            val validation = if (step.candidate == LpCandidateKind.FLOAT_OPTIMUM ||
                step.hasFeasibleWitness || step.hasCertifiedBound
            ) {
                LpIndependentValidation.VALIDATED
            } else {
                LpIndependentValidation.DECLINED
            }
            return LpIndependentCheck(validation, claim)
        }

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
