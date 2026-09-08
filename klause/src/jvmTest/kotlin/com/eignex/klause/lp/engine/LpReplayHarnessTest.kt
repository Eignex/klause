package com.eignex.klause.lp.engine

import java.math.BigInteger
import java.nio.file.Files
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
        assertTrue(allSteps.any { it.independentCheck.claim == LpIndependentClaim.FEASIBLE_WITNESS })
        assertTrue(allSteps.any { it.independentCheck.claim == LpIndependentClaim.CERTIFIED_BOUND })
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

        val continuousBuilder = LpBuilder()
        val continuousX = continuousBuilder.addRealVar(0.0, 10.0, cost = 1.0)
        continuousBuilder.addRealRow(intArrayOf(continuousX), doubleArrayOf(1.0), Relation.GE, 3.0)
        val continuous = continuousBuilder.build(Sense.MINIMIZE)

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
            Workload(
                "continuous-witness",
                LpCapture.capture(continuous, settings, listOf(LpReplayEvent.Solve())),
            ),
            Workload(
                "pivot-bounded",
                LpCapture.capture(coveringModel(), boundedSettings, listOf(LpReplayEvent.Solve())),
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

    private fun coveringModel(): LpModel {
        val builder = LpBuilder()
        val variables = IntArray(4) { builder.addVar(0L, 3L, cost = 1L) }
        builder.addRow(intArrayOf(variables[0], variables[1]), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(variables[1], variables[2]), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(variables[2], variables[3]), longArrayOf(1L, 1L), Relation.GE, 5L)
        builder.addRow(intArrayOf(variables[0], variables[3]), longArrayOf(1L, 1L), Relation.GE, 2L)
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
            "budgets=per-workload repetitions=$REPETITIONS cache=none verdicts=[$verdicts] " +
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
            if (step.productionVerdict == null || step.certificationCapability == LpCertificationCapability.NO_CLAIM) {
                return LpIndependentCheck(LpIndependentValidation.DECLINED, LpIndependentClaim.NONE)
            }
            val optimum = exactBoundedOptimum(model, step.enforcedRows)
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
            if (step.hasFeasibleWitness && !exactWitnessFeasible(model, step.primalBits)) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.FEASIBLE_WITNESS)
            }
            val expected = optimum.objective ?: return LpIndependentCheck(
                LpIndependentValidation.DECLINED,
                candidateClaim(step),
            )
            val lower = step.exactLowerBound
            if (lower != null && BigInteger.valueOf(lower) > expected.ceil()) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, LpIndependentClaim.CERTIFIED_BOUND)
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
                if (step.hasFeasibleWitness || step.hasCertifiedBound) {
                    return LpIndependentCheck(LpIndependentValidation.VALIDATED, candidateClaim(step))
                }
                return LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
            }
            val objective = step.objectiveBits?.let(Double::fromBits)?.let(Fraction::fromFiniteDouble)
                ?: return LpIndependentCheck(LpIndependentValidation.DECLINED, candidateClaim(step))
            if (objective.compareTo(expected) != 0) {
                return LpIndependentCheck(LpIndependentValidation.REFUTED, candidateClaim(step))
            }
            val claim = when {
                step.hasCertifiedBound -> LpIndependentClaim.PROVED_OPTIMUM
                step.hasFeasibleWitness -> LpIndependentClaim.FEASIBLE_WITNESS
                else -> LpIndependentClaim.CANDIDATE_HINT
            }
            return LpIndependentCheck(LpIndependentValidation.VALIDATED, claim)
        }

        private fun candidateClaim(step: LpReplayStep): LpIndependentClaim = when {
            step.hasInfeasibilityProof -> LpIndependentClaim.PROVED_INFEASIBLE

            step.productionVerdict == LpVerdict.OPTIMAL && step.hasCertifiedBound ->
                LpIndependentClaim.PROVED_OPTIMUM

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

private fun exactBoundedOptimum(model: LpModel, enforcedRows: BooleanArray?): ExactOptimum? {
    if (model.rowStrict.any { it } || model.sense != Sense.MINIMIZE) return null
    return if (model.hasContinuous) {
        exactContinuousOptimum(model, enforcedRows)
    } else {
        exactIntegerOptimum(model, enforcedRows)
    }
}

private fun exactContinuousOptimum(model: LpModel, enforcedRows: BooleanArray?): ExactOptimum? {
    if (model.n != 1) return null
    val view = model.doubleView ?: return null
    var lower = Fraction.ZERO
    var upper = if (view.hasUpper[0]) Fraction.fromFiniteDouble(view.upper[0]) ?: return null else null
    for (row in 0 until model.m) {
        if (enforcedRows?.get(row) == false) continue
        var coefficient = Fraction.ZERO
        for (position in view.colPtr[0] until view.colPtr[1]) {
            if (view.rowIdx[position] == row) {
                coefficient = Fraction.fromFiniteDouble(view.colVal[position]) ?: return null
            }
        }
        val rhs = Fraction.fromFiniteDouble(view.rhs[row]) ?: return null
        val tightened = tighten(
            lower,
            upper,
            coefficient,
            rhs,
            upperSide = true,
        ) ?: return ExactOptimum(false, null)
        lower = tightened.first
        upper = tightened.second
        val slack = model.slackCol(row)
        if (view.hasUpper[slack]) {
            val slackUpper = Fraction.fromFiniteDouble(view.upper[slack]) ?: return null
            val lowerRhs = rhs - slackUpper
            val next = tighten(lower, upper, coefficient, lowerRhs, upperSide = false)
                ?: return ExactOptimum(false, null)
            lower = next.first
            upper = next.second
        }
    }
    if (upper != null && lower > upper) return ExactOptimum(false, null)
    val cost = Fraction.fromFiniteDouble(view.cost[0]) ?: return null
    val seat = when {
        cost >= Fraction.ZERO -> lower
        upper != null -> upper
        else -> return null
    }
    val constant = Fraction.fromFiniteDouble(view.objConstant) ?: return null
    return ExactOptimum(true, seat * cost + constant)
}

private fun exactIntegerOptimum(model: LpModel, enforcedRows: BooleanArray?): ExactOptimum? {
    if (model.n > 4) return null
    val limits = LongArray(model.n)
    var assignments = 1L
    for (column in 0 until model.n) {
        if (!model.hasUpper[column] || model.upper[column] !in 0L..100L) return null
        limits[column] = model.upper[column]
        assignments = Math.multiplyExact(assignments, limits[column] + 1L)
        if (assignments > 100_000L) return null
    }
    val values = LongArray(model.n)
    var best: BigInteger? = null
    fun visit(column: Int) {
        if (column < model.n) {
            for (value in 0L..limits[column]) {
                values[column] = value
                visit(column + 1)
            }
            return
        }
        val rowSums = Array(model.m) { BigInteger.ZERO }
        for (j in 0 until model.n) {
            model.forEachInColumn(j) { row, coefficient ->
                rowSums[row] = rowSums[row].add(BigInteger.valueOf(coefficient).multiply(BigInteger.valueOf(values[j])))
            }
        }
        for (row in 0 until model.m) {
            if (enforcedRows?.get(row) == false) continue
            val rhs = BigInteger.valueOf(model.rhs[row])
            if (rowSums[row] > rhs) return
            val slack = model.slackCol(row)
            if (model.hasUpper[slack] && rowSums[row] < rhs.subtract(BigInteger.valueOf(model.upper[slack]))) return
        }
        var objective = BigInteger.valueOf(model.objConstant)
        for (j in 0 until model.n) {
            objective = objective.add(BigInteger.valueOf(model.cost[j]).multiply(BigInteger.valueOf(values[j])))
        }
        if (best == null || objective < best) best = objective
    }
    visit(0)
    return ExactOptimum(best != null, best?.let(Fraction::of))
}

private fun exactWitnessFeasible(model: LpModel, primalBits: LongArray?): Boolean {
    if (primalBits == null || primalBits.size < model.n || model.rowStrict.any { it }) return false
    val shifted = Array(model.n) { column ->
        val value = Fraction.fromFiniteDouble(Double.fromBits(primalBits[column])) ?: return false
        val shift = if (model.hasContinuous) {
            Fraction.fromFiniteDouble(model.doubleView!!.loShift[column]) ?: return false
        } else {
            Fraction.of(model.loShift[column])
        }
        value - shift
    }
    val rowSums = Array(model.m) { Fraction.ZERO }
    for (column in 0 until model.n) {
        if (shifted[column] < Fraction.ZERO) return false
        val upper = if (model.hasFiniteUpper(column)) {
            if (model.hasContinuous) {
                Fraction.fromFiniteDouble(model.doubleView!!.upper[column]) ?: return false
            } else {
                Fraction.of(model.upper[column])
            }
        } else {
            null
        }
        if (upper != null && shifted[column] > upper) return false
        if (model.hasContinuous) {
            val view = model.doubleView!!
            for (position in view.colPtr[column] until view.colPtr[column + 1]) {
                val coefficient = Fraction.fromFiniteDouble(view.colVal[position]) ?: return false
                val row = view.rowIdx[position]
                rowSums[row] = rowSums[row] + coefficient * shifted[column]
            }
        } else {
            model.forEachInColumn(column) { row, coefficient ->
                rowSums[row] = rowSums[row] + shifted[column] * Fraction.of(coefficient)
            }
        }
    }
    for (row in 0 until model.m) {
        val rhs = if (model.hasContinuous) {
            Fraction.fromFiniteDouble(model.doubleView!!.rhs[row]) ?: return false
        } else {
            Fraction.of(model.rhs[row])
        }
        if (rowSums[row] > rhs) return false
        val slack = model.slackCol(row)
        if (model.hasFiniteUpper(slack)) {
            val upper = if (model.hasContinuous) {
                Fraction.fromFiniteDouble(model.doubleView!!.upper[slack]) ?: return false
            } else {
                Fraction.of(model.upper[slack])
            }
            if (rowSums[row] < rhs - upper) return false
        }
    }
    return true
}

private fun tighten(
    lower: Fraction,
    upper: Fraction?,
    coefficient: Fraction,
    rhs: Fraction,
    upperSide: Boolean,
): Pair<Fraction, Fraction?>? {
    if (coefficient.compareTo(Fraction.ZERO) == 0) {
        val valid = if (upperSide) Fraction.ZERO <= rhs else Fraction.ZERO >= rhs
        return if (valid) lower to upper else null
    }
    val boundary = rhs / coefficient
    val isUpper = (coefficient > Fraction.ZERO) == upperSide
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

    operator fun times(other: Fraction): Fraction =
        create(numerator.multiply(other.numerator), denominator.multiply(other.denominator))
    operator fun plus(other: Fraction): Fraction = create(
        numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator),
    )
    operator fun minus(other: Fraction): Fraction = create(
        numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator),
    )
    operator fun div(other: Fraction): Fraction =
        create(numerator.multiply(other.denominator), denominator.multiply(other.numerator))

    fun ceil(): BigInteger {
        val division = numerator.divideAndRemainder(denominator)
        return if (division[1].signum() > 0) division[0] + BigInteger.ONE else division[0]
    }

    companion object {
        val ZERO: Fraction = Fraction(BigInteger.ZERO, BigInteger.ONE)
        fun of(value: Long): Fraction = Fraction(BigInteger.valueOf(value), BigInteger.ONE)
        fun of(value: BigInteger): Fraction = Fraction(value, BigInteger.ONE)

        fun fromFiniteDouble(value: Double): Fraction? {
            if (!value.isFinite()) return null
            val bits = value.toRawBits()
            val exponentBits = ((bits ushr 52) and 0x7ffL).toInt()
            val fractionBits = bits and 0x000f_ffff_ffff_ffffL
            val significand = if (exponentBits == 0) fractionBits else fractionBits or (1L shl 52)
            if (significand == 0L) return ZERO
            val exponent = if (exponentBits == 0) -1074 else exponentBits - 1023 - 52
            var numerator = BigInteger.valueOf(significand)
            if (bits < 0L) numerator = numerator.negate()
            return if (exponent >= 0) {
                create(numerator.shiftLeft(exponent), BigInteger.ONE)
            } else {
                create(numerator, BigInteger.ONE.shiftLeft(-exponent))
            }
        }

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
