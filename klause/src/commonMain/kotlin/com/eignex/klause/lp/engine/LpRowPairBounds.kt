package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal fun expandPairedRows(
    state: LpExactState,
    continuousCounts: IntArray,
    selected: BooleanArray,
    meter: MixedCertificateMeter,
) {
    for (column in 0 until state.model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        val entries = state.model.columnEntries(column)
        if (entries.size < 2) continue
        var relevant = false
        for (entry in entries) {
            meter.step()
            if (continuousCounts[entry.row] == 1 && selected[entry.row] &&
                state.rows.row(entry.row).active && !entry.number.value.isZero
            ) relevant = true
        }
        if (!relevant) continue
        for (entry in entries) {
            meter.step()
            if (continuousCounts[entry.row] == 1 && state.rows.row(entry.row).active &&
                !entry.number.value.isZero
            ) selected[entry.row] = true
        }
    }
}

internal fun pairedRowBounds(
    state: LpExactState,
    rows: Array<ArrayList<LpRowBoundTerm>>,
    continuousCounts: IntArray,
    meter: MixedCertificateMeter,
    completed: MutableList<LpRowBound>,
) {
    for (column in 0 until state.model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        val entries = state.model.columnEntries(column)
        if (entries.size < 2) continue
        meter.reserve(entries.size.toLong() * 8L + 32L)
        val candidates = ArrayList<ExactLpEntry>()
        for (entry in entries) {
            meter.step()
            if (continuousCounts[entry.row] == 1 && rows[entry.row].isNotEmpty() &&
                state.rows.row(entry.row).active && !entry.number.value.isZero
            ) candidates.add(entry)
        }
        var attempts = 0
        pairs@ for (first in 0 until candidates.lastIndex) {
            for (second in first + 1..candidates.lastIndex) {
                meter.step()
                if (attempts++ == 8) break@pairs
                projectPair(state, candidates[first], candidates[second], rows, meter, completed)
            }
        }
    }
}

private fun projectPair(
    state: LpExactState,
    first: ExactLpEntry,
    second: ExactLpEntry,
    rows: Array<ArrayList<LpRowBoundTerm>>,
    meter: MixedCertificateMeter,
    completed: MutableList<LpRowBound>,
) {
    val multiplier = meter.multiply(meter.read(first.number.value).negated(),
        meter.read(meter.read(second.number.value).reciprocal()))
    val rhs = meter.add(meter.read(state.model.rhs(first.row).value),
        meter.multiply(multiplier, meter.read(state.model.rhs(second.row).value)))
    meter.reserve((rows[first.row].size.toLong() + rows[second.row].size + 2L) * 32L + 320L)
    val left = rows[first.row] + LpRowBoundTerm(state.model.n + first.row, BigFraction.ONE, 1L)
    val right = rows[second.row] + LpRowBoundTerm(state.model.n + second.row, BigFraction.ONE, 1L)
    val terms = ArrayList<LpRowBoundTerm>()
    var i = 0
    var j = 0
    while (i < left.size || j < right.size) {
        meter.step()
        val a = left.getOrNull(i)
        val b = right.getOrNull(j)
        val column = minOf(a?.column ?: Int.MAX_VALUE, b?.column ?: Int.MAX_VALUE)
        var coefficient = BigFraction.ZERO
        if (a?.column == column) {
            coefficient = meter.read(a.coefficient)
            i++
        }
        if (b?.column == column) {
            coefficient = meter.add(coefficient, meter.multiply(multiplier, b.coefficient))
            j++
        }
        if (!coefficient.isZero) {
            meter.reserve(64L)
            terms.add(LpRowBoundTerm(column, coefficient, null))
        }
    }
    val minimum = rowEndpointSum(state, terms, true, meter)
    val maximum = rowEndpointSum(state, terms, false, meter)
    for (term in terms) {
        meter.step()
        if (term.column >= state.model.n || !state.model.column(term.column).integral) continue
        projectRowBound(state, first.row, term, terms, minimum, true, meter, rhs, second.row)?.let(completed::add)
        projectRowBound(state, first.row, term, terms, maximum, false, meter, rhs, second.row)?.let(completed::add)
    }
}
