package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ContinuationBudget
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase
import com.eignex.klause.simplex.exact.ContinuationStatus
import com.eignex.klause.simplex.exact.ContinuationStop
import com.eignex.klause.simplex.exact.ExactContinuation
import com.eignex.klause.simplex.exact.ExactContinuationInput
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.Cancellation

internal data class LpEpochBudget(
    val authority: ExactLpModel,
    val work: Long,
    val allocation: Long,
    val timeNs: Long,
    val pivots: Int,
    val importPivots: Int,
    val scalarPeak: Int,
) {
    init {
        require(work >= 0L && allocation >= 0L && timeNs >= 0L && pivots >= 0 && importPivots >= 0 && scalarPeak >= 0)
    }
}

internal class LpExactContinuationCache {
    internal var state: LpExactState? = null
    internal var key: ByteArray? = null
    internal var headings: List<Int>? = null
    internal var statuses: List<VarStatus>? = null
    internal var continuation: ExactContinuation? = null
    private var epochAuthority: ExactLpModel? = null
    private var carriedPivots = 0
    private var carriedImports = 0
    private var carriedScalarPeak = 0
    private var inputWork = 0L
    private var inputAllocation = 0L
    private var inputTimeNs = 0L
    val usedWork get() = continuation?.usedWork ?: inputWork
    val usedAllocation get() = continuation?.usedAllocation ?: inputAllocation
    val usedTimeNs get() = continuation?.usedTimeNs ?: inputTimeNs
    val usedPivots get() = addCount(carriedPivots, continuation?.usedPivots ?: 0)
    val usedImportPivots get() = addCount(carriedImports, continuation?.usedImportPivots ?: 0)
    val scalarPeak get() = maxOf(carriedScalarPeak, continuation?.normalizedScalarPeak ?: 0)

    fun exportBudget(current: LpExactState): LpEpochBudget? {
        if (state !== current && epochAuthority?.sameAuthority(current.model) != true) return null
        return LpEpochBudget(
            current.model,
            usedWork,
            usedAllocation,
            usedTimeNs,
            usedPivots,
            usedImportPivots,
            scalarPeak,
        )
    }

    fun importBudget(current: LpExactState, budget: LpEpochBudget): Boolean {
        if (state != null || key != null || headings != null || continuation != null || epochAuthority != null ||
            usedWork != 0L || usedAllocation != 0L || usedTimeNs != 0L ||
            !current.model.sameAuthority(budget.authority)
        ) {
            return false
        }
        epochAuthority = budget.authority
        inputWork = budget.work
        inputAllocation = budget.allocation
        inputTimeNs = budget.timeNs
        carriedPivots = budget.pivots
        carriedImports = budget.importPivots
        carriedScalarPeak = budget.scalarPeak
        return true
    }

    fun retainsBudget(current: LpExactState?): Boolean =
        current != null && epochAuthority?.sameAuthority(current.model) == true

    fun discardLane() {
        inputWork = usedWork
        inputAllocation = usedAllocation
        inputTimeNs = usedTimeNs
        carriedPivots = usedPivots
        carriedImports = usedImportPivots
        carriedScalarPeak = scalarPeak
        continuation = null
        headings = null
        statuses = null
    }

    fun limits(limits: ExactContinuationLimits): ExactContinuationLimits = limits.copy(
        maxPivots = (limits.maxPivots - carriedPivots).coerceAtLeast(0),
        maxImportPivots = (limits.maxImportPivots - carriedImports).coerceAtLeast(0),
    )

    fun accountInput(work: Long, allocation: Long, timeNs: Long) {
        require(work >= 0L && allocation >= 0L && timeNs >= 0L)
        continuation?.let {
            it.account(work, allocation, timeNs)
            return
        }
        inputWork = addCost(inputWork, work)
        inputAllocation = addCost(inputAllocation, allocation)
        inputTimeNs = addCost(inputTimeNs, timeNs)
    }

    fun initialize(input: ExactContinuationInput): ExactContinuation = continuation ?: ExactContinuation(input).also {
        it.account(inputWork, inputAllocation, inputTimeNs)
        continuation = it
    }

