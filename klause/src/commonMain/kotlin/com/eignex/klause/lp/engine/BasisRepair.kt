package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair as SolverBasisRepair
import com.eignex.klause.simplex.basis.BasisSnapshot
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal data class BasisBoundState(
    val hasLower: Boolean,
    val hasUpper: Boolean,
    val fixed: Boolean,
) {
    init {
        require(!fixed || hasLower && hasUpper)
    }
}

internal class EngineBasisState(headings: IntArray, statuses: Array<VarStatus>) {
    private val storedHeadings: IntArray = headings.copyOf()
    private val storedStatuses: Array<VarStatus> = statuses.copyOf()
    val headings: IntArray get() = storedHeadings.copyOf()
    val statuses: Array<VarStatus> get() = storedStatuses.copyOf()
}

internal enum class ExactBasisRankEvidence {
    UNAVAILABLE,
    RESOURCE_DECLINED,
    CANCELLED,
    SINGULAR,
    NONSINGULAR,
}

internal enum class BasisRepairDecline {
    CANCELLED,
    UNSUPPORTED,
    NUMERICAL_FAILURE,
    INVALID_REPAIR,
    RESOURCE_DECLINED,
    LOGICAL_FALLBACK_FAILED,
}

internal data class BasisRepairMetrics(
    val attempts: Long = 0,
    val successes: Long = 0,
    val repairedUnitSuccesses: Long = 0,
    val logicalFallbacks: Long = 0,
    val logicalFallbackSuccesses: Long = 0,
    val logicalFallbackFailures: Long = 0,
    val avoidedLogicalRebuilds: Long = 0,
    val declines: Map<BasisRepairDecline, Long> = emptyMap(),
)

internal sealed interface BasisRecoveryResult {
    val rankEvidence: ExactBasisRankEvidence

    class Recovered(
        val state: EngineBasisState,
        val repaired: Boolean,
        override val rankEvidence: ExactBasisRankEvidence,
    ) : BasisRecoveryResult

    class Failed(
        val decline: BasisRepairDecline,
        override val rankEvidence: ExactBasisRankEvidence,
    ) : BasisRecoveryResult
}

