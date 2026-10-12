package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class QuadraticNativeScope(private val params: PolynomialTheoryParams) {
    private val stop = params.timeout or params.cancellation
    private var polynomialWork = 0L
    private var quadraticWork = 0L
    private var projected = 0L
    private var lifted = 0L
    var resource: PolynomialTheoryResource? = null
        private set

    val work: Long get() = sum(polynomialWork, quadraticWork)
    val numericalWork: Long get() = quadraticWork
    val polynomialStats: PolynomialTheoryStats get() = PolynomialTheoryStats(projected, lifted, polynomialWork)
    val exhausted: Boolean get() = params.maximumWork?.let { work >= it } == true
    val stopped: Boolean get() = exhausted || resource != null || stop()
    val polynomial: Cancellation = Token(quadratic = false)
    val numerical: Cancellation = Token(quadratic = true)

    fun phaseCancellation(worker: Cancellation, share: Double): Cancellation = (polynomial or worker).shorten(share)

    fun observe(stats: QfNraStats) {
        projected = sum(projected, stats.projectedPolynomials)
        lifted = sum(lifted, stats.liftedCells)
    }

    fun decline(resource: PolynomialResource) {
        this.resource = when (resource) {
            PolynomialResource.DENSE_COEFFICIENTS -> PolynomialTheoryResource.DenseCoefficients
            PolynomialResource.MATRIX_CELLS -> PolynomialTheoryResource.MatrixCells
            PolynomialResource.DEGREE_OVERFLOW -> PolynomialTheoryResource.DegreeOverflow
        }
    }

    fun reason(): TerminationReason = when {
        params.timeout() -> TerminationReason.Timeout
        stop() -> TerminationReason.Cancelled
        else -> TerminationReason.BudgetExhausted
    }

    private inner class Token(private val quadratic: Boolean) : Cancellation, WorkMeter {
        override fun isCancelled(): Boolean = stopped
        override fun deadline() = stop.deadline()
        override fun workMeter(): WorkMeter = this
        override fun charge(units: Long) {
            require(units >= 0)
            if (quadratic) quadraticWork = sum(quadraticWork, units)
            else polynomialWork = sum(polynomialWork, units)
            stop.charge(units)
        }
    }

    private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
}
