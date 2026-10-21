package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationBudget
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase
import com.eignex.klause.simplex.exact.ContinuationStop
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

internal class LpAffineContinuation(
    val frame: LpAffineFrame,
    val model: LpModel,
    val basis: Basis,
    val cache: LpExactContinuationCache = LpExactContinuationCache(),
) {
    init {
        // Frame preparation is charged before import; the identity must already match when import selects its cache.
        cache.state = requireNotNull(model.exactState)
        cache.headings = basis.basicVars.toList()
        cache.statuses = basis.status.toList()
    }
}

@Suppress("ThrowsCount")
internal fun continueAffineLp(
    original: LpModel,
    cache: LpExactContinuationCache,
    cancellation: Cancellation,
    limits: ExactContinuationLimits,
    shortPivots: Int,
    fullEffort: Boolean,
    exported: ExactContinuationMetrics,
): LpContinuationVerification {
    val budget = ContinuationBudget(
        limits.copy(
            maxWork = minOf(limits.maxWork, 1_000_000L),
            maxAllocation = minOf(limits.maxAllocation, 4L * 1024L * 1024L),
        ),
        cancellation,
    )
    budget.phase = ContinuationPhase.ADMISSION
    var held: LpAffineContinuation? = null
    var decline: ContinuationDecline? = null
    try {
        budget.step()
        val source = requireNotNull(original.exactState)
        held = cache.affine?.takeIf { it.frame.source === source }
        if (held == null) {
            cache.clear()
            val frame = LpAffineFrame.capture(source, budget) ?: throw ContinuationStop(ContinuationDecline.DIMENSION)
            val projection = LpProjectionMeter(
                (budget.limits.maxWork - budget.work).coerceAtLeast(0),
                (budget.limits.maxAllocation - budget.allocation).coerceAtLeast(0),
                cancellation,
            )
            val model = LpExactState(frame.model).toWorkingModel(projection)
            budget.step(projection.work, projection.allocation)
            if (model == null) throw ContinuationStop(ContinuationDecline.INVALID_INPUT)
            if (!limits.admitsDimensions(model)) throw ContinuationStop(ContinuationDecline.DIMENSION)
            budget.step(model.numVars.toLong() + model.m, model.numVars.toLong() * 32L + model.m.toLong() * 16L)
            val basis = Basis(
                IntArray(model.m) { model.n + it },
                Array(model.numVars) { column ->
                    val bounds = frame.model.column(column).bounds
                    when {
                        column >= model.n -> VarStatus.BASIC
                        bounds.fixed -> VarStatus.FIXED
                        bounds.lower != null -> VarStatus.AT_LOWER
                        bounds.upper != null -> VarStatus.AT_UPPER
                        else -> VarStatus.FREE
                    }
                },
            )
            held = LpAffineContinuation(frame, model, basis)
            cache.affine = held
        }
        if (!limits.admitsDimensions(held.model)) throw ContinuationStop(ContinuationDecline.DIMENSION)
    } catch (stop: ContinuationStop) {
        decline = stop.reason
    }
    val preparation = ExactContinuationMetrics(
        phase = budget.phase,
        work = budget.work,
        allocation = budget.allocation,
        elapsedNs = budget.elapsedNs,
        decline = decline,
        workByPhase = budget.workByPhase,
        allocationByPhase = budget.allocationByPhase,
    )
    val owner = held
    if (owner == null || decline != null) {
        return LpContinuationVerification(null, null, null, combineContinuationMetrics(exported, preparation))
    }
    owner.cache.accountInput(preparation.work, preparation.allocation, preparation.elapsedNs)
    val continued = continueExactLp(
        owner.model,
        owner.basis,
        owner.cache,
        cancellation,
        limits,
        shortPivots,
        fullEffort,
    )
    val metrics = combineContinuationMetrics(
        exported.copy(decline = null),
        combineContinuationMetrics(preparation, continued.metrics),
    )
    if (continued.witness == null && continued.conflict == null) {
        return LpContinuationVerification(null, null, null, metrics)
    }
    return verifyAffineContinuation(original, owner, continued, metrics, limits, cancellation)
}

