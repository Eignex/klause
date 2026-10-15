package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.IntArrayList

private fun expandDefinedRowSupports(
    state: LpExactState,
    continuousCounts: IntArray,
    selected: BooleanArray,
    meter: MixedCertificateMeter,
) {
    selectDefinedRowConsumers(state, continuousCounts, selected, meter)
    for (column in 0 until state.model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        val entries = state.model.columnEntries(column)
        if (entries.none {
                meter.step()
                continuousCounts[it.row] > 1 && selected[it.row] && state.rows.row(it.row).active
            }) continue
        for (entry in entries) {
            meter.step()
            if (continuousCounts[entry.row] == 1 && fixedLogicalRow(state, entry.row) &&
                !entry.number.value.isZero
            ) selected[entry.row] = true
        }
    }
}

private fun selectDefinedRowConsumers(
    state: LpExactState,
    continuousCounts: IntArray,
    selected: BooleanArray,
    meter: MixedCertificateMeter,
) {
    for (column in 0 until state.model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        val entries = state.model.columnEntries(column)
        if (entries.none {
                meter.step()
                continuousCounts[it.row] == 1 && selected[it.row] && fixedLogicalRow(state, it.row)
            }) continue
        for (entry in entries) {
            meter.step()
            if (continuousCounts[entry.row] > 1 && state.rows.row(entry.row).active) selected[entry.row] = true
        }
    }
}

internal fun definedRowBounds(
    state: LpExactState,
    rows: Array<ArrayList<LpRowBoundTerm>>,
    continuousCounts: IntArray,
    selected: BooleanArray,
    meter: MixedCertificateMeter,
    completed: MutableList<LpRowBound>,
) {
    if (continuousCounts.none {
            meter.step()
            it > 1
        }) return
    prepareDefinedRows(state, rows, continuousCounts, selected, meter)
    meter.reserve(state.model.n.toLong() * 4L + 32L)
    val definitions = IntArray(state.model.n) { -1 }
    for (row in rows.indices) {
        meter.step()
        if (continuousCounts[row] != 1 || !fixedLogicalRow(state, row)) continue
        for (term in rows[row]) {
            meter.step()
            if (!state.model.column(term.column).integral && definitions[term.column] < 0) {
                definitions[term.column] = row
            }
        }
    }
    var attempts = 0
    for (row in rows.indices) {
        meter.step()
        if (continuousCounts[row] < 2 || rows[row].isEmpty() || !state.rows.row(row).active) continue
        if (attempts++ == 8) break
        projectDefinedRow(state, row, rows, definitions, meter, completed)
    }
}

private fun prepareDefinedRows(
    state: LpExactState,
    rows: Array<ArrayList<LpRowBoundTerm>>,
    continuousCounts: IntArray,
    selected: BooleanArray,
    meter: MixedCertificateMeter,
) {
    meter.reserve(state.model.m.toLong() * 2L + 64L)
    val expanded = selected.copyOf()
    expandDefinedRowSupports(state, continuousCounts, expanded, meter)
    val missing = BooleanArray(state.model.m) {
        meter.step()
        expanded[it] && rows[it].isEmpty()
    }
    if (missing.none {
            meter.step()
            it
        }) return
    for (column in 0 until state.model.n) {
        meter.step()
        for (entry in state.model.columnEntries(column)) {
            meter.step()
            if (!missing[entry.row] || entry.number.value.isZero) continue
            meter.reserve(64L)
            val integer = entry.number.exactLong()
            val coefficient = if (integer == null) meter.read(entry.number.value) else entry.number.value
            rows[entry.row].add(LpRowBoundTerm(column, coefficient, integer))
        }
    }
}

private fun fixedLogicalRow(state: LpExactState, row: Int): Boolean {
    if (!state.rows.row(row).active) return false
    val bounds = state.model.column(state.model.n + row).bounds
    val lower = bounds.lower ?: return false
    val upper = bounds.upper ?: return false
    return !lower.strict && !upper.strict && lower.number.value == upper.number.value
}

private fun projectDefinedRow(
    state: LpExactState,
    row: Int,
    rows: Array<ArrayList<LpRowBoundTerm>>,
    definitions: IntArray,
    meter: MixedCertificateMeter,
    completed: MutableList<LpRowBound>,
) {
    meter.reserve(state.model.numVars.toLong() * 8L + rows[row].size * 4L + 128L)
    val coefficients = arrayOfNulls<BigFraction>(state.model.numVars)
    val support = IntArrayList()
    var rhs = meter.read(state.model.rhs(row).value)
    coefficients[state.model.n + row] = BigFraction.ONE
    for (term in rows[row]) {
        meter.step()
        val definition = if (state.model.column(term.column).integral) -1 else definitions[term.column]
        if (definition < 0) {
            coefficients[term.column] = meter.add(coefficients[term.column] ?: BigFraction.ZERO, term.coefficient)
            continue
        }
        if (support.size == 128) return
        support.add(definition)
        val pivot = rows[definition].first {
            meter.step()
            it.column == term.column
        }.coefficient
        val multiplier = meter.multiply(term.coefficient.negated(), meter.read(pivot.reciprocal()))
        rhs = meter.add(rhs, meter.multiply(multiplier, state.model.rhs(definition).value))
        for (entry in rows[definition]) {
            meter.step()
            if (entry.column == term.column) continue
            coefficients[entry.column] = meter.add(coefficients[entry.column] ?: BigFraction.ZERO,
                meter.multiply(multiplier, entry.coefficient))
        }
        coefficients[state.model.n + definition] = multiplier
    }
    if (support.size == 0) return
    meter.reserve(coefficients.size.toLong() * 8L + support.size * 4L + 64L)
    val terms = ArrayList<LpRowBoundTerm>()
    for (column in coefficients.indices) {
        meter.step()
        val coefficient = coefficients[column] ?: continue
        if (coefficient.isZero) continue
        meter.reserve(64L)
        terms.add(LpRowBoundTerm(column, coefficient, null))
    }
    val definingRows = support.toIntArray()
    val minimum = rowEndpointSum(state, terms, true, meter)
    val maximum = rowEndpointSum(state, terms, false, meter)
    for (term in terms) {
        meter.step()
        if (term.column >= state.model.n || !state.model.column(term.column).integral) continue
        projectRowBound(state, row, term, terms, minimum, true, meter, rhs,
            definingRows = definingRows)?.let(completed::add)
        projectRowBound(state, row, term, terms, maximum, false, meter, rhs,
            definingRows = definingRows)?.let(completed::add)
    }
}