internal class EngineBasisRepairer(
    private val exactRankLimits: ExactRankLimits = ExactRankLimits(),
) {
    private var attempts = 0L
    private var successes = 0L
    private var repairedUnitSuccesses = 0L
    private var logicalFallbacks = 0L
    private var logicalFallbackSuccesses = 0L
    private var logicalFallbackFailures = 0L
    private var avoidedLogicalRebuilds = 0L
    private val declines = mutableMapOf<BasisRepairDecline, Long>()

    val metrics: BasisRepairMetrics
        get() = BasisRepairMetrics(
            attempts,
            successes,
            repairedUnitSuccesses,
            logicalFallbacks,
            logicalFallbackSuccesses,
            logicalFallbackFailures,
            avoidedLogicalRebuilds,
            declines.toMap(),
        )

    fun recover(
        solver: BasisSolver,
        requested: IntArray,
        structuralColumns: Int,
        bounds: Array<BasisBoundState>,
        statuses: Array<VarStatus>,
        exactModel: LpModel?,
        cancellation: Cancellation = Cancellation.Never,
    ): BasisRecoveryResult {
        attempts = saturatingIncrement(attempts)
        val evidence = exactBasisRankEvidence(exactModel, requested, exactRankLimits, cancellation)
        if (evidence == ExactBasisRankEvidence.CANCELLED || cancellation()) {
            return failed(BasisRepairDecline.CANCELLED, evidence)
        }
        val repaired = try {
            solver.refactorizeRepairing(requested)
        } catch (_: BasisArithmeticException) {
            return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.NUMERICAL_FAILURE,
                cancellation,
            )
        } catch (_: ArithmeticException) {
            return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.NUMERICAL_FAILURE,
                cancellation,
            )
        }
        if (cancellation()) return failed(BasisRepairDecline.CANCELLED, evidence)
        if (repaired == null) {
            return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.UNSUPPORTED,
                cancellation,
            )
        }
        val candidate = decodeBasisRepair(repaired, structuralColumns, bounds, statuses)
            ?: return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.INVALID_REPAIR,
                cancellation,
            )
        successes = saturatingIncrement(successes)
        avoidedLogicalRebuilds = saturatingIncrement(avoidedLogicalRebuilds)
        if (repaired.repaired) repairedUnitSuccesses = saturatingIncrement(repairedUnitSuccesses)
        return BasisRecoveryResult.Recovered(candidate, repaired = true, evidence)
    }

    private fun fallback(
        solver: BasisSolver,
        structuralColumns: Int,
        bounds: Array<BasisBoundState>,
        statuses: Array<VarStatus>,
        evidence: ExactBasisRankEvidence,
        repairDecline: BasisRepairDecline,
        cancellation: Cancellation,
    ): BasisRecoveryResult {
        recordDecline(repairDecline)
        if (cancellation()) return failed(BasisRepairDecline.CANCELLED, evidence)
        logicalFallbacks = saturatingIncrement(logicalFallbacks)
        val logicals = IntArray(bounds.size - structuralColumns) { structuralColumns + it }
        val state = normalizeBasisState(logicals, bounds, statuses)
            ?: return failed(BasisRepairDecline.LOGICAL_FALLBACK_FAILED, evidence)
        val factorized = try {
            solver.refactorize(logicals)
        } catch (_: BasisArithmeticException) {
            false
        } catch (_: ArithmeticException) {
            false
        }
        if (!factorized || cancellation()) {
            logicalFallbackFailures = saturatingIncrement(logicalFallbackFailures)
            return failed(
                if (cancellation()) BasisRepairDecline.CANCELLED else BasisRepairDecline.LOGICAL_FALLBACK_FAILED,
                evidence,
            )
        }
        successes = saturatingIncrement(successes)
        logicalFallbackSuccesses = saturatingIncrement(logicalFallbackSuccesses)
        return BasisRecoveryResult.Recovered(state, repaired = false, evidence)
    }

    private fun failed(decline: BasisRepairDecline, evidence: ExactBasisRankEvidence): BasisRecoveryResult.Failed {
        recordDecline(decline)
        return BasisRecoveryResult.Failed(decline, evidence)
    }

    private fun recordDecline(decline: BasisRepairDecline) {
        declines[decline] = saturatingIncrement(declines[decline] ?: 0L)
    }
}

internal fun decodeBasisRepair(
    repair: SolverBasisRepair,
    structuralColumns: Int,
    bounds: Array<BasisBoundState>,
    statuses: Array<VarStatus>,
): EngineBasisState? {
    val rows = repair.columns.size
    if (structuralColumns < 0 || bounds.size != structuralColumns + rows || statuses.size != bounds.size) return null
    val headings = IntArray(rows)
    for (slot in 0 until rows) {
        val column = repair.columns[slot]
        val unitRow = repair.unitRows[slot]
        headings[slot] = when {
            column in bounds.indices && unitRow == -1 -> column
            column == -1 && unitRow in 0 until rows -> structuralColumns + unitRow
            else -> return null
        }
    }
    return normalizeBasisState(headings, bounds, statuses)
}

internal fun normalizeBasisState(
    headings: IntArray,
    bounds: Array<BasisBoundState>,
    statuses: Array<VarStatus>,
): EngineBasisState? {
    if (statuses.size != bounds.size || headings.distinct().size != headings.size ||
        headings.any { it !in bounds.indices }
    ) {
        return null
    }
    val basic = BooleanArray(bounds.size)
    headings.forEach { basic[it] = true }
    val normalized = Array(bounds.size) { column ->
        if (basic[column]) VarStatus.BASIC else normalizeNonbasicStatus(statuses[column], bounds[column])
    }
    if (normalized.indices.any { column ->
            (normalized[column] == VarStatus.BASIC) != basic[column] ||
                !statusValidForBounds(normalized[column], bounds[column])
        }
    ) {
        return null
    }
    return EngineBasisState(headings, normalized)
}

