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
)

internal class BasisExtensionAdapter(private val factory: (SparseMatrix) -> BasisSolver = { KotlinBasisSolver(it) }) {
    fun transfer(
        oldSolver: BasisSolver,
        newMatrix: SparseMatrix,
        intendedBasis: IntArray,
        logicalColumns: IntArray,
        extension: BasisExtension,
    ): BasisTransferAttempt {
        require(intendedBasis.size == newMatrix.rows)
        require(intendedBasis.all { it in 0 until newMatrix.cols })
        validateLogicals(newMatrix, logicalColumns)
        val before = oldSolver.basisOperationWork
        val extended = try {
            oldSolver.extend(newMatrix, extension)
        } catch (_: BasisArithmeticException) {
            return BasisTransferAttempt(null, true, operationDelta(before, oldSolver.basisOperationWork))
        }
        val units = operationDelta(before, oldSolver.basisOperationWork)
        if (extended == null) return BasisTransferAttempt(null, false, units)
        val headings = translate(extended.basis, logicalColumns)
        if (!headings.contentEquals(intendedBasis)) {
            extended.solver.close()
            return BasisTransferAttempt(null, false, units)
        }
        return BasisTransferAttempt(
            BasisReplacement(
                extended.solver,
                headings,
                extended.basis.columns,
                extended.basis.unitRows,
                transferred = true,
                transferArithmeticDeclined = false,
            ),
            arithmeticDeclined = false,
            workUnits = units,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun replacement(
        oldSolver: BasisSolver,
        newMatrix: SparseMatrix,
        intendedBasis: IntArray,
        logicalColumns: IntArray,
        extension: BasisExtension? = null,
    ): BasisReplacement? {
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
                val headings = translate(extended.basis, logicalColumns)
                if (headings.contentEquals(intendedBasis)) {
                    return BasisReplacement(
                        extended.solver,
                        headings,
                        extended.basis.columns,
                        extended.basis.unitRows,
                        transferred = true,
                        transferArithmeticDeclined = false,
                    )
                }
                extended.solver.close()
            }
        }
        val fresh = factory(newMatrix)
        var accepted = false
        var failure: Throwable? = null
        try {
            if (!fresh.refactorize(intendedBasis)) return null
            val result = BasisReplacement(
                fresh,
                intendedBasis,
                intendedBasis,
                IntArray(newMatrix.rows) { -1 },
                transferred = false,
                transferArithmeticDeclined = arithmeticDeclined,
            )
            accepted = true
            return result
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

    private fun operationDelta(before: BasisOperationWork?, after: BasisOperationWork?): Long? {
        if (
            before == null || after == null || !before.complete || !after.complete ||
            before.saturated || after.saturated || after.units < before.units
        ) {
            return null
        }
        return after.units - before.units
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
