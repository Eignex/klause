package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.util.Cancellation

internal enum class QuadraticStop { CANCELLED, WORK_LIMIT, MATRIX_LIMIT }
internal class QuadraticInterrupted(val reason: QuadraticStop) : RuntimeException()

internal class QuadraticControl(
    private val cancellation: Cancellation = Cancellation.Never,
    private val maximumWork: Long? = null,
    private val maximumMatrixCells: Long? = 262144,
) {
    var work: Long = 0
        private set

    init {
        require(maximumWork == null || maximumWork >= 0)
        require(maximumMatrixCells == null || maximumMatrixCells >= 0)
    }

    fun poll() {
        if (cancellation()) throw QuadraticInterrupted(QuadraticStop.CANCELLED)
    }

    fun step() {
        poll()
        if (maximumWork != null && work >= maximumWork) throw QuadraticInterrupted(QuadraticStop.WORK_LIMIT)
        if (work != Long.MAX_VALUE) work++
        cancellation.charge(1)
        poll()
    }

    fun matrix(size: Int) {
        step()
        require(size >= 0)
        if (maximumMatrixCells != null && size.toLong() * size > maximumMatrixCells) {
            throw QuadraticInterrupted(QuadraticStop.MATRIX_LIMIT)
        }
    }
}
