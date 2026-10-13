package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.theory.qfnra.PolynomialTheoryInvocation
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.util.Cancellation

internal class PolynomialInvocationScope(
    private val service: PolynomialTheoryService,
    private val params: PolynomialTheoryParams,
) {
    private val native = QuadraticNativeScope(params)
    val stats: PolynomialTheoryStats get() = native.polynomialStats
    val stopped: Boolean get() = native.stopped
    val cancellation: Cancellation get() = native.polynomial

    fun worker(slot: PortfolioWorkerSlot): PolynomialTheoryInvocation {
        val limit = params.maximumWork?.let(slot::nativeAllowance)
        val scope = native.worker(slot.index, limit)
        val invocation = service.invocation(QfNraParams(
            scope.polynomial or slot.cancellation, maximumWork = null,
            polynomialLimits = PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells),
        ))
        slot.own(AutoCloseable {
            native.observe(invocation.stats)
            invocation.close()
        })
        return invocation
    }
}
