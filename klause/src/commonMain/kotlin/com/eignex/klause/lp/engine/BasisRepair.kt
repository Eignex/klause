package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisRepairStop
import com.eignex.klause.simplex.basis.BasisSnapshot
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.simplex.basis.BasisRepair as SolverBasisRepair

internal data class BasisBoundState(val hasLower: Boolean, val hasUpper: Boolean, val fixed: Boolean) {
    init {
        require(!fixed || (hasLower && hasUpper))
    }
}

internal class EngineBasisState(
    headings: IntArray,
    statuses: Array<VarStatus>,
    ownerColumns: IntArray = headings,
    ownerUnitRows: IntArray = IntArray(headings.size) { -1 },
) {
    private val storedHeadings: IntArray = headings.copyOf()
    private val storedStatuses: Array<VarStatus> = statuses.copyOf()
    private val storedOwnerColumns: IntArray = ownerColumns.copyOf()
    private val storedOwnerUnitRows: IntArray = ownerUnitRows.copyOf()
    val headings: IntArray get() = storedHeadings.copyOf()
    val statuses: Array<VarStatus> get() = storedStatuses.copyOf()
    val ownerColumns: IntArray get() = storedOwnerColumns.copyOf()
    val ownerUnitRows: IntArray get() = storedOwnerUnitRows.copyOf()
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
    val rankEvidence: Map<ExactBasisRankEvidence, Long> = emptyMap(),
)

internal sealed interface BasisRecoveryResult {
    val rankEvidence: ExactBasisRankEvidence

    class Recovered(
        val state: EngineBasisState,
        val repaired: Boolean,
        override val rankEvidence: ExactBasisRankEvidence,
    ) : BasisRecoveryResult

    class Failed(val decline: BasisRepairDecline, override val rankEvidence: ExactBasisRankEvidence) :
        BasisRecoveryResult
}

internal class EngineBasisRepairer(private val exactRankLimits: ExactRankLimits = ExactRankLimits()) {
    private var attempts = 0L
    private var successes = 0L
    private var repairedUnitSuccesses = 0L
    private var logicalFallbacks = 0L
    private var logicalFallbackSuccesses = 0L
    private var logicalFallbackFailures = 0L
    private var avoidedLogicalRebuilds = 0L
    private val declines = mutableMapOf<BasisRepairDecline, Long>()
    private val rankEvidence = mutableMapOf<ExactBasisRankEvidence, Long>()

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
            rankEvidence.toMap(),
        )

    fun recover(
        solver: BasisSolver,
        requested: IntArray,
        structuralColumns: Int,
        bounds: Array<BasisBoundState>,
        statuses: Array<VarStatus>,
        exactModel: LpModel?,
        cancellation: Cancellation = Cancellation.Never,
        control: BasisRepairControl = BasisRepairControl(cancellation),
    ): BasisRecoveryResult {
        attempts = saturatingIncrement(attempts)
        val evidence = exactBasisRankEvidence(exactModel, requested, exactRankLimits, cancellation)
        rankEvidence[evidence] = saturatingIncrement(rankEvidence[evidence] ?: 0L)
        if (evidence == ExactBasisRankEvidence.RESOURCE_DECLINED) recordDecline(BasisRepairDecline.RESOURCE_DECLINED)
        if (evidence == ExactBasisRankEvidence.CANCELLED || cancellation()) {
            return failed(BasisRepairDecline.CANCELLED, evidence)
        }
        if (!control.check()) return stopped(control, evidence)
        val repaired = try {
            control.measure(solver) { solver.refactorizeRepairing(requested, control) }
        } catch (failure: BasisArithmeticException) {
            if (control.callbackFailure === failure) throw failure
            return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.NUMERICAL_FAILURE,
                control,
            )
        } catch (failure: ArithmeticException) {
            if (control.callbackFailure === failure) throw failure
            return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.NUMERICAL_FAILURE,
                control,
            )
        }
        if (!control.check()) return stopped(control, evidence)
        if (repaired == null) {
            return fallback(
                solver,
                structuralColumns,
                bounds,
                statuses,
                evidence,
                BasisRepairDecline.UNSUPPORTED,
                control,
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
                control,
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
        control: BasisRepairControl,
    ): BasisRecoveryResult {
        recordDecline(repairDecline)
        if (!control.check()) return stopped(control, evidence)
        logicalFallbacks = saturatingIncrement(logicalFallbacks)
        val logicals = IntArray(bounds.size - structuralColumns) { structuralColumns + it }
        val state = normalizeBasisState(logicals, bounds, statuses)
            ?: run {
                logicalFallbackFailures = saturatingIncrement(logicalFallbackFailures)
                return failed(BasisRepairDecline.LOGICAL_FALLBACK_FAILED, evidence)
            }
        control.charge(logicals.size.toLong())
        if (!control.check()) return stopped(control, evidence)
        val factorized = try {
            control.measure(solver) { solver.refactorize(logicals) }
        } catch (failure: BasisArithmeticException) {
            if (control.callbackFailure === failure) throw failure
            false
        } catch (failure: ArithmeticException) {
            if (control.callbackFailure === failure) throw failure
            false
        }
        val admitted = control.check()
        if (!factorized || !admitted) {
            logicalFallbackFailures = saturatingIncrement(logicalFallbackFailures)
            return failed(
                if (!admitted) stopDecline(control) else BasisRepairDecline.LOGICAL_FALLBACK_FAILED,
                evidence,
            )
        }
        successes = saturatingIncrement(successes)
        logicalFallbackSuccesses = saturatingIncrement(logicalFallbackSuccesses)
        return BasisRecoveryResult.Recovered(state, repaired = false, evidence)
    }

    private fun stopDecline(control: BasisRepairControl): BasisRepairDecline =
        if (control.stop == BasisRepairStop.CANCELLED) {
            BasisRepairDecline.CANCELLED
        } else {
            BasisRepairDecline.RESOURCE_DECLINED
        }

    private fun stopped(control: BasisRepairControl, evidence: ExactBasisRankEvidence): BasisRecoveryResult.Failed =
        failed(stopDecline(control), evidence)

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
    return normalizeBasisState(headings, bounds, statuses, repair.columns, repair.unitRows)
}