private fun verifyAffineContinuation(
    original: LpModel,
    owner: LpAffineContinuation,
    continued: LpContinuationVerification,
    metrics: ExactContinuationMetrics,
    limits: ExactContinuationLimits,
    cancellation: Cancellation,
): LpContinuationVerification {
    val remaining = limits.copy(
        maxWork = (limits.maxWork - owner.cache.usedWork).coerceAtLeast(0),
        maxAllocation = (limits.maxAllocation - owner.cache.usedAllocation).coerceAtLeast(0),
        maxTimeNs = limits.maxTimeNs?.let { (it - owner.cache.usedTimeNs).coerceAtLeast(0) },
    )
    val budget = ContinuationBudget(remaining, cancellation)
    budget.phase = ContinuationPhase.VERIFY
    var checked: ReconstructedCertificate? = null
    var decline: ContinuationDecline? = null
    try {
        budget.step()
        if (original.exactState !== owner.frame.source) throw ContinuationStop(ContinuationDecline.RESUME_KEY)
        val primal = continued.witness?.let { owner.frame.liftPrimal(it.primal, budget) }
        val ray = continued.conflict?.let { conflict ->
            budget.step(owner.model.m.toLong(), owner.model.m.toLong() * 32L)
            val values = MutableList(owner.model.m) { BigFraction.ZERO }
            for (index in conflict.rows.indices) {
                budget.step()
                values[conflict.rows[index]] = budget.fraction(conflict.multipliers[index].negated())
            }
            owner.frame.liftMultipliers(values, ray = true, budget)
        }
        val defaults = ReconstructionLimits()
        checked = verifyRationalCertificate(
            original,
            sourcePrimal = primal,
            ray = ray,
            cancellation = remaining.maxTimeNs?.let {
                cancellation or Cancellation.until(TimeSource.Monotonic.markNow() + (it - budget.elapsedNs).nanoseconds)
            } ?: cancellation,
            limits = defaults.copy(
                maxWork = minOf(defaults.maxWork, (remaining.maxWork - budget.work).coerceAtLeast(0)),
                maxAllocation = minOf(
                    defaults.maxAllocation,
                    (remaining.maxAllocation - budget.allocation).coerceAtLeast(0),
                ),
                maxBits = minOf(defaults.maxBits, remaining.maxBits),
            ),
        )
        budget.step()
        if (checked.witness == null && checked.conflict == null) {
            decline = when (checked.metrics.decline) {
                ReconstructionDecline.CANCELLED -> ContinuationDecline.CANCELLED
                ReconstructionDecline.WORK -> ContinuationDecline.WORK
                ReconstructionDecline.ALLOCATION -> ContinuationDecline.ALLOCATION
                ReconstructionDecline.BITS -> ContinuationDecline.BITS
                ReconstructionDecline.DIMENSION -> ContinuationDecline.DIMENSION
                else -> ContinuationDecline.CANDIDATE
            }
        }
    } catch (stop: ContinuationStop) {
        decline = stop.reason
    }
    val work = budget.work + (checked?.metrics?.work ?: 0L)
    val allocation = budget.allocation + (checked?.metrics?.allocation ?: 0L)
    owner.cache.accountInput(work, allocation, budget.elapsedNs)
    val accepted = checked.takeIf { decline == null }
    val verification = ExactContinuationMetrics(
        eligible = metrics.eligible,
        phase = ContinuationPhase.VERIFY,
        work = work,
        allocation = allocation,
        elapsedNs = budget.elapsedNs,
        checks = if (checked == null) 0 else 1,
        decline = decline,
        success = accepted?.witness != null || accepted?.conflict != null,
        workByPhase = mapOf(ContinuationPhase.VERIFY to work),
        allocationByPhase = mapOf(ContinuationPhase.VERIFY to allocation),
    )
    return LpContinuationVerification(
        accepted?.witness,
        accepted?.conflict,
        accepted?.conflictSupport,
        combineContinuationMetrics(metrics, verification).copy(checks = metrics.checks + verification.checks),
        checked?.pointRejection,
    )
}
