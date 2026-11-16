package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisFactorCopy
import com.eignex.klause.simplex.basis.LuPivotPolicy
import com.eignex.koblas.SparseMatrix

// Immutable coefficient identity and normalized headings bind the copy to its numerical source.
internal class SimplexFactorCopy(
    private val sourceMatrix: SparseMatrix,
    private val copy: BasisFactorCopy,
    headings: IntArray,
    columns: IntArray,
    unitRows: IntArray,
) {
    private val sourceHeadings = headings.copyOf()
    private val sourceColumns = columns.copyOf()
    private val sourceUnits = unitRows.copyOf()

    fun install(
        owner: SimplexBasisLifecycle,
        matrix: SparseMatrix,
        structuralColumns: Int,
        entryLimit: Long,
        workLimit: Long,
        cancelled: () -> Boolean,
    ): Boolean {
        if (owner.current != null || matrix !== sourceMatrix || matrix.rows != copy.dimension ||
            structuralColumns !in 0..matrix.cols || sourceHeadings.size != copy.dimension ||
            sourceColumns.size != copy.dimension || sourceUnits.size != copy.dimension ||
            !sourceHeadings.contentEquals(owner.basicVar)
        ) return false
        for (slot in sourceHeadings.indices) {
            val column = sourceColumns[slot]
            val unit = sourceUnits[slot]
            if (column < -1 || unit < -1 || column >= matrix.cols || unit >= copy.dimension ||
                (column >= 0) == (unit >= 0) ||
                (if (column >= 0) column else structuralColumns + unit) != sourceHeadings[slot]
            ) return false
        }
        val solver = copy.create(sourceColumns, sourceUnits,
            LuPivotPolicy(maxEntries = entryLimit, maxWork = workLimit, cancelled = cancelled),
            copy.additionalWork) ?: return false
        owner.installCopy(solver, sourceColumns, sourceUnits)
        return true
    }
}
