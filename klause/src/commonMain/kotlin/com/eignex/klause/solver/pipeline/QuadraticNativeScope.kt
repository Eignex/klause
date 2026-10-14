package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

internal class QuadraticNativeScope private constructor(
    private val params: PolynomialTheoryParams,
    private val ledger: Ledger,
    private val root: QuadraticNativeScope?,
    private val maximumWorkerWork: Long?,
) {
    constructor(params: PolynomialTheoryParams) : this(params, Ledger(), null, null)

    private val stop = params.timeout or params.cancellation
    private var polynomialWork = 0L
    private var quadraticWork = 0L
    private var projected = 0L
    private var lifted = 0L
    private var dyadicAttempts: Long? = null
    private var dyadicBases: Long? = null
    private var coupledWorkDeclines: Long? = null
    private var dyadicPreExtractionDeclines: Long? = null
    private val workers = mutableMapOf<Int, QuadraticNativeScope>()
    private var representationDecline: PolynomialTheoryResource? = null
    val resource: PolynomialTheoryResource? get() = ledger.lock.withLock { representationDecline }
    val declinedResource: PolynomialTheoryResource? get() = ledger.lock.withLock {
        representationDecline ?: workers.values.firstNotNullOfOrNull { it.representationDecline }
    }
    val work: Long get() = ledger.lock.withLock {
        if (root == null) ledger.work else sum(polynomialWork, quadraticWork)
    }
    val numericalWork: Long get() = ledger.lock.withLock {
        if (root == null) ledger.quadraticWork else quadraticWork
    }
    val polynomialStats: PolynomialTheoryStats get() = ledger.lock.withLock {
        if (root == null) PolynomialTheoryStats(ledger.projected, ledger.lifted, ledger.polynomialWork,
            ledger.dyadicAttempts, ledger.dyadicBases, ledger.coupledWorkDeclines, ledger.dyadicPreExtractionDeclines)
        else PolynomialTheoryStats(projected, lifted, polynomialWork,
            dyadicAttempts, dyadicBases, coupledWorkDeclines, dyadicPreExtractionDeclines)
    }
    val exhausted: Boolean get() = ledger.lock.withLock {
        params.maximumWork?.let { ledger.work >= it } == true ||
            maximumWorkerWork?.let { sum(polynomialWork, quadraticWork) >= it } == true
    }
    val stopped: Boolean get() = exhausted || resource != null || root?.resource != null || stop()
    val polynomial: Cancellation = Token(quadratic = false)
    val numerical: Cancellation = Token(quadratic = true)

    init { require(maximumWorkerWork == null || maximumWorkerWork >= 0) }

    fun worker(slotId: Int, maximumWork: Long? = null): QuadraticNativeScope = ledger.lock.withLock {
        require(root == null && slotId >= 0) { "native workers belong to their containing scope" }
        val worker = workers.getOrPut(slotId) { QuadraticNativeScope(params, ledger, this, maximumWork) }
        require(worker.maximumWorkerWork == maximumWork) { "worker native quota cannot change" }
        worker
    }

    fun phaseCancellation(worker: Cancellation, share: Double): Cancellation = (polynomial or worker).shorten(share)

    fun observe(stats: QfNraStats) = ledger.lock.withLock {
        projected = sum(projected, stats.projectedPolynomials)
        lifted = sum(lifted, stats.liftedCells)
        ledger.projected = sum(ledger.projected, stats.projectedPolynomials)
        ledger.lifted = sum(ledger.lifted, stats.liftedCells)
        stats.dyadicAttempts?.let {
            dyadicAttempts = sum(dyadicAttempts ?: 0, it)
            ledger.dyadicAttempts = sum(ledger.dyadicAttempts ?: 0, it)
        }
        stats.dyadicBases?.let {
            dyadicBases = sum(dyadicBases ?: 0, it)
            ledger.dyadicBases = sum(ledger.dyadicBases ?: 0, it)
        }
        stats.coupledWorkDeclines?.let {
            coupledWorkDeclines = sum(coupledWorkDeclines ?: 0, it)
            ledger.coupledWorkDeclines = sum(ledger.coupledWorkDeclines ?: 0, it)
        }
        stats.dyadicPreExtractionDeclines?.let {
            dyadicPreExtractionDeclines = sum(dyadicPreExtractionDeclines ?: 0, it)
            ledger.dyadicPreExtractionDeclines = sum(ledger.dyadicPreExtractionDeclines ?: 0, it)
        }
    }

    fun decline(resource: PolynomialResource) = ledger.lock.withLock {
        representationDecline = when (resource) {
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
            ledger.lock.withLock {
                if (quadratic) {
                    quadraticWork = sum(quadraticWork, units)
                    ledger.quadraticWork = sum(ledger.quadraticWork, units)
                } else {
                    polynomialWork = sum(polynomialWork, units)
                    ledger.polynomialWork = sum(ledger.polynomialWork, units)
                }
            }
            stop.charge(units)
        }
    }

    private class Ledger {
        val lock = Concurrency.Strict.lock()
        var polynomialWork = 0L
        var quadraticWork = 0L
        var projected = 0L
        var lifted = 0L
        var dyadicAttempts: Long? = null
        var dyadicBases: Long? = null
        var coupledWorkDeclines: Long? = null
        var dyadicPreExtractionDeclines: Long? = null
        val work: Long get() = sum(polynomialWork, quadraticWork)
    }
}

private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
