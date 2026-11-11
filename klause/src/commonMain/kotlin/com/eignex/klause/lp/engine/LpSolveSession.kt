package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

@Suppress("TooGenericExceptionCaught") // Retire every owner while preserving primary and cleanup failures.
internal class LpSolveSession(
    private val context: LpSolveContext = LpSolveContext.Production,
    private val pricing: LpPricingOptions = LpPricingOptions(),
    private val componentSplit: Boolean = true,
) : AutoCloseable {
    private var solver: RetainedLpSolver? = null
    private var certification = LpCertificationSession(context, pricing)
    private var closed = false
    private var activeSolve: LpSolveCursor<CertifiedLpResult>? = null
    private val queryOwner = context.onQuery?.let { Any() }
    private var querySequence = 0L

    fun solve(
        model: LpModel,
        cancellation: Cancellation = Cancellation.Never,
        workLimit: Long = 0L,
        observer: LpCertificationObserver? = null,
        counterResults: LpCounterResults? = null,
        floatAccept: ((FloatLpResult) -> Boolean)? = null,
        floatOffset: Double = 0.0,
        refinementLimits: LpRefinementLimits = LpRefinementLimits(),
        claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    ): CertifiedLpResult {
        check(!closed) { "LP solve session is closed" }
        check(activeSolve == null) { "LP solve session is suspended" }
        require(workLimit >= 0L)
        try {
            val previous = solver
            val current = prepare(model, cancellation, workLimit)
                ?: return CertifiedLpResult(null, null, null, null, null, false, { null })
            current.configureRecessionCapture(context.certificationPolicy.numericalDeductions && floatAccept != null &&
                (claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.ATTAINED_OPTIMUM))
            observeQuery(model, current, current === previous, workLimit, claim,
                floatOffset, floatAccept != null, refinementLimits)
            val result = try {
                if (current === previous) current.resolveBounds(LpFloatAllowance(workLimit, 0)) else current.solve()
            } finally {
                observer?.observeSolve(current.lastMetrics, current is ComponentLpSolverCapability)
            }
            return certification.certify(model, current, result, cancellation, observer, counterResults,
                numericalLimits(current, workLimit, refinementLimits), floatAccept, floatOffset, claim)
        } catch (primary: Throwable) {
            try {
                releaseSolvers()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
            throw primary
        }
    }

    fun openSolve(
        model: LpModel,
        cancellation: Cancellation = Cancellation.Never,
        workLimit: Long = 0L,
        observer: LpCertificationObserver? = null,
        floatAccept: ((FloatLpResult) -> Boolean)? = null,
        floatOffset: Double = 0.0,
        claim: LpClaim = LpClaim.ATTAINED_OPTIMUM,
    ): LpSolveCursor<CertifiedLpResult> {
        check(!closed && activeSolve == null) { "LP solve session is closed or suspended" }
        require(workLimit >= 0L)
        requireNotNull(model.exactState) { "retained solves require immutable exact authority" }
        return LpSolveCursor<CertifiedLpResult>(completed = { activeSolve = null }) {
            try {
                val previous = solver
                val current = prepare(model, cancellation, workLimit)
                    ?: return@LpSolveCursor cancelledResult()
                current.configureRecessionCapture(context.certificationPolicy.numericalDeductions && floatAccept != null &&
                    (claim == LpClaim.FEASIBLE_COMPLETION || claim == LpClaim.ATTAINED_OPTIMUM))
                observeQuery(model, current, current === previous, workLimit, claim,
                    floatOffset, floatAccept != null, null)
                val stage = solveFloating(current, current === previous, cancellation, observer, workLimit)
                val result = when (stage) {
                    is FloatingStage.Completed -> stage.result
                    FloatingStage.Cancelled -> return@LpSolveCursor cancelledResult()
                }
                if (cancellation()) {
                    return@LpSolveCursor cancelledResult()
                }
                checkpoint(current.lastMetrics.workOps)
                if (cancellation()) return@LpSolveCursor cancelledResult()
                certification.certify(model, current, result, cancellation, observer, null,
                    refinementLimits = numericalLimits(current, workLimit, LpRefinementLimits()),
                    floatAccept = floatAccept, floatOffset = floatOffset, claim = claim)
            } catch (primary: Throwable) {
                try {
                    releaseSolvers()
                } catch (cleanup: Throwable) {
                    if (cleanup !== primary) primary.addSuppressed(cleanup)
                }
                throw primary
            }
        }.also { activeSolve = it }
    }

    private suspend fun LpSolveCursor<CertifiedLpResult>.solveFloating(
        current: RetainedLpSolver,
        retained: Boolean,
        cancellation: Cancellation,
        observer: LpCertificationObserver?,
        workLimit: Long,
    ): FloatingStage {
        var reported = LpSolveMetrics()
        fun report(finished: Boolean) {
            val actual = current.lastMetrics
            val emitted = if (finished) actual else actual.copy(warmHits = 0)
            val delta = emitted - reported
            reported = emitted
            observer?.observeSolveProgress(delta, current is ComponentLpSolverCapability, finished)
        }
        var cancelled = false
        val result = try {
            if (current is ResumableLpSolver) {
                current.openSolve(if (retained) LpFloatAllowance(workLimit, 0) else null).use { numerical ->
                    var step = numerical.advance(shouldPause = { shouldPause(current.currentWork) })
                    while (step is LpSolveStep.Paused) {
                        report(false)
                        pauseAt(current.currentWork)
                        if (cancellation()) {
                            cancelled = true
                            break
                        }
                        step = numerical.advance(shouldPause = { shouldPause(current.currentWork) })
                    }
                    if (cancelled) null else (step as LpSolveStep.Completed<FloatLpResult?>).value
                }
            } else {
                if (retained) current.resolveBounds(LpFloatAllowance(workLimit, 0)) else current.solve()
            }
        } catch (primary: Throwable) {
            try {
                report(true)
            } catch (cleanup: Throwable) {
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
            throw primary
        }
        report(true)
        return if (cancelled) FloatingStage.Cancelled else FloatingStage.Completed(result)
    }

    private sealed interface FloatingStage {
        class Completed(val result: FloatLpResult?) : FloatingStage
        data object Cancelled : FloatingStage
    }

    private fun observeQuery(
        model: LpModel,
        solver: LpSolver,
        retained: Boolean,
        workLimit: Long,
        claim: LpClaim,
        floatOffset: Double,
        hasFloatAcceptance: Boolean,
        refinementLimits: LpRefinementLimits?,
    ) {
        val observer = context.onQuery ?: return
        check(querySequence < Long.MAX_VALUE) { "LP query sequence exhausted" }
        val query = LpQueryObservation(checkNotNull(queryOwner), ++querySequence, model, retained,
            workLimit, claim, floatOffset, hasFloatAcceptance, refinementLimits ?: LpRefinementLimits(),
            pricing, componentSplit) {
            solver.continuationBasis(model)
        }
        try {
            observer(query)
        } finally {
            query.release()
        }
    }

    private fun cancelledResult(): CertifiedLpResult {
        releaseSolvers()
        return CertifiedLpResult(null, null, null, null, null, false, { null })
    }

    private fun numericalLimits(
        current: LpSolver,
        workLimit: Long,
        limits: LpRefinementLimits,
    ): LpRefinementLimits {
        if (!context.certificationPolicy.numericalDeductions || workLimit == 0L) return limits
        var remaining = workLimit - minOf(workLimit, current.lastMetrics.workOps)
        remaining -= minOf(remaining, current.lastMetrics.preparationOps)
        return limits.copy(maxWork = minOf(limits.maxWork, remaining))
    }

    private fun prepare(model: LpModel, cancellation: Cancellation, workLimit: Long): RetainedLpSolver? {
        val state = requireNotNull(model.exactState) { "retained solves require immutable exact authority" }
        if (cancellation()) return null
        val previous = solver
        if (previous?.adopt(state, cancellation) == true) return previous
        solver = null
        previous?.close()
        if (cancellation()) return null
        return newRetainedLpSolver(model, cancellation, componentSplit, context.engineFactory, pricing, workLimit)
            .also { it.deferUnscaledSourceDiagnostics(); solver = it }
    }

    fun releaseSolvers() {
        val pending = activeSolve
        activeSolve = null
        var failure: Throwable? = null
        try {
            if (pending?.isAdvancing != true) pending?.close()
        } catch (cleanup: Throwable) {
            failure = cleanup
        }
        val previous = solver
        solver = null
        val proofs = certification
        certification = LpCertificationSession(context, pricing)
        for (owner in listOfNotNull(previous, proofs)) {
            try { owner.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseSolvers()
    }
}
