package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisExtension
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.koblas.SparseMatrix

internal class BasisReplacement(
    val solver: BasisSolver,
    sourceHeadings: IntArray,
    ownerColumns: IntArray,
    ownerUnitRows: IntArray,
    val transferred: Boolean,
    val transferArithmeticDeclined: Boolean,
) {
    val sourceHeadings = sourceHeadings.copyOf()
    val ownerBasis = BasisRepair(ownerColumns, ownerUnitRows)
}

internal class BasisTransferAttempt(
    val replacement: BasisReplacement?,
    val arithmeticDeclined: Boolean,
    val workUnits: Long?,
    val workComplete: Boolean,
)

internal class BasisReplacementAttempt(
    val replacement: BasisReplacement?,
    val workUnits: Long?,
    val workComplete: Boolean,
)

internal data class BasisAttemptWork(val units: Long?, val complete: Boolean)

private class BasisOperationDelta(val units: Long?, val complete: Boolean)

internal class BasisExtensionAdapter(private val factory: (SparseMatrix) -> BasisSolver = { KotlinBasisSolver(it) }) {
    var lastAttemptWork: BasisAttemptWork? = null
        private set

    @Suppress("TooGenericExceptionCaught")
    fun transfer(
        oldSolver: BasisSolver,
        newMatrix: SparseMatrix,
        intendedBasis: IntArray,
        logicalColumns: IntArray,
        extension: BasisExtension,
    ): BasisTransferAttempt {
        lastAttemptWork = null
        require(intendedBasis.size == newMatrix.rows)
        require(intendedBasis.all { it in 0 until newMatrix.cols })
        validateLogicals(newMatrix, logicalColumns)
        val before = oldSolver.basisOperationWork
        val extended = try {
            oldSolver.extend(newMatrix, extension)
        } catch (_: BasisArithmeticException) {
            val work = operationDelta(before, oldSolver.basisOperationWork)
            remember(work)
            return BasisTransferAttempt(null, true, work.units, work.complete)
        }
        if (extended == null) {
            val work = operationDelta(before, oldSolver.basisOperationWork)
            remember(work)
            return BasisTransferAttempt(null, false, work.units, work.complete)
        }
        var accepted = false
        var failure: Throwable? = null
        try {
            val work = operationDelta(before, oldSolver.basisOperationWork)
            remember(work)
            val headings = translate(extended.basis, logicalColumns)
            if (!headings.contentEquals(intendedBasis)) {
                return BasisTransferAttempt(null, false, work.units, work.complete)
            }
            val result = BasisTransferAttempt(
                BasisReplacement(
                    extended.solver,
                    headings,
                    extended.basis.columns,
                    extended.basis.unitRows,
                    transferred = true,
                    transferArithmeticDeclined = false,
                ),
                arithmeticDeclined = false,
                workUnits = work.units,
                workComplete = work.complete,
            )
            accepted = true
            return result
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!accepted) closeRejected(extended.solver, failure)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun replacement(
        oldSolver: BasisSolver,
        newMatrix: SparseMatrix,
        intendedBasis: IntArray,
        logicalColumns: IntArray,
        extension: BasisExtension? = null,
    ): BasisReplacement? = replacementAttempt(
        oldSolver,
        newMatrix,
        intendedBasis,
        logicalColumns,
        extension,
    ).replacement

    @Suppress("TooGenericExceptionCaught")
    fun replacementAttempt(
        oldSolver: BasisSolver,
        newMatrix: SparseMatrix,
        intendedBasis: IntArray,
        logicalColumns: IntArray,
        extension: BasisExtension? = null,
    ): BasisReplacementAttempt {
        lastAttemptWork = null
        require(intendedBasis.size == newMatrix.rows)
        require(intendedBasis.all { it in 0 until newMatrix.cols })
        validateLogicals(newMatrix, logicalColumns)
        var arithmeticDeclined = false
        if (extension != null) {
            val extended = try {
                oldSolver.extend(newMatrix, extension)
            } catch (_: BasisArithmeticException) {
                arithmeticDeclined = true
                null
            }
            if (extended != null) {
                var accepted = false
                var failure: Throwable? = null
                try {
                    val headings = translate(extended.basis, logicalColumns)
                    if (headings.contentEquals(intendedBasis)) {
                        val result = BasisReplacement(
                            extended.solver,
                            headings,
                            extended.basis.columns,
                            extended.basis.unitRows,
                            transferred = true,
                            transferArithmeticDeclined = false,
                        )
                        accepted = true
                        return BasisReplacementAttempt(result, null, false)
                    }
                } catch (primary: Throwable) {
                    failure = primary
                    throw primary
                } finally {
                    if (!accepted) closeRejected(extended.solver, failure)
                }
            }
        }
        val fresh = factory(newMatrix)
        var accepted = false
        var failure: Throwable? = null
        try {
            val factorized = try {
                fresh.refactorize(intendedBasis)
            } catch (_: BasisArithmeticException) {
                false
            }
            val work = fresh.basisOperationWork
            val workUnits = work?.takeUnless { it.saturated }?.units
            val workComplete = work != null && work.complete && !work.saturated
            lastAttemptWork = BasisAttemptWork(workUnits, workComplete)
            if (!factorized) return BasisReplacementAttempt(null, workUnits, workComplete)
            val result = BasisReplacement(
                fresh,
                intendedBasis,
                intendedBasis,
                IntArray(newMatrix.rows) { -1 },
                transferred = false,
                transferArithmeticDeclined = arithmeticDeclined,
            )
            accepted = true
            return BasisReplacementAttempt(result, workUnits, workComplete)
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!accepted) closeRejected(fresh, failure)
        }
    }

    private fun validateLogicals(matrix: SparseMatrix, columns: IntArray) {
        require(columns.size == matrix.rows)
        require(columns.all { it in 0 until matrix.cols })
        require(columns.toSet().size == columns.size)
        for (row in columns.indices) {
            var count = 0
            matrix.forEachInColumn(columns[row]) { actualRow, value ->
                require(actualRow == row && value.toBits() == 1.0.toBits()) {
                    "logical column ${columns[row]} is not the unit for row $row"
                }
                count++
            }
            require(count == 1) { "logical column ${columns[row]} is not the unit for row $row" }
        }
    }

    private fun translate(basis: BasisRepair, logicalColumns: IntArray): IntArray = IntArray(basis.columns.size) {
        if (basis.columns[it] >= 0) basis.columns[it] else logicalColumns[basis.unitRows[it]]
    }

    private fun operationDelta(before: BasisOperationWork?, after: BasisOperationWork?): BasisOperationDelta {
        if (before == null || after == null || before.saturated || after.saturated || after.units < before.units) {
            return BasisOperationDelta(null, false)
        }
        return BasisOperationDelta(after.units - before.units, before.complete && after.complete)
    }

    private fun remember(work: BasisOperationDelta) {
        lastAttemptWork = BasisAttemptWork(work.units, work.complete)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeRejected(solver: BasisSolver, primary: Throwable?) {
        try {
            solver.close()
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            primary.addSuppressed(cleanup)
        }
    }
}
