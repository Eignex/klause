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

    fun solve(
        model: LpModel,
        cancellation: Cancellation = Cancellation.Never,
        workLimit: Long = 0L,
        observer: LpCertificationObserver? = null,
        counterResults: LpCounterResults? = null,
        floatAccept: ((FloatLpResult) -> Boolean)? = null,
        floatOffset: Double = 0.0,
        refinementLimits: LpRefinementLimits = LpRefinementLimits(),
    ): CertifiedLpResult {
        check(!closed) { "LP solve session is closed" }
        require(workLimit >= 0L)
        val state = requireNotNull(model.exactState) { "retained solves require immutable exact authority" }
        if (cancellation()) return CertifiedLpResult(null, null, null, null, null, false, { null })
        try {
            val previous = solver
            val retained = previous?.adopt(state, cancellation) == true
            val current = if (retained) {
                checkNotNull(previous)
            } else {
                solver = null
                previous?.close()
                if (cancellation()) return CertifiedLpResult(null, null, null, null, null, false, { null })
                newRetainedLpSolver(model, cancellation, componentSplit, context.engineFactory, pricing, workLimit)
                    .also { it.deferUnscaledSourceDiagnostics(); solver = it }
            }
            val result = try {
                if (retained) current.resolveBounds(LpFloatAllowance(workLimit, 0)) else current.solve()
            } finally {
                observer?.observeSolve(current.lastMetrics, current is ComponentLpSolverCapability)
            }
            return certification.certify(model, current, result, cancellation, observer, counterResults,
                refinementLimits, floatAccept, floatOffset)
        } catch (primary: Throwable) {
            try {
                releaseSolvers()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
            throw primary
        }
    }

    fun releaseSolvers() {
        val previous = solver
        solver = null
        val proofs = certification
        certification = LpCertificationSession(context, pricing)
        var failure: Throwable? = null
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
