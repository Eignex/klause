package com.eignex.klause.lp.engine

internal data class LpLayoutWeight(val retained: Long, val retired: Long) {
    init {
        require(retained >= 0L && retired >= 0L)
    }

    fun warrantsCompaction(retainedOverhead: Long = 0L): Boolean =
        retired > 0L && retainedOverhead >= 0L && retired >= retained && retired - retained >= retainedOverhead

    fun retireColumns(count: Int): LpLayoutWeight {
        require(count >= 0)
        return copy(
            retained = retained - count.toLong() * LpLayoutStorage.COLUMN_UNITS,
            retired = retired + count.toLong() * LpLayoutStorage.COLUMN_UNITS,
        )
    }
}

// Units count stored numeric slots and premise entries, using the exact model's key-size convention.
internal class LpLayoutStorage private constructor(
    private val nonzeros: LongArray,
    private val rowUnits: LongArray,
    val columnUnits: Long,
) {
    val total: Long = columnUnits + rowUnits.sum()

    fun weights(rows: LpScopedRows): LpLayoutWeight {
        require(rows.size == rowUnits.size)
        var retained = columnUnits
        var retired = 0L
        for (row in rowUnits.indices) {
            if (rows.row(row).active || rows.row(row).suspendedAt != null) retained += rowUnits[row]
            else retired += rowUnits[row]
        }
        return LpLayoutWeight(retained, retired)
    }

    fun withRows(rows: List<ExactLpRow>): LpLayoutStorage {
        require(rows.size == rowUnits.size)
        val next = units(nonzeros, rows)
        return if (next.contentEquals(rowUnits)) this else LpLayoutStorage(nonzeros, next, columnUnits)
    }

    companion object {
        const val COLUMN_UNITS = 16L

        fun of(model: ExactLpModel): LpLayoutStorage {
            val counts = LongArray(model.m)
            for (column in 0 until model.n) for (entry in model.columnEntries(column)) counts[entry.row]++
            return LpLayoutStorage(counts, units(counts, List(model.m) { model.row(it) }), model.n * COLUMN_UNITS)
        }

        private fun units(nonzeros: LongArray, rows: List<ExactLpRow>): LongArray = LongArray(rows.size) {
            16L + nonzeros[it] * 3L + (rows[it].premises?.size ?: 0L)
        }
    }
}
