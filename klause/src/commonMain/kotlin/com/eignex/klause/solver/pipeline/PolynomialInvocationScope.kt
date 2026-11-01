package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.theory.qfnra.PolynomialTheoryInvocation
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stream.Mutex

internal class PolynomialInvocationScope(
    private val service: PolynomialTheoryService,
    private val params: PolynomialTheoryParams,
) {
    private val native = QuadraticNativeScope(params)
    private val lock = Mutex()
    private var decline: PolynomialTheoryResource? = null
    val resource: PolynomialTheoryResource? get() = lock.withLock { decline }
    val stats: PolynomialTheoryStats get() = native.polynomialStats
    val stopped: Boolean get() = native.stopped
    val cancellation: Cancellation get() = native.polynomial

    fun workerCancellation(slot: PortfolioWorkerSlot): Cancellation =
        native.worker(slot.index, params.maximumWork?.let(slot::nativeAllowance)).polynomial or slot.cancellation

    fun worker(slot: PortfolioWorkerSlot): PolynomialTheoryInvocation {
        val limit = params.maximumWork?.let(slot::nativeAllowance)
        val scope = native.worker(slot.index, limit)
        val invocation = service.invocation(QfNraParams(
            scope.polynomial or slot.cancellation, maximumWork = null,
            polynomialLimits = PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells),
        ))
        slot.own(AutoCloseable {
            scope.observe(invocation.stats)
            invocation.lastUnknown?.resource?.let { limited ->
                scope.decline(limited)
                lock.withLock { if (decline == null) decline = scope.resource }
            }
            invocation.close()
        })
        return invocation
    }
}