    fun clear() {
        inputWork = 0L
        inputAllocation = 0L
        inputTimeNs = 0L
        carriedPivots = 0
        carriedImports = 0
        carriedScalarPeak = 0
        epochAuthority = null
        state = null
        key = null
        headings = null
        statuses = null
        continuation = null
    }

    private fun addCost(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun addCount(left: Int, right: Int): Int = if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right
}

internal class LpContinuationTarget(val basis: Basis?, val metrics: ExactContinuationMetrics)

internal fun captureContinuationTarget(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    limits: ExactContinuationLimits,
    cancellation: Cancellation,
): LpContinuationTarget {
    val budget = ContinuationBudget(admissionLimits(limits), cancellation)
    budget.phase = ContinuationPhase.ADMISSION
    var basis: Basis? = null
    var decline: ContinuationDecline? = null
    try {
        budget.step()
        admitContinuation(model, budget)
        // The hook validates current authority and returns owned target arrays.
        budget.step(bytes = (model.m.toLong() + model.numVars) * 64L)
        basis = solver.continuationBasis(model) ?: if (model.exactState == null) {
            (result?.basis ?: solver.infeasibleBasis)?.let { Basis(it.basicVars.copyOf(), it.status.copyOf(), false) }
        } else {
            null
        }
        budget.step()
    } catch (stop: ContinuationStop) {
        decline = stop.reason
        basis = null
    }
    return LpContinuationTarget(
        basis,
        ExactContinuationMetrics(
            work = budget.work,
            allocation = budget.allocation,
            elapsedNs = budget.elapsedNs,
            phase = budget.phase,
            decline = decline,
            workByPhase = budget.workByPhase.toMap(),
            allocationByPhase = budget.allocationByPhase.toMap(),
        ),
    )
}

internal class LpContinuationVerification(
    val witness: ExactLpWitness?,
    val conflict: BigRationalConflict?,
    val support: LpExactSupport?,
    val metrics: ExactContinuationMetrics,
)

internal fun continueExactLp(
    model: LpModel,
    basis: Basis?,
    cache: LpExactContinuationCache = LpExactContinuationCache(),
    cancellation: Cancellation = Cancellation.Never,
    limits: ExactContinuationLimits = ExactContinuationLimits(),
    shortPivots: Int = 20,
    fullEffort: Boolean = true,
    targetMetrics: ExactContinuationMetrics = ExactContinuationMetrics(),
): LpContinuationVerification {
    require(shortPivots >= 0)
    if (targetMetrics.decline != null) return LpContinuationVerification(null, null, null, targetMetrics)
    val selection = selectContinuation(model, basis, cache, limits, cancellation, targetMetrics)
    if (selection.metrics.decline != null) {
        return LpContinuationVerification(null, null, null, selection.metrics)
    }
    val invalidated = selection.metrics.invalidated
    val captureLimits = limits.copy(
        maxWork = (limits.maxWork - cache.usedWork).coerceAtLeast(0),
        maxAllocation = (limits.maxAllocation - cache.usedAllocation).coerceAtLeast(0),
        maxTimeNs = (limits.maxTimeNs - cache.usedTimeNs).coerceAtLeast(0),
    )
    val capture = ContinuationBudget(captureLimits, cancellation)
    var metrics = ExactContinuationMetrics(invalidated = invalidated)
    var point: ExactLpWitness? = null
    var conflict: BigRationalConflict? = null
    var support: LpExactSupport? = null
    var captureCharged = false
    var captureNs: Long? = null
    try {
        capture.step()
        val authority = captureContinuation(model, requireNotNull(basis), capture)
        val session = cache.initialize(authority.input)
        captureNs = capture.elapsedNs
        session.account(capture.work, capture.allocation, captureNs)
        captureCharged = true
        var result = session.resume(
            cache.limits(limits.copy(maxPivots = minOf(shortPivots, limits.maxPivots))),
            cancellation,
        )
        metrics = result.metrics
        if (fullEffort && result.metrics.decline == ContinuationDecline.PIVOTS && limits.maxPivots > shortPivots) {
            val next = session.resume(cache.limits(limits), cancellation)
            metrics = combineContinuationMetrics(metrics, next.metrics)
            result = next
        }
        if (result.values != null || result.ray != null) {
            val remaining = remainingContinuationLimits(limits, session)
            val verification = ContinuationBudget(remaining, cancellation)
            verification.phase = ContinuationPhase.VERIFY
            var checked: ReconstructedCertificate? = null
            try {
                verification.step()
                val source = result.values?.take(model.n)?.mapIndexed { j, value ->
                    verification.preflight(value, authority.origins[j])
                    verification.fraction(value + authority.origins[j])
                }
                val defaults = ReconstructionLimits()
                checked = verifyRationalCertificate(
                    model,
                    sourcePrimal = source,
                    ray = result.ray,
                    basis = result.headings?.let { headings ->
                        Basis(
                            headings.toIntArray(),
                            requireNotNull(result.statuses).map(::engineStatus).toTypedArray(),
                            false,
                        )
                    },
                    cancellation = Cancellation { cancellation() || verification.elapsedNs >= remaining.maxTimeNs },
                    limits = defaults.copy(
                        maxWork = minOf(defaults.maxWork, (remaining.maxWork - verification.work).coerceAtLeast(0)),
                        maxAllocation = minOf(
                            defaults.maxAllocation,
                            (remaining.maxAllocation - verification.allocation).coerceAtLeast(0),
                        ),
                        maxBits = minOf(defaults.maxBits, remaining.maxBits),
                    ),
                )
                // Complete proof packages survive a later decline; scoped publication still checks cancellation.
                point = checked.witness
                conflict = checked.conflict
                support = checked.conflictSupport
                metrics = metrics.copy(
                    phase = ContinuationPhase.VERIFY,
                    checks = 1,
                    restarts = metrics.restarts + checked.metrics.verificationRestarts + checked.metrics.vectorRestarts,
                    decline = if (point == null && conflict == null) {
                        checked.metrics.decline?.let {
                            when (it) {
                                ReconstructionDecline.CANCELLED -> if (cancellation()) {
                                    ContinuationDecline.CANCELLED
                                } else {
                                    ContinuationDecline.TIME
                                }

                                ReconstructionDecline.WORK -> ContinuationDecline.WORK

                                ReconstructionDecline.ALLOCATION -> ContinuationDecline.ALLOCATION

                                ReconstructionDecline.BITS -> ContinuationDecline.BITS

                                ReconstructionDecline.DIMENSION -> ContinuationDecline.DIMENSION

                                else -> ContinuationDecline.CANDIDATE
                            }
                        } ?: ContinuationDecline.CANDIDATE
                    } else {
                        null
                    },
                )
            } finally {
                val work = verification.work + (checked?.metrics?.work ?: 0L)
                val bytes = verification.allocation + (checked?.metrics?.allocation ?: 0L)
                session.account(work, bytes, verification.elapsedNs)
                metrics = metrics.copy(
                    work = metrics.work + work,
                    allocation = metrics.allocation + bytes,
                    elapsedNs = metrics.elapsedNs + verification.elapsedNs,
                    workByPhase = addContinuationCosts(metrics.workByPhase, mapOf(ContinuationPhase.VERIFY to work)),
                    allocationByPhase = addContinuationCosts(
                        metrics.allocationByPhase,
                        mapOf(ContinuationPhase.VERIFY to bytes),
                    ),
                )
            }
        }
    } catch (stop: ContinuationStop) {
        metrics = metrics.copy(
            phase = if (captureCharged) ContinuationPhase.VERIFY else capture.phase,
            decline = stop.reason,
        )
    } finally {
        if (captureNs == null) captureNs = capture.elapsedNs
        if (!captureCharged) cache.accountInput(capture.work, capture.allocation, requireNotNull(captureNs))
    }
    metrics = metrics.copy(
        work = metrics.work + capture.work,
        allocation = metrics.allocation + capture.allocation,
        elapsedNs = metrics.elapsedNs + requireNotNull(captureNs),
        invalidated = invalidated,
        success = point != null || conflict != null,
        workByPhase = addContinuationCosts(metrics.workByPhase, capture.workByPhase),
        allocationByPhase = addContinuationCosts(metrics.allocationByPhase, capture.allocationByPhase),
    )
    return LpContinuationVerification(point, conflict, support, combineContinuationMetrics(selection.metrics, metrics))
}

// Identity admission is separately bounded; it cannot replenish retained numerical work.
private fun admissionLimits(limits: ExactContinuationLimits) = limits.copy(
    maxWork = minOf(limits.maxWork, 1_000_000L),
    maxAllocation = minOf(limits.maxAllocation, 4L * 1024L * 1024L),
    maxTimeNs = minOf(limits.maxTimeNs, 100_000_000L),
)

private fun admitContinuation(model: LpModel, budget: ContinuationBudget) {
    if (model.m > budget.limits.maxRows || model.numVars > budget.limits.maxColumns ||
        model.m.toLong() * (model.numVars.toLong() + model.m + 1L) > budget.limits.maxCells
    ) {
        throw ContinuationStop(ContinuationDecline.DIMENSION)
    }
    val size = model.exactState?.model?.keySize ?: model.csc.colVal.size.toLong() * 3L + model.numVars * 16L
    budget.step(size + model.numVars, size * 8L)
    // These are the only rational values compared by the target's current-state guard.
    model.exactState?.model?.let { source ->
        repeat(model.numVars) { j ->
            source.column(j).bounds.lower?.let { budget.fraction(it.number.value) }
            source.column(j).bounds.upper?.let { budget.fraction(it.number.value) }
        }
    }
}

private class ContinuationSelection(val metrics: ExactContinuationMetrics)

@Suppress("ThrowsCount")
private fun selectContinuation(
    model: LpModel,
    basis: Basis?,
    cache: LpExactContinuationCache,
    limits: ExactContinuationLimits,
    cancellation: Cancellation,
    exported: ExactContinuationMetrics,
): ContinuationSelection {
    val envelope = admissionLimits(limits)
    val budget = ContinuationBudget(
        envelope.copy(
            maxWork = (envelope.maxWork - exported.work).coerceAtLeast(0),
            maxAllocation = (envelope.maxAllocation - exported.allocation).coerceAtLeast(0),
            maxTimeNs = (envelope.maxTimeNs - exported.elapsedNs).coerceAtLeast(0),
        ),
        cancellation,
    )
    budget.phase = ContinuationPhase.ADMISSION
    var decline: ContinuationDecline? = null
    var invalidated = false
    try {
        budget.step()
        if (basis == null) throw ContinuationStop(ContinuationDecline.NO_BASIS)
        admitContinuation(model, budget)
        if (basis.basicVars.size != model.m || basis.status.size != model.numVars) {
            throw ContinuationStop(ContinuationDecline.INVALID_BASIS)
        }
        val key = if (model.exactState == null) {
            var size = model.n.toLong() * 16L + model.m * 16L + model.csc.colVal.size.toLong() * 3L
            model.doubleView?.let { size += it.colVal.size.toLong() * 3L }
            for (premises in model.rowPremises) {
                budget.step()
                if (premises != null) size += premises.vars.size.toLong() * 3L + premises.boolLits.size
            }
            budget.step(minOf(size, 4096L) * 4L, minOf(size, 4096L) * 64L)
            exactLpStateKey(model)
        } else {
            null
        }
        if (model.exactState == null && key == null && cache.headings != null) {
            throw ContinuationStop(ContinuationDecline.RESUME_KEY)
        }
        budget.step(model.numVars.toLong() + model.m, (model.numVars.toLong() + model.m) * 32L)
        val headings = basis.basicVars.toList()
        val statuses = basis.status.toList()
        val sameAuthority = if (model.exactState != null) {
            cache.state === model.exactState
        } else {
            key != null && cache.key?.contentEquals(key) == true
        }
        val matching = sameAuthority && cache.headings == headings && cache.statuses == statuses
        budget.step()
        if (!matching) {
            invalidated = cache.headings != null
            if (cache.retainsBudget(model.exactState)) cache.discardLane() else cache.clear()
            cache.state = model.exactState
            cache.key = key
            cache.headings = headings
            cache.statuses = statuses
        }
        if (cache.scalarPeak > limits.maxBits) throw ContinuationStop(ContinuationDecline.BITS)
    } catch (stop: ContinuationStop) {
        decline = stop.reason
    }
    return ContinuationSelection(
        combineContinuationMetrics(
            exported,
            ExactContinuationMetrics(
                work = budget.work,
                allocation = budget.allocation,
                elapsedNs = budget.elapsedNs,
                phase = budget.phase,
                decline = decline,
                invalidated = invalidated,
                workByPhase = budget.workByPhase.toMap(),
                allocationByPhase = budget.allocationByPhase.toMap(),
            ),
        ),
    )
}

private fun remainingContinuationLimits(limits: ExactContinuationLimits, session: ExactContinuation?) = limits.copy(
    maxWork = (limits.maxWork - (session?.usedWork ?: 0L)).coerceAtLeast(0),
    maxAllocation = (limits.maxAllocation - (session?.usedAllocation ?: 0L)).coerceAtLeast(0),
    maxTimeNs = (limits.maxTimeNs - (session?.usedTimeNs ?: 0L)).coerceAtLeast(0),
)

private class ContinuationAuthority(val input: ExactContinuationInput, val origins: List<BigFraction>)

private fun captureContinuation(model: LpModel, basis: Basis, budget: ContinuationBudget): ContinuationAuthority {
    if (model.m > budget.limits.maxRows || model.numVars > budget.limits.maxColumns ||
        model.m.toLong() * (model.numVars.toLong() + model.m + 1) > budget.limits.maxCells
    ) {
        throw ContinuationStop(ContinuationDecline.DIMENSION)
    }
    val size = model.exactState?.model?.keySize ?: model.csc.colVal.size.toLong() * 3L + model.numVars * 16L
    budget.step(size, size * 64L)
    if (!model.finiteExactInput()) throw ContinuationStop(ContinuationDecline.INVALID_INPUT)
    val columns = List(model.n) { j ->
        buildList {
            model.forEachRationalColumn(j) { row, value ->
                budget.step(bytes = 32)
                add(row to budget.fraction(value))
            }
        }
    }
    repeat(model.numVars) { budget.fraction(model.exactCost(it)) }
    budget.fraction(model.exactConstant())
    model.exactState?.model?.objective?.let {
        budget.fraction(it.scale.value)
        budget.fraction(it.externalConstant.value)
    }
    val bounds = List(model.numVars) { model.exactBounds(it) }
    val input = ExactContinuationInput(
        columns,
        List(model.m) { budget.fraction(model.exactRhs(it)) },
        bounds.map { it.lower?.number?.value?.let(budget::fraction) },
        bounds.map { it.upper?.number?.value?.let(budget::fraction) },
        basis.basicVars.toList(),
        basis.status.map {
            when (it) {
                VarStatus.BASIC -> ContinuationStatus.BASIC
                VarStatus.AT_LOWER -> ContinuationStatus.LOWER
                VarStatus.AT_UPPER -> ContinuationStatus.UPPER
                VarStatus.FIXED -> ContinuationStatus.FIXED
                VarStatus.FREE -> ContinuationStatus.FREE
            }
        },
    )
    return ContinuationAuthority(input, List(model.n) { budget.fraction(model.exactShift(it)) })
}

private fun engineStatus(status: ContinuationStatus): VarStatus = when (status) {
    ContinuationStatus.BASIC -> VarStatus.BASIC
    ContinuationStatus.LOWER -> VarStatus.AT_LOWER
    ContinuationStatus.UPPER -> VarStatus.AT_UPPER
    ContinuationStatus.FIXED -> VarStatus.FIXED
    ContinuationStatus.FREE -> VarStatus.FREE
}

private fun combineContinuationMetrics(a: ExactContinuationMetrics, b: ExactContinuationMetrics) = b.copy(
    builds = a.builds + b.builds, imports = a.imports + b.imports, pivots = a.pivots + b.pivots,
    repairs = a.repairs + b.repairs, restarts = a.restarts + b.restarts,
    work = a.work + b.work, allocation = a.allocation + b.allocation, elapsedNs = a.elapsedNs + b.elapsedNs,
    workByPhase = addContinuationCosts(a.workByPhase, b.workByPhase),
    allocationByPhase = addContinuationCosts(a.allocationByPhase, b.allocationByPhase),
)

private fun addContinuationCosts(a: Map<ContinuationPhase, Long>, b: Map<ContinuationPhase, Long>) =
    (a.keys + b.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) }
