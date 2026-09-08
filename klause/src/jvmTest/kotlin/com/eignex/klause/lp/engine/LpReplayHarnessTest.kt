package com.eignex.klause.lp.engine

import java.math.BigInteger
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpReplayHarnessTest {
    @Test
    fun `bounded replay utility persists and reports the fixed manifest`() {
        val manifest = fixedManifest()
        val directory = Files.createTempDirectory("lp-replay-w04-")
        val reports = try {
            manifest.map { workload ->
                val file = directory.resolve("${workload.name}.klpc")
                Files.write(file, workload.capture.encode())
                LpReplay.replay(LpCapture.decode(Files.readAllBytes(file)), BoundedExactValidator)
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
        assertTrue(allSteps.any { it.productionVerdict == LpVerdict.INDETERMINATE })

        val withoutPersistence = LongArray(REPETITIONS)
        val withPersistence = LongArray(REPETITIONS)
        repeat(REPETITIONS) { repetition ->
            withoutPersistence[repetition] = elapsedNanos {
                manifest.forEach { LpReplay.replay(it.capture, BoundedExactValidator) }
            }
            withPersistence[repetition] = elapsedNanos {
                manifest.forEach { LpReplay.replay(LpCapture.decode(it.capture.encode()), BoundedExactValidator) }
            }
        }
        println(reportLine(reports, withoutPersistence, withPersistence))
    }

    private fun fixedManifest(): List<Workload> {
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

        val feasibleBuilder = LpBuilder()
        val feasibleX = feasibleBuilder.addVar(0L, 10L, cost = 1L)
        feasibleBuilder.addRow(intArrayOf(feasibleX), longArrayOf(1L), Relation.GE, 3L)
        val feasible = feasibleBuilder.build(Sense.MINIMIZE)

        val infeasibleBuilder = LpBuilder()
        val infeasibleX = infeasibleBuilder.addVar(0L, 1L)
        infeasibleBuilder.addRow(intArrayOf(infeasibleX), longArrayOf(1L), Relation.GE, 2L)
        val infeasible = infeasibleBuilder.build(Sense.MINIMIZE)

        val cancelSettings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            cancellationPollLimit = 1,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
        )
        return listOf(
            Workload("empty", LpCapture.capture(empty, settings, listOf(LpReplayEvent.Solve()))),
            Workload("feasible-bounded", LpCapture.capture(feasible, settings, listOf(LpReplayEvent.Solve()))),
            Workload(
                "infeasible-bounded",
                LpCapture.capture(
                    infeasible,
                    settings,
                    listOf(
                        LpReplayEvent.Solve(),
                        LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                        LpReplayEvent.ResolveGated(booleanArrayOf(true)),
                    ),
                ),
            ),
            Workload(
                "cancelled",
                LpCapture.capture(cancellationModel(), cancelSettings, listOf(LpReplayEvent.Solve())),
            ),
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

    private fun elapsedNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun reportLine(
        reports: List<LpReplayReport>,
        withoutPersistence: LongArray,
        withPersistence: LongArray,
    ): String {
        val steps = reports.flatMap { it.steps }
        val verdicts = LpVerdict.entries.joinToString(",") { verdict ->
            "$verdict=${steps.count { it.productionVerdict == verdict }}"
        }
        val validations = LpIndependentValidation.entries.joinToString(",") { validation ->
            "$validation=${steps.count { it.independentCheck.validation == validation }}"
        }
        val totalPivots = reports.sumOf { it.pivots }
        val totalWork = reports.sumOf { it.workOps }
        return "LP_REPLAY label=$LABEL seed=$SEED workloads=${reports.size} " +
            "solver=persistent componentSplit=false refactorUpdateLimit=$DEFAULT_REFACTOR_UPDATE_LIMIT " +
            "pivotLimit=0 workLimit=0 repetitions=$REPETITIONS cache=none verdicts=[$verdicts] " +
            "validations=[$validations] pivots=$totalPivots workOps=$totalWork " +
            "withoutPersistenceNs=${summary(withoutPersistence)} withPersistenceNs=${summary(withPersistence)}"
    }

    private fun summary(values: LongArray): String {
        val sorted = values.sorted()
        return "${sorted[sorted.size / 2]}(${sorted.first()}..${sorted.last()})"
    }

    private class Workload(val name: String, val capture: LpCapture)

    private object BoundedExactValidator : LpReplayValidator {
        override fun validate(model: LpModel, step: LpReplayStep): LpIndependentCheck {
            val optimum = exactOneDimensionalOptimum(model, step.enforcedRows)
                ?: return LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
            if (!optimum.feasible) {
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
            if (step.productionVerdict == LpVerdict.INFEASIBLE) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.PROVED_INFEASIBLE)
            }
            if (step.productionVerdict != LpVerdict.OPTIMAL) {
                if (step.certificationCapability == LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE) {
                    val candidateMatches = if (optimum.feasible) {
                        step.candidate == LpCandidateKind.FLOAT_OPTIMUM
                    } else {
                        step.candidate == LpCandidateKind.FLOAT_INFEASIBILITY
                    }
                    return LpIndependentCheck(
                        if (candidateMatches) LpIndependentValidation.VALIDATED else LpIndependentValidation.REFUTED,
                        candidateClaim(step),
                    )
                }
                return LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
            }
            val expected = optimum.objective ?: return LpIndependentCheck(
                LpIndependentValidation.DECLINED,
                candidateClaim(step),
            )
            val objective = step.objectiveBits?.let(Double::fromBits)
            if (objective == null || abs(objective - expected.toDouble()) > 1e-9) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, candidateClaim(step))
            }
            val lower = step.exactLowerBound
            if (lower != null && BigInteger.valueOf(lower) > expected.ceil()) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.CERTIFIED_BOUND)
            }
            val claim = when {
                step.hasFeasibleWitness && step.hasCertifiedBound -> LpIndependentClaim.PROVED_OPTIMUM
                step.hasFeasibleWitness -> LpIndependentClaim.FEASIBLE_WITNESS
                step.hasCertifiedBound -> LpIndependentClaim.CERTIFIED_BOUND
                else -> LpIndependentClaim.CANDIDATE_HINT
            }
            return LpIndependentCheck(LpIndependentValidation.VALIDATED, claim)
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
        const val LABEL = "w0.4-replay-v1"
        const val SEED = 0x4c50573034L
        const val REPETITIONS = 3

        init {
            val model = LpBuilder().build(Sense.MINIMIZE)
            val settings = LpReplaySettings("w0.4-warmup", SEED, LpReplaySolverKind.PERSISTENT, componentSplit = false)
            LpReplay.replay(LpCapture.capture(model, settings, listOf(LpReplayEvent.Solve())))
        }
    }
}

