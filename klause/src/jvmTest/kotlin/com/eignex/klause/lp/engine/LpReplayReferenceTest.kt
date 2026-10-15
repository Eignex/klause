package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LpWave0Workload(
    val name: String,
    val model: LpModel,
    private val settings: LpReplaySettings,
    private val events: List<LpReplayEvent>,
) {
    val capture: LpCapture get() = LpCapture.capture(model, settings, events)
}

private object LpWave0ReplaySlice {
    const val LABEL = "w0.4-replay-v1"
    const val SEED = 0x4c50573034L

    fun workloads(): List<LpWave0Workload> {
        val settings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
            pivotLimit = 0,
            workLimit = 0L,
            trackDegeneracy = false,
        )
        val empty = LpBuilder().build(Sense.MINIMIZE)

        val feasible = LpBuilder().apply {
            val x = addVar(0L, 10L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)

        val infeasible = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)

        val continuous = LpBuilder().apply {
            val x = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 3.0)
        }.build(Sense.MINIMIZE)

        val boundedSettings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            pivotLimit = 1,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
        )
        val cancelSettings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            cancellationPollLimit = 1,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
        )
        return listOf(
            LpWave0Workload("empty", empty, settings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload("feasible-bounded", feasible, settings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload(
                "infeasible-bounded",
                infeasible,
                settings,
                listOf(
                    LpReplayEvent.Solve(),
                    LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                    LpReplayEvent.ResolveGated(booleanArrayOf(true)),
                ),
            ),
            LpWave0Workload("cancelled", cancellationModel(), cancelSettings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload("continuous-witness", continuous, settings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload("pivot-bounded", coveringModel(), boundedSettings, listOf(LpReplayEvent.Solve())),
        )
    }

    private fun cancellationModel(): LpModel {
        val builder = LpBuilder()
        val columns = IntArray(32) { builder.addVar(0L, 9L, cost = (it % 5 + 1).toLong()) }
        repeat(16) { row ->
            builder.addRow(
                IntArray(4) { columns[(row * 3 + it) % columns.size] },
                LongArray(4) { 1L },
                Relation.GE,
                (row % 6 + 2).toLong(),
            )
        }
        return builder.build(Sense.MINIMIZE)
    }

    private fun coveringModel(): LpModel {
        val builder = LpBuilder()
        val variables = IntArray(4) { builder.addVar(0L, 3L, cost = 1L) }
        builder.addRow(intArrayOf(variables[0], variables[1]), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(variables[1], variables[2]), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(variables[2], variables[3]), longArrayOf(1L, 1L), Relation.GE, 5L)
        builder.addRow(intArrayOf(variables[0], variables[3]), longArrayOf(1L, 1L), Relation.GE, 2L)
        return builder.build(Sense.MINIMIZE)
    }
}

class LpReplayReferenceTest {
    @Test
    fun `the bounded replay utility should persist and report the fixed manifest`() {
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
    fun `an unattained strict infimum should keep the persistent bound and general witness separate`() {
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

        for (kind in listOf(LpReplaySolverKind.PERSISTENT, LpReplaySolverKind.GENERAL)) {
            val step = LpReplay.replay(
                LpCapture.capture(
                    model,
                    LpReplaySettings(settings.label, settings.seed, solverKind = kind, componentSplit = false),
                    listOf(LpReplayEvent.Solve()),
                ),
                IndependentExactValidator,
            ).steps.single()

            assertEquals(LpIndependentValidation.VALIDATED, step.independentCheck.validation)
            assertEquals(BigFraction.ZERO, step.rationalLowerBound)
            assertEquals(
                if (kind == LpReplaySolverKind.PERSISTENT) LpVerdict.CERTIFIED_BOUND else LpVerdict.FEASIBLE,
                step.productionVerdict,
            )
            assertEquals(
                if (kind == LpReplaySolverKind.PERSISTENT) {
                    LpIndependentClaim.CERTIFIED_BOUND
                } else {
                    LpIndependentClaim.FEASIBLE_WITNESS
                },
                step.independentCheck.claim,
            )
        }
    }

    @Test
    fun `rebound probe source optima outside the box should decline unsupported objective comparison`() {
        for (lowerClamped in listOf(false, true)) {
            val point = if (lowerClamped) -5L else 5L
            val model = LpBuilder().apply {
                val x = addFreeVar(
                    if (lowerClamped) null else 0L,
                    if (lowerClamped) 0L else null,
                    cost = if (lowerClamped) 1L else -1L,
                )
                addRow(intArrayOf(x), longArrayOf(1L), if (lowerClamped) Relation.GE else Relation.LE, point)
            }.build(Sense.MINIMIZE)
            val capture = LpCapture.capture(
                model,
                LpReplaySettings(
                    "probe-source-witness",
                    76L,
                    LpReplaySolverKind.PERSISTENT,
                    componentSplit = false,
                    pivotLimit = 100,
                    workLimit = 1_000_000L,
                    refactorUpdateLimit = 50,
                ),
                listOf(
                    LpReplayEvent.Rebind(
                        longArrayOf(if (lowerClamped) -3L else 0L),
                        longArrayOf(if (lowerClamped) 0L else 3L),
                    ),
                    LpReplayEvent.ResolveBounds(),
                ),
            )

            val step = LpReplay.replay(capture, IndependentExactValidator).steps.last()

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
            assertEquals(listOf(BigFraction.ofLong(point)), step.exactWitness)
            assertEquals(BigFraction.ofLong(-5L), step.rationalLowerBound)
            assertEquals(LpIndependentValidation.DECLINED, step.independentCheck.validation)
        }
    }

    private object IndependentExactValidator : LpReplayValidator {
        override fun validate(model: LpModel, step: LpReplayStep): LpIndependentCheck {
            if (step.productionVerdict == null || step.certificationCapability == LpCertificationCapability.NO_CLAIM) {
                return LpIndependentCheck(LpIndependentValidation.DECLINED, LpIndependentClaim.NONE)
            }
            if (step.productionVerdict == LpVerdict.INDETERMINATE &&
                step.candidate == LpCandidateKind.NONE && !step.hasFeasibleWitness &&
                !step.hasCertifiedBound && !step.hasInfeasibilityProof && step.exactWitness == null &&
                step.rationalLowerBound == null && step.integerObjectiveLowerBound == null &&
                step.objectiveBits == null && step.primalBits == null
            ) {
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
            val lower = step.rationalLowerBound
            if (step.hasCertifiedBound && lower != null && witnessObjective != null && lower > witnessObjective) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.CERTIFIED_BOUND)
            }
            if (step.productionVerdict == LpVerdict.ATTAINED_OPTIMUM &&
                (!step.hasCertifiedBound || lower == null || witnessObjective == null || lower != witnessObjective)
            ) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.PROVED_OPTIMUM)
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
