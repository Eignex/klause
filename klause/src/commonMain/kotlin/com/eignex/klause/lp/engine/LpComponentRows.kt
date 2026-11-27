package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntDisjointSet

internal class LpComponentRowPlan(
    val model: LpModel,
    val parts: List<LpNeighborhood>,
    val ancestors: List<IntArray>,
    val columnMap: IntArray,
    val rowMap: IntArray,
)

internal class LpComponentRows(
    private val model: LpModel,
    private val parts: List<LpNeighborhood>,
    private val state: LpExactState,
    private val token: ExecutionControl,
    private val projection: LpProjectionMeter,
) {
    private class Group(val ancestors: IntArray, val added: IntArray, val columns: Int, val rows: Int)

    var decline = LpComponentEditDecline.UNAVAILABLE
        private set

    fun plan(admission: LpComponentAdmission?): LpComponentRowPlan? {
        if (!compatible()) return null
        val source = state.model
        val working = state.ownerWorkingModel(projection) ?: return null
        val rows = working.rowIndex(projection)
        val groups = groups(rows) ?: return null
        val blocks = groups.count { it.columns > 0 }
        if (blocks < 2) return declined(LpComponentEditDecline.CROSS_BLOCK)
        if (admission != null) {
            projection.reserve(groups.size.toLong() + 1L, 64L, matrix = false)
            val shape = LpComponentShape(
                blocks,
                source.m.toLong() * (source.n.toLong() + source.m + 1L),
                groups.sumOf { it.rows.toLong() * (it.columns.toLong() + it.rows + 1L) },
                128L * (rows.colIdx.size.toLong() + source.n + source.m + groups.size) + 4L * source.m,
            )
            if (!admission.accepts(shape)) return declined(LpComponentEditDecline.ADMISSION)
        }
        projection.reserve(source.n.toLong() + source.m, 4L * (source.n.toLong() + source.m), matrix = true)
        val columnMap = IntArray(source.n)
        val rowMap = IntArray(source.m) { -1 }
        val nextParts = ArrayList<LpNeighborhood>(groups.size)
        for (group in groups) {
            projection.poll()
            val part = restrict(working, group, rowMap) ?: return null
            nextParts += part
        }
        projection.reserve(groups.size * 16L * (32 - groups.size.countLeadingZeroBits()),
            groups.size * 128L, matrix = false)
        return LpComponentRowPlan(working, nextParts, groups.map { it.ancestors }, columnMap, rowMap)
    }

    private fun compatible(): Boolean {
        val previous = model.exactState ?: return false
        val source = state.model
        if (token()) return reject(LpComponentEditDecline.STOPPED)
        if (source.n != model.n) return reject(LpComponentEditDecline.COLUMNS)
        if (source.m <= model.m) return reject(LpComponentEditDecline.ROW_COUNT)
        for (column in 0 until model.n) {
            val values = source.columnEntries(column)
            val prefix = previous.model.columnEntries(column)
            projection.reserve(minOf(values.size.toLong(), prefix.size.toLong() + 1L) + 1L, 0L, matrix = true)
            if (values.size < prefix.size || prefix.indices.any { values[it] != prefix[it] } ||
                (values.size > prefix.size && values[prefix.size].row < model.m)
            ) {
                return reject(LpComponentEditDecline.MATRIX)
            }
        }
        projection.reserve(source.m.toLong() + source.n, (source.m.toLong() + source.n) * 16L, matrix = true)
        if ((0 until model.m).any { previous.rows.row(it).id != state.rows.row(it).id }) {
            return reject(LpComponentEditDecline.ROW_IDENTITY)
        }
        return true
    }

    private fun groups(rows: LpRowIndex): List<Group>? {
        val source = state.model
        projection.reserve(parts.size * 8L + source.n + source.m, parts.size * 256L + 16L * source.n + 16L * source.m,
            matrix = true)
        val owner = IntArray(source.n) { -1 }
        for (index in parts.indices) for (column in parts[index].cols) owner[column] = index
        val partition = IntDisjointSet(parts.size)
        val added = Array(parts.size) { IntArrayList() }
        for (row in model.m until source.m) {
            projection.poll()
            val from = rows.rowPtr[row]
            val to = rows.rowPtr[row + 1]
            if (from == to) return declined(LpComponentEditDecline.ROW_ONLY)
            val first = owner[rows.colIdx[from]]
            if (first < 0) return declined(LpComponentEditDecline.CROSS_BLOCK)
            for (entry in from until to) {
                projection.reserve(1L, 0L, matrix = true)
                val other = owner[rows.colIdx[entry]]
                if (other < 0) return declined(LpComponentEditDecline.CROSS_BLOCK)
                partition.union(first, other)
            }
            added[first].add(row)
        }
        val byRoot = LinkedHashMap<Int, IntArrayList>()
        for (index in parts.indices) byRoot.getOrPut(partition.find(index)) { IntArrayList() }.add(index)
        return byRoot.values.map { ancestors ->
            val appended = IntArrayList()
            ancestors.forEach { ancestor -> added[ancestor].forEach { appended.add(it) } }
            val members = ancestors.toIntArray()
            Group(members, appended.toIntArray().sortedArray(),
                members.sumOf { parts[it].cols.size }, members.sumOf { parts[it].rows.size } + appended.size)
        }
    }

    private fun restrict(working: LpModel, group: Group, scratch: IntArray): LpNeighborhood? {
        val singleton = group.ancestors.singleOrNull()
        if (singleton != null && group.added.isEmpty()) {
            val previous = parts[singleton]
            val rebound = previous.rebindExact(state, token, projection) ?: return declined(
                if (token() || token.admission?.exhausted == true) LpComponentEditDecline.STOPPED
                else LpComponentEditDecline.REBIND_UNAVAILABLE,
            )
            return rebound
        }
        val sortWork = group.columns.toLong() * (32 - group.columns.countLeadingZeroBits()) +
            group.rows.toLong() * (32 - group.rows.countLeadingZeroBits())
        projection.reserve(sortWork + group.columns + group.rows,
            16L * (group.columns.toLong() + group.rows), matrix = true)
        val columns = IntArrayList()
        val selected = IntArrayList()
        for (ancestor in group.ancestors) {
            columns.addAll(parts[ancestor].cols)
            selected.addAll(parts[ancestor].rows)
        }
        selected.addAll(group.added)
        val sortedColumns = IntArrayList().apply { addAll(columns.toIntArray().sortedArray()) }
        val sortedRows = IntArrayList().apply { addAll(selected.toIntArray().sortedArray()) }
        var nonzeros = 0L
        sortedColumns.forEach { nonzeros += state.model.columnEntries(it).size.toLong() }
        projection.reserve(nonzeros * 8L + group.columns + group.rows,
            nonzeros * 128L + (group.columns.toLong() + group.rows) * 128L, matrix = true)
        return working.restrictTo(sortedColumns, sortedRows, null, copyCosts = true, scratch)
    }

    private fun reject(reason: LpComponentEditDecline): Boolean {
        decline = reason
        return false
    }

    private fun declined(reason: LpComponentEditDecline): Nothing? {
        reject(reason)
        return null
    }
}
