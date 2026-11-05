package com.eignex.klause.lp.relaxation

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal sealed interface CutColumnBinding

private class BoundCutColumns(val map: CutSourceMap, val layout: CutColumnLayout) : CutColumnBinding

internal fun CutColumnBinding.layoutFor(map: CutSourceMap): CutColumnLayout? =
    (this as? BoundCutColumns)?.takeIf { it.map === map }?.layout

internal class CutColumnLayout private constructor(private val kinds: IntArray, private val ids: IntArray) {
    val storageUnits: Long get() = kinds.size.toLong() * 2L + 8L

    fun bind(map: CutSourceMap, cancellation: Cancellation, onWork: (Long) -> Unit): CutColumnBinding? {
        var work = 1L
        var matches = kinds.size == map.columnCount
        if (matches) for (index in kinds.indices) {
            if (cancellation()) {
                matches = false
                break
            }
            work++
            val column = map.column(index)
            if ((column?.source?.kind?.ordinal ?: -1) != kinds[index] ||
                (column?.source?.id ?: -1) != ids[index] || !canonical(column)
            ) {
                matches = false
                break
            }
        }
        onWork(work + if (matches) 4L else 0L)
        return if (matches && !cancellation()) BoundCutColumns(map, this) else null
    }

    companion object {
        private fun canonical(column: CutColumnSource?): Boolean = column == null ||
            (column.scale == BigFraction.ONE && column.offset.isZero)

        fun capture(
            map: CutSourceMap,
            maximumUnits: Long,
            cancellation: Cancellation,
            onWork: (Long) -> Unit,
        ): CutColumnBinding? {
            var work = 1L
            val layout: CutColumnLayout
            try {
                val size = map.columnCount
                val units = size.toLong() * 2L + 8L
                if (units > minOf(maximumUnits, 262_144L) || cancellation()) return null
                for (index in 0 until size) {
                    if (cancellation()) return null
                    work++
                    if (!canonical(map.column(index))) return null
                }
                work += units
                val kinds = IntArray(size)
                val ids = IntArray(size)
                for (index in 0 until size) {
                    if (cancellation()) return null
                    work += 2L
                    val column = map.column(index)
                    kinds[index] = column?.source?.kind?.ordinal ?: -1
                    ids[index] = column?.source?.id ?: -1
                }
                layout = CutColumnLayout(kinds, ids)
            } finally {
                onWork(work)
            }
            onWork(4L)
            return if (cancellation()) null else BoundCutColumns(map, layout)
        }
    }
}
