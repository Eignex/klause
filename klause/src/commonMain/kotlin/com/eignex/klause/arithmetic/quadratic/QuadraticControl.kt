package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.util.ArithmeticExecution
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

internal enum class QuadraticStop { CANCELLED, WORK_LIMIT, MATRIX_LIMIT }
internal class QuadraticInterrupted(val reason: QuadraticStop) : RuntimeException()

internal class QuadraticControl(
    cancellation: ExecutionControl = ExecutionControl.Never,
    private val maximumWork: Long? = null,
    private val maximumMatrixCells: Long? = 262144,
    val execution: ArithmeticExecution = ArithmeticExecution(cancellation) {
        throw QuadraticInterrupted(QuadraticStop.CANCELLED)
    },
) {
    var work: Long = 0
        private set

    init {
        require(maximumWork == null || maximumWork >= 0)
        require(maximumMatrixCells == null || maximumMatrixCells >= 0)
    }

    fun poll() = execution.checkpoint()

    inline fun <T> operation(quantum: Int = 64, body: () -> T): T = execution.operation(quantum, body)

    fun step() {
        if (execution.unchecked) debit() else meteredStep()
    }

    private fun meteredStep() = execution.step { debit() }

    fun steps(units: Int) {
        require(units >= 0)
        val admitted = minOf(
            units.toLong(),
            maximumWork?.let { (it - work).coerceAtLeast(0L) } ?: Long.MAX_VALUE,
        ).toInt()
        execution.advance(admitted) { count -> work += minOf(count.toLong(), Long.MAX_VALUE - work) }
        if (admitted < units) throw QuadraticInterrupted(QuadraticStop.WORK_LIMIT)
    }

    private fun debit() {
        if (maximumWork != null && work >= maximumWork) throw QuadraticInterrupted(QuadraticStop.WORK_LIMIT)
        if (work != Long.MAX_VALUE) work++
    }

    fun exactStep(
        first: BigFraction,
        second: BigFraction = BigFraction.ZERO,
        third: BigFraction = BigFraction.ZERO,
        fourth: BigFraction = BigFraction.ZERO,
    ) {
        step()
        if (execution.batching && bits(first) + bits(second) + bits(third) + bits(fourth) > 4096) {
            execution.boundary()
        }
    }

    private fun bits(value: BigFraction): Long =
        maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength()).toLong()

    fun matrix(size: Int) {
        execution.boundary()
        step()
        require(size >= 0)
        if (maximumMatrixCells != null && size.toLong() * size > maximumMatrixCells) {
            throw QuadraticInterrupted(QuadraticStop.MATRIX_LIMIT)
        }
    }
}