private fun normalizeNonbasicStatus(status: VarStatus, bounds: BasisBoundState): VarStatus = when {
    status == VarStatus.FIXED && bounds.fixed -> VarStatus.FIXED
    status == VarStatus.AT_LOWER && bounds.hasLower -> VarStatus.AT_LOWER
    status == VarStatus.AT_UPPER && bounds.hasUpper -> VarStatus.AT_UPPER
    status == VarStatus.FREE && !bounds.hasLower && !bounds.hasUpper -> VarStatus.FREE
    bounds.fixed -> VarStatus.FIXED
    bounds.hasLower -> VarStatus.AT_LOWER
    bounds.hasUpper -> VarStatus.AT_UPPER
    else -> VarStatus.FREE
}

private fun statusValidForBounds(status: VarStatus, bounds: BasisBoundState): Boolean = when (status) {
    VarStatus.BASIC -> true
    VarStatus.AT_LOWER -> bounds.hasLower
    VarStatus.AT_UPPER -> bounds.hasUpper
    VarStatus.FIXED -> bounds.fixed
    VarStatus.FREE -> !bounds.hasLower && !bounds.hasUpper
}

internal fun LpModel.basisBoundStates(): Array<BasisBoundState> = Array(numVars) { column ->
    val exact = exactState?.model?.column(column)?.bounds
    BasisBoundState(
        exact?.lower != null || hasFiniteLower(column),
        exact?.upper != null || hasFiniteUpper(column),
        exact?.fixed ?: fixed(column),
    )
}

internal data class BasisMatrixIdentity(
    val matrixRevision: Long,
    val structuralColumns: Int,
    val rowIds: List<Long>,
)

internal data class BasisRestartRestore(
    val state: EngineBasisState,
    val factorsRestored: Boolean,
    val factorRestoreDeclined: Boolean,
)