private class ExactOptimum(val feasible: Boolean, val objective: Fraction?)

private fun exactOneDimensionalOptimum(model: LpModel, enforcedRows: BooleanArray?): ExactOptimum? {
    if (model.hasContinuous || model.rowStrict.any { it } || model.n > 1) return null
    if (model.n == 0) {
        val feasible = (0 until model.m).all { row ->
            if (enforcedRows?.get(row) == false) return@all true
            val slack = model.rhs[row]
            slack >= 0L && (!model.hasUpper[model.slackCol(row)] || slack <= model.upper[model.slackCol(row)])
        }
        return ExactOptimum(feasible, if (feasible) Fraction.of(model.objConstant) else null)
    }
    var lower = Fraction.ZERO
    var upper = if (model.hasUpper[0]) Fraction.of(model.upper[0]) else null
    for (row in 0 until model.m) {
        if (enforcedRows?.get(row) == false) continue
        var coefficient = 0L
        model.forEachInColumn(0) { i, value -> if (i == row) coefficient = value }
        val tightened = tighten(
            lower,
            upper,
            coefficient,
            model.rhs[row],
            upperSide = true,
        ) ?: return ExactOptimum(false, null)
        lower = tightened.first
        upper = tightened.second
        val slack = model.slackCol(row)
        if (model.hasUpper[slack]) {
            val lowerRhs = Math.subtractExact(model.rhs[row], model.upper[slack])
            val next = tighten(lower, upper, coefficient, lowerRhs, upperSide = false)
                ?: return ExactOptimum(false, null)
            lower = next.first
            upper = next.second
        }
    }
    if (upper != null && lower > upper) return ExactOptimum(false, null)
    val cost = model.cost[0]
    val seat = when {
        cost >= 0L -> lower
        upper != null -> upper
        else -> return null
    }
    return ExactOptimum(true, seat * cost + model.objConstant)
}

private fun tighten(
    lower: Fraction,
    upper: Fraction?,
    coefficient: Long,
    rhs: Long,
    upperSide: Boolean,
): Pair<Fraction, Fraction?>? {
    if (coefficient == 0L) {
        val valid = if (upperSide) 0L <= rhs else 0L >= rhs
        return if (valid) lower to upper else null
    }
    val boundary = Fraction.of(rhs, coefficient)
    val isUpper = (coefficient > 0L) == upperSide
    return if (isUpper) {
        lower to if (upper == null || boundary < upper) boundary else upper
    } else {
        (if (boundary > lower) boundary else lower) to upper
    }
}

private class Fraction private constructor(private val numerator: BigInteger, private val denominator: BigInteger) :
    Comparable<Fraction> {
    override fun compareTo(other: Fraction): Int =
        numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))

    operator fun times(value: Long): Fraction = create(numerator.multiply(BigInteger.valueOf(value)), denominator)
    operator fun plus(value: Long): Fraction = create(
        numerator.add(denominator.multiply(BigInteger.valueOf(value))),
        denominator,
    )
    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    fun ceil(): BigInteger {
        val division = numerator.divideAndRemainder(denominator)
        return if (division[1].signum() > 0) division[0] + BigInteger.ONE else division[0]
    }

    companion object {
        val ZERO: Fraction = Fraction(BigInteger.ZERO, BigInteger.ONE)
        fun of(value: Long): Fraction = Fraction(BigInteger.valueOf(value), BigInteger.ONE)
        fun of(numerator: Long, denominator: Long): Fraction =
            create(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        private fun create(numerator: BigInteger, denominator: BigInteger): Fraction {
            require(denominator.signum() != 0)
            val sign = if (denominator.signum() < 0) BigInteger.valueOf(-1L) else BigInteger.ONE
            val signedNumerator = numerator.multiply(sign)
            val positiveDenominator = denominator.multiply(sign)
            val gcd = signedNumerator.gcd(positiveDenominator)
            return Fraction(signedNumerator.divide(gcd), positiveDenominator.divide(gcd))
        }
    }
}
