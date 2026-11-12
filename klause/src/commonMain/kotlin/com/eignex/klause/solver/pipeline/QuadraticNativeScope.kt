package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.solver.resources.WorkCounter
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.AtomicCells
import com.eignex.util.concurrent.Mutex
import com.eignex.util.concurrent.SerialCells

internal class QuadraticNativeScope private constructor(
    private val params: PolynomialTheoryParams,
    private val ledger: Ledger,
    private val root: QuadraticNativeScope?,
    private val maximumWorkerWork: Long?,
) {
    constructor(params: PolynomialTheoryParams, concurrent: Boolean = true) :
        this(params, Ledger(if (concurrent) Concurrency.Relaxed else Concurrency.None), null, null)

    private val stop = params.timeout or params.cancellation
    private val counters = Counters(ledger.concurrency)
    private val workers = mutableMapOf<Int, QuadraticNativeScope>()
    private val representationDecline = ledger.cells.newReference<PolynomialTheoryResource?>(null)
    val resource: PolynomialTheoryResource? get() = representationDecline.load()
    val declinedResource: PolynomialTheoryResource? get() = resource ?: ledger.lock.withLock {
        workers.values.toList()
    }.firstNotNullOfOrNull { it.resource }
    val work: Long get() = if (root == null) ledger.counters.work else counters.work
    val numericalWork: Long get() = (if (root == null) ledger.counters else counters).quadraticWork.value
    val polynomialStats: PolynomialTheoryStats get() = (if (root == null) ledger.counters else counters).stats()
    val exhausted: Boolean get() = params.maximumWork?.let { ledger.counters.work >= it } == true ||
        maximumWorkerWork?.let { counters.work >= it } == true
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

    fun observe(stats: QfNraStats) {
        counters.observe(stats)
        ledger.counters.observe(stats)
    }

    fun decline(resource: PolynomialResource) {
        representationDecline.store(when (resource) {
            PolynomialResource.DENSE_COEFFICIENTS -> PolynomialTheoryResource.DenseCoefficients
            PolynomialResource.MATRIX_CELLS -> PolynomialTheoryResource.MatrixCells
            PolynomialResource.DEGREE_OVERFLOW -> PolynomialTheoryResource.DegreeOverflow
        })
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
            if (quadratic) {
                counters.quadraticWork.add(units)
                ledger.counters.quadraticWork.add(units)
            } else {
                counters.polynomialWork.add(units)
                ledger.counters.polynomialWork.add(units)
            }
            stop.charge(units)
        }
    }

    private class Ledger(val concurrency: Concurrency) {
        val cells = if (concurrency == Concurrency.None) SerialCells else AtomicCells
        val lock = Mutex(concurrent = concurrency != Concurrency.None)
        val counters = Counters(concurrency)
    }

    private class Counters(concurrency: Concurrency) {
        val polynomialWork = WorkCounter(concurrency)
        val quadraticWork = WorkCounter(concurrency)
        private val projected = WorkCounter(concurrency)
        private val lifted = WorkCounter(concurrency)
        private val dyadicAttempts = WorkCounter(concurrency, -1L)
        private val dyadicBases = WorkCounter(concurrency, -1L)
        private val coupledWorkDeclines = WorkCounter(concurrency, -1L)
        private val dyadicPreExtractionDeclines = WorkCounter(concurrency, -1L)
        val work: Long get() = sum(polynomialWork.value, quadraticWork.value)

        fun observe(stats: QfNraStats) {
            projected.add(stats.projectedPolynomials)
            lifted.add(stats.liftedCells)
            stats.dyadicAttempts?.let(dyadicAttempts::add)
            stats.dyadicBases?.let(dyadicBases::add)
            stats.coupledWorkDeclines?.let(coupledWorkDeclines::add)
            stats.dyadicPreExtractionDeclines?.let(dyadicPreExtractionDeclines::add)
        }

        fun stats() = PolynomialTheoryStats(projected.value, lifted.value, polynomialWork.value,
            dyadicAttempts.value.takeIf { it >= 0L }, dyadicBases.value.takeIf { it >= 0L },
            coupledWorkDeclines.value.takeIf { it >= 0L }, dyadicPreExtractionDeclines.value.takeIf { it >= 0L })
    }
}

private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
