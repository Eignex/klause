package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.util.ArithmeticExecution
import com.eignex.klause.util.Cancellation

internal enum class QuadraticStop { CANCELLED, WORK_LIMIT, MATRIX_LIMIT }
internal class QuadraticInterrupted(val reason: QuadraticStop) : RuntimeException()

internal class QuadraticControl(
    cancellation: Cancellation = Cancellation.Never,
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

    inline fun <T> operation(quantum: Int = 8, body: () -> T): T = execution.operation(quantum, body)

    fun step() {
        execution.step {
            if (maximumWork != null && work >= maximumWork) throw QuadraticInterrupted(QuadraticStop.WORK_LIMIT)
            if (work != Long.MAX_VALUE) work++
        }
    }

    fun matrix(size: Int) {
        execution.boundary()
        step()
        require(size >= 0)
        if (maximumMatrixCells != null && size.toLong() * size > maximumMatrixCells) {
            throw QuadraticInterrupted(QuadraticStop.MATRIX_LIMIT)
        }
    }
}