internal fun normalizeBasisState(
    headings: IntArray,
    bounds: Array<BasisBoundState>,
    statuses: Array<VarStatus>,
    ownerColumns: IntArray = headings,
    ownerUnitRows: IntArray = IntArray(headings.size) { -1 },
): EngineBasisState? {
    if (statuses.size != bounds.size || headings.distinct().size != headings.size ||
        headings.any { it !in bounds.indices } || ownerColumns.size != headings.size ||
        ownerUnitRows.size != headings.size
    ) {
        return null
    }
    val structuralColumns = bounds.size - headings.size
    if (structuralColumns < 0 || headings.indices.any { slot ->
            val column = ownerColumns[slot]
            val unitRow = ownerUnitRows[slot]
            val mapped = when {
                column in bounds.indices && unitRow == -1 -> column
                column == -1 && unitRow in headings.indices -> structuralColumns + unitRow
                else -> return@any true
            }
            mapped != headings[slot]
        }
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
    return EngineBasisState(headings, normalized, ownerColumns, ownerUnitRows)
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

internal data class BasisMatrixIdentity(val matrixRevision: Long, val structuralColumns: Int, val rowIds: List<Long>)

internal sealed interface BasisRestartResult {
    class Restored(val state: EngineBasisState, val factorsRestored: Boolean, val factorRestoreDeclined: Boolean) :
        BasisRestartResult

    class Cancelled(val factorsMayHaveChanged: Boolean) : BasisRestartResult
}

internal class EngineBasisRestartSnapshot private constructor(
    owner: BasisSolver,
    identity: BasisMatrixIdentity,
    captured: EngineBasisState,
    factors: BasisSnapshot?,
    onClose: ((EngineBasisRestartSnapshot) -> Unit)?,
) : AutoCloseable {
    private var owner: BasisSolver? = owner
    private var identity: BasisMatrixIdentity? = identity
    private var captured: EngineBasisState? = captured
    private var factors: BasisSnapshot? = factors
    private var onClose: ((EngineBasisRestartSnapshot) -> Unit)? = onClose
    private var closed = false

    fun restore(
        target: BasisSolver,
        currentIdentity: BasisMatrixIdentity,
        currentBounds: Array<BasisBoundState>,
        cancellation: Cancellation = Cancellation.Never,
    ): BasisRestartResult? {
        val owner = owner ?: return null
        val identity = identity ?: return null
        val captured = captured ?: return null
        val factors = factors
        if (closed || target !== owner || currentIdentity != identity) return null
        if (cancellation()) {
            close()
            return BasisRestartResult.Cancelled(factorsMayHaveChanged = false)
        }
        if (!validBasisSnapshotShape(target, currentIdentity, captured)) return null
        val normalized = normalizeBasisState(
            captured.headings,
            currentBounds,
            captured.statuses,
            captured.ownerColumns,
            captured.ownerUnitRows,
        ) ?: return null
        if (cancellation()) {
            close()
            return BasisRestartResult.Cancelled(factorsMayHaveChanged = false)
        }
        if (factors == null) return BasisRestartResult.Restored(normalized, false, false)
        val restored = try {
            target.restore(factors)
        } catch (_: BasisArithmeticException) {
            false
        } catch (_: ArithmeticException) {
            false
        }
        if (cancellation()) {
            close()
            return BasisRestartResult.Cancelled(factorsMayHaveChanged = true)
        }
        return BasisRestartResult.Restored(normalized, restored, !restored)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (closed) return
        closed = true
        val factors = factors
        val onClose = onClose
        owner = null
        identity = null
        captured = null
        this.factors = null
        this.onClose = null
        var failure: Throwable? = null
        try {
            factors?.close()
        } catch (cleanup: Throwable) {
            failure = cleanup
        }
        try {
            onClose?.invoke(this)
        } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
        }
        if (failure != null) throw failure
    }

    companion object {
        fun capture(
            owner: BasisSolver,
            identity: BasisMatrixIdentity,
            state: EngineBasisState,
            cancellation: Cancellation = Cancellation.Never,
            onClose: ((EngineBasisRestartSnapshot) -> Unit)? = null,
        ): EngineBasisRestartSnapshot? {
            if (cancellation() || !validBasisSnapshotShape(owner, identity, state)) return null
            if (cancellation()) return null
            val factors = try {
                owner.snapshot()
            } catch (_: BasisArithmeticException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
            if (cancellation()) {
                factors?.close()
                return null
            }
            return EngineBasisRestartSnapshot(
                owner,
                identity.copy(rowIds = identity.rowIds.toList()),
                EngineBasisState(state.headings, state.statuses, state.ownerColumns, state.ownerUnitRows),
                factors,
                onClose,
            )
        }
    }
}

private fun validBasisSnapshotShape(
    owner: BasisSolver,
    identity: BasisMatrixIdentity,
    state: EngineBasisState,
): Boolean {
    val headings = state.headings
    val statuses = state.statuses
    val ownerColumns = state.ownerColumns
    val ownerUnitRows = state.ownerUnitRows
    if (identity.matrixRevision < 0L || identity.structuralColumns < 0 || identity.rowIds.size != owner.n ||
        identity.rowIds.distinct().size != owner.n || headings.size != owner.n ||
        statuses.size != identity.structuralColumns + owner.n || ownerColumns.size != owner.n ||
        ownerUnitRows.size != owner.n
    ) {
        return false
    }
    if (headings.distinct().size != owner.n || headings.any { it !in statuses.indices }) return false
    val basic = BooleanArray(statuses.size)
    headings.forEach { basic[it] = true }
    if (statuses.indices.any { (statuses[it] == VarStatus.BASIC) != basic[it] }) {
        return false
    }
    return headings.indices.all { slot ->
        val column = ownerColumns[slot]
        val unitRow = ownerUnitRows[slot]
        when {
            column in statuses.indices && unitRow == -1 -> column
            column == -1 && unitRow in headings.indices -> identity.structuralColumns + unitRow
            else -> -1
        } == headings[slot]
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
            if (cancellation()) return ExactBasisRankEvidence.CANCELLED
            if (matrix[row][column].isZero) continue
            val factor = safeDivide(matrix[row][column], matrix[column][column], limits)
                ?: return ExactBasisRankEvidence.RESOURCE_DECLINED
            for (entry in column until matrix.size) {
                if (++updates > limits.maxUpdates) return ExactBasisRankEvidence.RESOURCE_DECLINED
                if (updates % 32 == 0 && cancellation()) return ExactBasisRankEvidence.CANCELLED
                val product = safeMultiply(factor, matrix[column][entry], limits)
                    ?: return ExactBasisRankEvidence.RESOURCE_DECLINED
                matrix[row][entry] = safeSubtract(matrix[row][entry], product, limits)
                    ?: return ExactBasisRankEvidence.RESOURCE_DECLINED
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
    if (leftNum + rightDen + 1 > limits.maxIntermediateDigits ||
        rightNum + leftDen + 1 > limits.maxIntermediateDigits ||
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

private fun boundedAdd(left: Int, right: Int, cap: Int): Int = if (left > cap - right) cap else left + right

private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L
