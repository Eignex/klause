package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.basis.LuPivotPolicy
import com.eignex.koblas.SparseMatrix

internal class SimplexBasisLifecycle(m: Int, numVars: Int) {
    val basicVar = IntArray(m)
    val status = Array(numVars) { VarStatus.BASIC }
    // Factors are acquired lazily because an unsolved engine can be discarded by its consumer.
    var current: BasisSolver? = null
        private set
    var factorized = false
    var kept = false
    var columns = IntArray(0)
    var unitRows = IntArray(0)
    private var retiredWork: BasisOperationWork? = null

    fun solver(
        matrix: SparseMatrix,
        factory: ((SparseMatrix) -> BasisSolver)?,
        entryLimit: Long,
        workLimit: Long,
        cancelled: () -> Boolean,
    ): BasisSolver = current ?: (factory?.invoke(matrix) ?: KotlinBasisSolver(
        matrix, policy = LuPivotPolicy(maxEntries = entryLimit, maxWork = workLimit, cancelled = cancelled),
    )).also { current = it }

    fun invalidate() {
        factorized = false
        kept = false
        columns = IntArray(0)
        unitRows = IntArray(0)
    }

    @Suppress("TooGenericExceptionCaught")
    fun retire(): Throwable? {
        val retiring = current
        var failure: Throwable? = null
        if (retiring != null) {
            try {
                val work = retiring.basisOperationWork ?: BasisOperationWork(complete = false)
                retiredWork = retiredWork?.mergedWith(work) ?: work
            } catch (_: Throwable) {
                val incomplete = BasisOperationWork(complete = false)
                retiredWork = retiredWork?.mergedWith(incomplete) ?: incomplete
            }
            try {
                retiring.close()
            } catch (cleanup: Throwable) {
                failure = cleanup
            }
        }
        current = null
        invalidate()
        return failure
    }

    val operationWork: BasisOperationWork?
        get() {
            val active = current ?: return retiredWork
            val work = active.basisOperationWork ?: BasisOperationWork(complete = false)
            return retiredWork?.mergedWith(work) ?: work
        }
}