internal class EngineBasisRestartSnapshot private constructor(
    private val owner: BasisSolver,
    private val identity: BasisMatrixIdentity,
    private val captured: EngineBasisState,
    private val factors: BasisSnapshot?,
) : AutoCloseable {
    private var closed = false

    fun restore(
        target: BasisSolver,
        currentIdentity: BasisMatrixIdentity,
        currentBounds: Array<BasisBoundState>,
        cancellation: Cancellation = Cancellation.Never,
    ): BasisRestartRestore? {
        if (closed || cancellation() || target !== owner || currentIdentity != identity) return null
        val normalized = normalizeBasisState(captured.headings, currentBounds, captured.statuses) ?: return null
        if (factors == null) return BasisRestartRestore(normalized, false, false)
        val restored = try {
            target.restore(factors)
        } catch (_: BasisArithmeticException) {
            false
        } catch (_: ArithmeticException) {
            false
        }
        return BasisRestartRestore(normalized, restored, !restored)
    }

    override fun close() {
        if (closed) return
        closed = true
        factors?.close()
    }

    companion object {
        fun capture(
            owner: BasisSolver,
            identity: BasisMatrixIdentity,
            state: EngineBasisState,
        ): EngineBasisRestartSnapshot {
            val factors = try {
                owner.snapshot()
            } catch (_: BasisArithmeticException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
            return EngineBasisRestartSnapshot(
                owner,
                identity.copy(rowIds = identity.rowIds.toList()),
                EngineBasisState(state.headings, state.statuses),
                factors,
            )
        }
    }
}

internal data class ExactRankLimits(
    val maxDimension: Int = 16,
    val maxUpdates: Int = 4096,
    val maxInputDigits: Int = 4096,
    val maxIntermediateDigits: Int = 4096,
) {
    init {
        require(maxDimension >= 0 && maxUpdates >= 0 && maxInputDigits >= 0 && maxIntermediateDigits >= 0)
    }
}

internal fun exactBasisRankEvidence(
    model: LpModel?,
    headings: IntArray,
    limits: ExactRankLimits = ExactRankLimits(),
    cancellation: Cancellation = Cancellation.Never,
): ExactBasisRankEvidence {
    model ?: return ExactBasisRankEvidence.UNAVAILABLE
    if (model.exactState == null || headings.size != model.m || headings.any { it !in 0 until model.numVars }) {
        return ExactBasisRankEvidence.UNAVAILABLE
    }
    if (model.m > limits.maxDimension) return ExactBasisRankEvidence.RESOURCE_DECLINED
    val matrix = Array(model.m) { Array(model.m) { BigFraction.ZERO } }
    var inputDigits = 0
    for (column in headings.indices) {
        if (cancellation()) return ExactBasisRankEvidence.CANCELLED
        model.forEachRationalColumn(headings[column]) { row, value ->
            matrix[row][column] = value
            inputDigits = boundedAdd(inputDigits, fractionDigits(value), limits.maxInputDigits + 1)
        }
        if (inputDigits > limits.maxInputDigits) return ExactBasisRankEvidence.RESOURCE_DECLINED
    }
    var updates = 0
    for (column in matrix.indices) {
        if (cancellation()) return ExactBasisRankEvidence.CANCELLED
        var pivot = -1
        for (row in column until matrix.size) {
            if (++updates > limits.maxUpdates) return ExactBasisRankEvidence.RESOURCE_DECLINED
            if (!matrix[row][column].isZero) {
                pivot = row
                break
            }
        }
        if (pivot == -1) return ExactBasisRankEvidence.SINGULAR
        if (pivot != column) {
            val swap = matrix[pivot]
            matrix[pivot] = matrix[column]
            matrix[column] = swap
        }
        for (row in column + 1 until matrix.size) {
            if (matrix[row][column].isZero) continue
            val factor = safeDivide(matrix[row][column], matrix[column][column], limits) ?:
                return ExactBasisRankEvidence.RESOURCE_DECLINED
            for (entry in column until matrix.size) {
                if (++updates > limits.maxUpdates) return ExactBasisRankEvidence.RESOURCE_DECLINED
                val product = safeMultiply(factor, matrix[column][entry], limits) ?:
                    return ExactBasisRankEvidence.RESOURCE_DECLINED
                matrix[row][entry] = safeSubtract(matrix[row][entry], product, limits) ?:
                    return ExactBasisRankEvidence.RESOURCE_DECLINED
            }
        }
    }
    return ExactBasisRankEvidence.NONSINGULAR
}

private fun safeDivide(left: BigFraction, right: BigFraction, limits: ExactRankLimits): BigFraction? {
    if (right.isZero || !withinProductDigits(left.num.toString(), right.den.toString(), limits) ||
        !withinProductDigits(left.den.toString(), right.num.toString(), limits)
    ) {
        return null
    }
    return left * right.reciprocal()
}

private fun safeMultiply(left: BigFraction, right: BigFraction, limits: ExactRankLimits): BigFraction? {
    if (!withinProductDigits(left.num.toString(), right.num.toString(), limits) ||
        !withinProductDigits(left.den.toString(), right.den.toString(), limits)
    ) {
        return null
    }
    return left * right
}

private fun safeSubtract(left: BigFraction, right: BigFraction, limits: ExactRankLimits): BigFraction? {
    val leftNum = decimalDigits(left.num.toString())
    val rightNum = decimalDigits(right.num.toString())
    val leftDen = decimalDigits(left.den.toString())
    val rightDen = decimalDigits(right.den.toString())
    if (leftNum + rightDen > limits.maxIntermediateDigits ||
        rightNum + leftDen > limits.maxIntermediateDigits ||
        leftDen + rightDen > limits.maxIntermediateDigits
    ) {
        return null
    }
    return left - right
}

private fun withinProductDigits(left: String, right: String, limits: ExactRankLimits): Boolean =
    decimalDigits(left) + decimalDigits(right) <= limits.maxIntermediateDigits

private fun fractionDigits(value: BigFraction): Int =
    boundedAdd(decimalDigits(value.num.toString()), decimalDigits(value.den.toString()), Int.MAX_VALUE)

private fun decimalDigits(value: String): Int = value.length - if (value.startsWith('-')) 1 else 0

private fun boundedAdd(left: Int, right: Int, cap: Int): Int =
    if (left > cap - right) cap else left + right

private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L
