package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal fun LpModel.leafShape(cancellation: Cancellation, onWork: (Long) -> Unit): String {
    fun charge(units: Long): Boolean {
        onWork(units)
        cancellation.charge(units)
        return !cancellation()
    }
    if (n !in 0..10000 || m !in 0..10000) return "DIMENSION"
    if (!charge(n.toLong() + m + 1L)) return "CANCELLED"
    var entries = 0L
    for (column in 0 until n) {
        if (!charge(1L)) return "CANCELLED"
        entries += exactState?.model?.columnEntries(column)?.size?.toLong()
            ?: doubleView?.let { (it.colPtr[column + 1] - it.colPtr[column]).toLong() }
            ?: (csc.colPtr[column + 1] - csc.colPtr[column]).toLong()
        if (entries > 65536L) return "ENTRIES"
    }
    if (!charge(6L * n + 14L * m + 8L * entries)) return "CANCELLED"
    val active = BooleanArray(m) { exactState?.rows?.row(it)?.active ?: true }
    val rows = Array(m) { ArrayList<LeafShapeTerm>() }
    var fixedColumns = 0
    for (column in 0 until n) {
        if (!charge(1L)) return "CANCELLED"
        if (fixed(column)) {
            fixedColumns++
            continue
        }
        forEachRationalColumn(column) { row, value ->
            if (!active[row] || value.isZero) return@forEachRationalColumn
            val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
            if (bits > 4096) return "SCALAR"
            if (!charge(2L + bits / 64L)) return "CANCELLED"
            rows[row].add(LeafShapeTerm(column, value))
        }
    }
    var activeCount = 0
    var zero = 0
    var single = 0
    var pair = 0
    val distinct = HashSet<List<LeafShapeTerm>>()
    for (row in rows.indices) {
        if (!charge(rows[row].size.toLong() + 1L)) return "CANCELLED"
        if (!active[row]) continue
        activeCount++
        val terms = rows[row]
        when (terms.size) {
            0 -> {
                zero++
                continue
            }
            1 -> single++
            2 -> pair++
        }
        val reversed = terms.first().value < BigFraction.ZERO
        distinct.add(terms.map { if (reversed) it.copy(value = it.value.negated()) else it })
    }
    if (cancellation()) return "CANCELLED"
    val duplicates = activeCount - zero - distinct.size
    return "N_${n}_M_${m}_A_${activeCount}_F_${fixedColumns}_Z_${zero}_S_${single}_P_${pair}" +
        "_U_${distinct.size}_D_$duplicates"
}

private data class LeafShapeTerm(val column: Int, val value: BigFraction)
