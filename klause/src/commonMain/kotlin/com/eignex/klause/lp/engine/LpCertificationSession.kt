package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.unaryMinus

internal class LpCertificationSession(private val context: LpSolveContext, private val pricing: LpPricingOptions) :
    AutoCloseable {
    private val continuation = LpExactContinuationCache()
    private val checked = LpCounterResults()
    private var anchor: LpScopedSolver? = null

    fun certify(
        model: LpModel,
        solver: LpSolver,
        result: FloatLpResult?,
        cancellation: ExecutionControl,
        observer: LpCertificationObserver?,
        counterResults: LpCounterResults?,
        refinementLimits: LpRefinementLimits = LpRefinementLimits(),
        floatAccept: ((FloatLpResult) -> Boolean)? = null,
        floatOffset: Double = 0.0,
        claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    ): CertifiedLpResult {
        val observation = observer?.let { LpClaimObservation(claim, it) }
        return certifyWithin(model, solver, result, cancellation, observation, counterResults,
            refinementLimits, floatAccept, floatOffset, claim).also {
            observation?.finish(it, it.certificationReused)
        }
    }

    // The float acceptance step alone: a refused or non-optimal point decides nothing and starts no exact work.
    fun acceptFloat(
        model: LpModel,
        result: FloatLpResult?,
        cancellation: ExecutionControl,
        observer: LpCertificationObserver?,
        floatAccept: (FloatLpResult) -> Boolean,
        floatOffset: Double,
    ): FloatLpResult? {
        if (result == null || !result.optimal || cancellation()) return null
        val timer = LpPhaseTimer(observer, LpSolvePhase.FLOAT_ACCEPTANCE)
        val float = floatOptimum(model, result, floatAccept, floatOffset, cancellation, observer)
        timer.finish(if (float != null) "ACCEPTED" else "REFUSED")
        return float
    }

    private fun certifyWithin(
        model: LpModel,
        solver: LpSolver,
        result: FloatLpResult?,
        cancellation: ExecutionControl,
        observer: LpCertificationObserver?,
        counterResults: LpCounterResults?,
        refinementLimits: LpRefinementLimits,
        floatAccept: ((FloatLpResult) -> Boolean)?,
        floatOffset: Double,
        claim: LpClaim,
    ): CertifiedLpResult {
        // Tolerance semantics accept a float optimum before any exact work; a rejected one falls through to it.
        if ((claim == LpClaim.ATTAINED_OPTIMUM || claim == LpClaim.FEASIBLE_COMPLETION) &&
            floatAccept != null && result != null && result.optimal &&
            !cancellation()
        ) {
            val timer = LpPhaseTimer(observer, LpSolvePhase.FLOAT_ACCEPTANCE)
            val float = floatOptimum(model, result, floatAccept, floatOffset, cancellation, observer)
            timer.finish(if (float != null) "ACCEPTED" else "REFUSED")
            float?.let {
                return CertifiedLpResult(float, null, null, null, null, false, { null }, floatOptimum = float)
            }
        }
        val ladderTimer = LpPhaseTimer(observer, LpSolvePhase.EXACT_LADDER)
        val state = model.exactState
        val refinement = if (claim != LpClaim.BOUND &&
            state != null && solver !is ComponentLpSolverCapability && !cancellation()
        ) {
            val previous = anchor
            val current = if (previous != null && previous.adopt(state, cancellation)) {
                previous
            } else {
                anchor = null
                previous?.close()
                LpScopedSolver(state, context = context, pricing = pricing).also { anchor = it }
            }
            LpRefinementRequest(current, current.refinementCache, refinementLimits)
        } else {
            null
        }
        val certified = certifyLpResult(
            model, solver, result, cancellation, observer, context.certificationPolicy,
            counterResults ?: checked, continuationCache = continuation, refinement = refinement,
            continuationLimits = ExactContinuationLimits(
                startArbitraryPrecision = context.startArbitraryPrecision,
                preserveOverflowCheckpoint = context.preserveOverflowCheckpoint,
            ),
            sparsePointRecovery = context.sparsePointRecovery, claim = claim,
        )
        ladderTimer.finish(certified.verdict.name)
        return certified
    }

    override fun close() {
        val previous = anchor
        anchor = null
        continuation.clear()
        checked.declineStorage()
        previous?.close()
    }
}

// A float optimum the dual check cannot prove under the engine's duals is checked again under the exact duals of
// its own basis: at a dual-degenerate optimum a reduced cost that is exactly zero takes a random sign from any
// inexact duals, and only the exact zero clears a column nothing bounds. A basis whose exact duals leave some
// reduced cost truly wrong-signed, or whose exact duals decline, gets one cleanup: primal pivots from its own basis
// at a pricing tolerance well below the engine's, as a solver re-optimizes when unscaled infeasibilities exceed its
// tolerance. A stop the engine made early moves on to the true optimum in a few pivots; a basis already optimal but
// priced with noisy duals finds nothing to pivot on and is left to the exact ladder, so the pivots are capped.
private fun floatOptimum(
    model: LpModel,
    result: FloatLpResult,
    floatAccept: (FloatLpResult) -> Boolean,
    offset: Double,
    cancellation: ExecutionControl,
    observer: LpCertificationObserver?,
): FloatLpResult? {
    val verdict = provenFloatOptimum(model, result, offset, cancellation, observer)
    if (verdict == FloatDualVerdict.ACCEPTED) return result.takeIf(floatAccept)
    if (verdict == FloatDualVerdict.REFUSED || cancellation()) return null
    val timer = LpPhaseTimer(observer, LpSolvePhase.CLEANUP)
    val cleanup = RevisedSimplex(
        model,
        cancellation,
        iterationLimit = CLEANUP_PIVOTS,
        primalPricingTolerance = CLEANUP_PRICING_TOLERANCE,
    ).use {
        val cleaned = it.solvePrimal(result.basis)
        timer.finish(it.lastTermination?.name ?: "OTHER", it.lastWorkOps, it.lastPivots)
        cleaned
    }
        ?.takeIf { it.optimal && !cancellation() }
        ?: return null
    return cleanup.takeIf {
        provenFloatOptimum(model, it, offset, cancellation, observer) == FloatDualVerdict.ACCEPTED && floatAccept(it)
    }
}

// ACCEPTED when the engine's duals or the basis's exact duals prove the optimum; WRONG_SIGNED when that is left
// undecided by a declined exact solve or refuted by a truly wrong-signed exact reduced cost; REFUSED otherwise.
private fun provenFloatOptimum(
    model: LpModel,
    result: FloatLpResult,
    offset: Double,
    cancellation: ExecutionControl,
    observer: LpCertificationObserver?,
): FloatDualVerdict {
    if (floatDualFeasible(model, result, offset)) return FloatDualVerdict.ACCEPTED
    val timer = LpPhaseTimer(observer, LpSolvePhase.EXACT_DUALS)
    val exact = exactBasisDuals(
        model,
        result.basis,
        result.duals,
        cancellation.shorten(EXACT_DUAL_BUDGET_FRACTION),
    )
    val verdict = exact.duals?.let { floatDualVerdict(model, result, offset, exactDuals = it) }
        ?: FloatDualVerdict.WRONG_SIGNED
    timer.finish(exact.decline?.name ?: verdict.name, steps = exact.steps)
    return verdict
}

private const val EXACT_DUAL_BUDGET_FRACTION: Double = 0.5
private const val CLEANUP_PRICING_TOLERANCE: Double = 1e-12
private const val CLEANUP_PIVOTS: Int = 100
