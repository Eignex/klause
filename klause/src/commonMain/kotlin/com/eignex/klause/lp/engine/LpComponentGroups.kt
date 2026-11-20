package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList

internal class LpComponentGroups(
    val columns: Array<IntArrayList>,
    val rows: Array<IntArrayList>,
    val blocks: Int,
) {
    fun coarsen(
        model: LpModel,
        rowIndex: LpRowIndex,
        minimumCells: Long,
        token: ExecutionControl,
        projection: LpProjectionMeter?,
    ): LpComponentGroups? {
        require(minimumCells >= 0L)
        if (minimumCells == 0L || blocks < 2) return this
        projection?.reserve(8L * blocks + 1L, 4L * blocks, matrix = true)
        val groupOf = IntArray(blocks)
        var groups = 0
        var pending = -1
        var pendingRows = 0L
        var pendingColumns = 0L
        for (block in 0 until blocks) {
            if (token()) return null
            val rowCount = rows[block].size.toLong()
            val columnCount = columns[block].size.toLong()
            if (rowCount * (columnCount + rowCount + 1L) >= minimumCells) {
                groupOf[block] = groups++
                continue
            }
            if (pending < 0) {
                pending = groups++
                pendingRows = 0L
                pendingColumns = 0L
            }
            groupOf[block] = pending
            pendingRows += rowCount
            pendingColumns += columnCount
            if (pendingRows * (pendingColumns + pendingRows + 1L) >= minimumCells) pending = -1
        }
        if (groups == blocks) return this
        val children = groups + if (columns.size > blocks) 1 else 0
        projection?.reserve(3L * model.n + 2L * model.m + blocks,
            4L * model.n + 128L * children + 8L * (model.n.toLong() + model.m), matrix = true)
        val owner = IntArray(model.n) { -1 }
        for (block in 0 until blocks) {
            if (token()) return null
            columns[block].forEach { owner[it] = groupOf[block] }
        }
        val nextColumns = Array(children) { IntArrayList() }
        val nextRows = Array(children) { IntArrayList() }
        // Global index order preserves the sorted coordinate maps required by exact restriction.
        for (column in owner.indices) {
            if (token()) return null
            if (owner[column] >= 0) nextColumns[owner[column]].add(column)
        }
        for (row in 0 until model.m) {
            if (token()) return null
            val from = rowIndex.rowPtr[row]
            val group = if (from == rowIndex.rowPtr[row + 1]) groups else owner[rowIndex.colIdx[from]]
            nextRows[group].add(row)
        }
        projection?.reserve(1L, 64L, matrix = true)
        return LpComponentGroups(nextColumns, nextRows, groups)
    }
}
