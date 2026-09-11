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

internal class LpExactContinuationCache {
    internal var state: LpExactState? = null
    internal var key: ByteArray? = null
    internal var headings: List<Int>? = null
    internal var statuses: List<VarStatus>? = null
    internal var continuation: ExactContinuation? = null

    fun clear() {
        state = null
        key = null
        headings = null
        statuses = null
        continuation = null
    }
}

internal class LpContinuationTarget(val basis: Basis?, val metrics: ExactContinuationMetrics)

internal fun captureContinuationTarget(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    cache: LpExactContinuationCache,
    limits: ExactContinuationLimits,
    cancellation: Cancellation,
): LpContinuationTarget {
    val old = cache.continuation.takeIf { cache.state === model.exactState }
    val budget = ContinuationBudget(remainingContinuationLimits(limits, old), cancellation)
    var basis: Basis? = null
    var decline: ContinuationDecline? = null
    try {
        budget.step()
        if (model.m > limits.maxRows || model.numVars > limits.maxColumns) {
            throw ContinuationStop(ContinuationDecline.DIMENSION)
        }
        val sourceSize = model.exactState?.model?.keySize ?: model.csc.colVal.size.toLong() * 3L
        // Current-authority validation scans the source; stopped snapshots and output arrays are owned copies.
        budget.step(sourceSize + model.numVars, (model.m.toLong() + model.numVars) * 64L)
        basis = solver.continuationBasis(model) ?: if (model.exactState == null) {
            (result?.basis ?: solver.infeasibleBasis)?.let { Basis(it.basicVars.copyOf(), it.status.copyOf(), false) }
        } else null
        budget.step()
    } catch (stop: ContinuationStop) {
        decline = stop.reason
        basis = null
    }
    return LpContinuationTarget(basis, ExactContinuationMetrics(
        work = budget.work, allocation = budget.allocation, elapsedNs = budget.elapsedNs, decline = decline,
        workByPhase = budget.workByPhase.toMap(), allocationByPhase = budget.allocationByPhase.toMap(),
    ))
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
    var invalidated = cache.continuation != null && cache.state !== model.exactState
    if (invalidated) cache.clear()
    val old = cache.continuation
    old?.account(targetMetrics.work, targetMetrics.allocation, targetMetrics.elapsedNs)
    if (targetMetrics.decline != null) return LpContinuationVerification(null, null, null, targetMetrics)
    val captureLimits = remainingContinuationLimits(limits, old).let {
        if (old != null) it else it.copy(
            maxWork = (it.maxWork - targetMetrics.work).coerceAtLeast(0),
            maxAllocation = (it.maxAllocation - targetMetrics.allocation).coerceAtLeast(0),
            maxTimeNs = (it.maxTimeNs - targetMetrics.elapsedNs).coerceAtLeast(0),
        )
    }
    val capture = ContinuationBudget(captureLimits, cancellation)
    var metrics = ExactContinuationMetrics(invalidated = invalidated)
    var point: ExactLpWitness? = null
    var conflict: BigRationalConflict? = null
    var support: LpExactSupport? = null
    var captureCharged = false
    var captureNs: Long? = null
    try {
        capture.step()
        if (basis == null) throw ContinuationStop(ContinuationDecline.NO_BASIS)
        val authority = captureContinuation(model, basis, capture)
        val key = if (model.exactState == null) exactLpStateKey(model) else null
        capture.step(bytes = key?.size?.toLong() ?: 0L)
        if (model.exactState == null && key == null && old != null) {
            throw ContinuationStop(ContinuationDecline.RESUME_KEY)
        }
        val sameAuthority = if (model.exactState != null) cache.state === model.exactState else
            key != null && cache.key?.contentEquals(key) == true
        val matching = sameAuthority &&
            cache.headings == basis.basicVars.toList() && cache.statuses == basis.status.toList()
        invalidated = invalidated || old != null && !matching
        val session = if (matching) requireNotNull(old) else ExactContinuation(authority.input).also {
            cache.clear()
            cache.state = model.exactState
            cache.key = key
            cache.headings = basis.basicVars.toList()
            cache.statuses = basis.status.toList()
            cache.continuation = it
        }
        if (session !== old) session.account(targetMetrics.work, targetMetrics.allocation, targetMetrics.elapsedNs)
        captureNs = capture.elapsedNs
        session.account(capture.work, capture.allocation, captureNs)
        captureCharged = true
        var result = session.resume(limits.copy(maxPivots = minOf(shortPivots, limits.maxPivots)), cancellation)
        metrics = result.metrics
        if (fullEffort && result.metrics.decline == ContinuationDecline.PIVOTS && limits.maxPivots > shortPivots) {
            val next = session.resume(limits, cancellation)
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
                        Basis(headings.toIntArray(), requireNotNull(result.statuses).map(::engineStatus).toTypedArray(), false)
                    },
                    cancellation = Cancellation { cancellation() || verification.elapsedNs >= remaining.maxTimeNs },
                    limits = defaults.copy(
                        maxWork = minOf(defaults.maxWork, (remaining.maxWork - verification.work).coerceAtLeast(0)),
                        maxAllocation = minOf(defaults.maxAllocation, (remaining.maxAllocation - verification.allocation).coerceAtLeast(0)),
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
                    decline = if (point == null && conflict == null) checked.metrics.decline?.let {
                        when (it) {
                            ReconstructionDecline.CANCELLED -> if (cancellation()) ContinuationDecline.CANCELLED else ContinuationDecline.TIME
                            ReconstructionDecline.WORK -> ContinuationDecline.WORK
                            ReconstructionDecline.ALLOCATION -> ContinuationDecline.ALLOCATION
                            ReconstructionDecline.BITS -> ContinuationDecline.BITS
                            ReconstructionDecline.DIMENSION -> ContinuationDecline.DIMENSION
                            else -> ContinuationDecline.CANDIDATE
                        }
                    } ?: ContinuationDecline.CANDIDATE else null,
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
                    allocationByPhase = addContinuationCosts(metrics.allocationByPhase, mapOf(ContinuationPhase.VERIFY to bytes)),
                )
            }
        }
    } catch (stop: ContinuationStop) {
        metrics = metrics.copy(phase = if (captureCharged) ContinuationPhase.VERIFY else capture.phase, decline = stop.reason)
    } finally {
        if (captureNs == null) captureNs = capture.elapsedNs
        if (!captureCharged) old?.account(capture.work, capture.allocation, requireNotNull(captureNs))
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
    return LpContinuationVerification(point, conflict, support, combineContinuationMetrics(targetMetrics, metrics))
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
