package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

@Suppress("LongParameterList")
internal class LpRowBound internal constructor(
    private val authority: LpExactState,
    val row: Int,
    val column: Int,
    val upper: Boolean,
    val value: BigFraction,
    val strict: Boolean,
    private val minimum: Boolean,
    private val terms: List<LpRowBoundTerm>,
    private val lattice: Boolean = false,
    private val companionRow: Int = -1,
    private val definingRows: IntArray? = null,
) {
    val supportCount: Int get() = (if (lattice) terms.size * 2 else terms.size - 1) +
        (if (companionRow >= 0) 1 else 0) + (definingRows?.size ?: 0)
    fun belongsTo(model: LpModel): Boolean = model.exactState === authority && authority.rows.row(row).active &&
        (companionRow < 0 || authority.rows.row(companionRow).active) &&
        (definingRows?.all { authority.rows.row(it).active } != false)
    fun premiseRows(): IntArray = (if (companionRow < 0) intArrayOf(row) else intArrayOf(row, companionRow)) +
        (definingRows ?: intArrayOf())

    fun integerBound(): Long? = when {
        upper && strict -> value.ceilLong()?.takeIf { it != Long.MIN_VALUE }?.minus(1L)
        upper -> value.floorLong()
        strict -> value.floorLong()?.takeIf { it != Long.MAX_VALUE }?.plus(1L)
        else -> value.ceilLong()
    }

    fun forEachSupport(action: (Int, Boolean) -> Unit) {
        if (lattice) {
            action(column, !upper)
            for (term in terms) if (term.column != column) {
                action(term.column, true)
                action(term.column, false)
            }
            return
        }
        for (term in terms) if (term.column != column) {
            action(term.column, minimum == (term.coefficient.signum() > 0))
        }
    }

    fun forEachIntegralSupport(action: (Int) -> Unit) {
        if (!lattice) return
        for (term in terms) if (term.column < authority.model.n && authority.model.column(term.column).integral) {
            action(term.column)
        }
    }
}

internal data class LpRowBoundTerm(val column: Int, val coefficient: BigFraction, val integerCoefficient: Long?)

internal fun mixedRowBounds(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
    meter: MixedCertificateMeter = MixedCertificateMeter(cancellation),
    affectedColumns: IntArray? = null,
): List<LpRowBound> {
    val initialWork = meter.work
    val completed = ArrayList<LpRowBound>()
    try {
        meter.attempt(observer, "ROW_BOUNDS") {
            val state = model.exactState ?: return@attempt null
            if (affectedColumns?.isEmpty() == true) return@attempt completed
            meter.reserve(model.m.toLong() * 64L + 128L)
            val rows = Array(model.m) { ArrayList<LpRowBoundTerm>() }
            val mixed = BooleanArray(model.m)
            meter.reserve(model.m.toLong() * 4L + 32L)
            val continuousCounts = IntArray(model.m)
            meter.reserve(model.m.toLong() * 8L + 32L)
            val ordinary = arrayOfNulls<List<LpRowBound>>(model.m)
            val selected = affectedColumns?.let { affectedRowMask(state, it, meter) }
            for (column in 0 until model.n) {
                meter.step()
                if (state.model.column(column).integral) continue
                for (entry in state.model.columnEntries(column)) {
                    meter.step()
                    if (entry.number.value.isZero) continue
                    continuousCounts[entry.row]++
                    if (selected?.get(entry.row) != false) mixed[entry.row] = true
                }
            }
            expandPairedRows(state, continuousCounts, mixed, meter)
            for (column in 0 until model.n) {
                meter.step()
                for (entry in state.model.columnEntries(column)) {
                    meter.step()
                    if (entry.number.value.isZero || !mixed[entry.row]) continue
                    meter.reserve(64L)
                    val integer = entry.number.exactLong()
                    val coefficient = if (integer == null) meter.read(entry.number.value) else entry.number.value
                    rows[entry.row].add(LpRowBoundTerm(column, coefficient, integer))
                }
            }
            for (row in rows.indices) {
                meter.step()
                if (!mixed[row] || !state.rows.row(row).active) continue
                meter.reserve(160L + (rows[row].size + 1L) * 16L)
                val terms = rows[row].toList() + LpRowBoundTerm(model.slackCol(row), BigFraction.ONE, 1L)
                val integral = integralRowBounds(state, row, terms, meter)
                if (integral != null) {
                    ordinary[row] = integral
                    completed.addAll(integral)
                    continue
                }
                val minimum = rowEndpointSum(state, terms, true, meter)
                val maximum = rowEndpointSum(state, terms, false, meter)
                meter.reserve(128L)
                val projected = ArrayList<LpRowBound>()
                for (term in terms) {
                    meter.step()
                    if (term.column >= model.n || !state.model.column(term.column).integral) continue
                    projectRowBound(state, row, term, terms, minimum, true, meter)?.let(projected::add)
                    projectRowBound(state, row, term, terms, maximum, false, meter)?.let(projected::add)
                }
                ordinary[row] = projected
                completed.addAll(projected)
            }
            for (row in rows.indices) {
                meter.step()
                if (!mixed[row] || !state.rows.row(row).active) continue
                val bounds = ordinary[row] ?: continue
                meter.reserve(160L + (rows[row].size + 1L) * 16L)
                val terms = rows[row].toList() + LpRowBoundTerm(model.slackCol(row), BigFraction.ONE, 1L)
                completed.addAll(rowLatticeBounds(state, row, terms, bounds, meter))
            }
            pairedRowBounds(state, rows, continuousCounts, meter, completed)
            definedRowBounds(state, rows, continuousCounts, mixed, meter, completed)
            completed
        }
    } catch (_: LpProjectionStop) {
        // Each retained projection has already checked its complete row and endpoint support.
    }
    observer?.observe(LpCertifier.INTEGER, completed.isNotEmpty(), LpCertifierCost.Metered(meter.work - initialWork))
    return if (cancellation()) emptyList() else completed
}

private fun affectedRowMask(state: LpExactState, columns: IntArray, meter: MixedCertificateMeter): BooleanArray {
    meter.reserve(state.model.m.toLong() + 32L)
    val selected = BooleanArray(state.model.m)
    for (column in columns) {
        meter.step()
        if (column !in 0 until state.model.n) continue
        for (entry in state.model.columnEntries(column)) {
            meter.step()
            if (!entry.number.value.isZero) selected[entry.row] = true
        }
    }
    return selected
}

internal data class RowEndpointSum(val value: BigFraction, val missing: Int, val strict: Int)

internal fun rowEndpointSum(
    state: LpExactState,
    terms: List<LpRowBoundTerm>,
    minimum: Boolean,
    meter: MixedCertificateMeter,
): RowEndpointSum {
    var sum = BigFraction.ZERO
    var missing = 0
    var strict = 0
    for (term in terms) {
        meter.step()
        val side = rowEndpoint(state, term, minimum)
        if (side == null) {
            missing++
        } else {
            val value = meter.read(side.number.value)
            val contribution = meter.read(term.coefficient * value)
            sum = meter.read(sum + contribution)
            if (side.strict) strict++
        }
    }
    return RowEndpointSum(sum, missing, strict)
}

private fun rowEndpoint(state: LpExactState, term: LpRowBoundTerm, minimum: Boolean): ExactLpSide? {
    val bounds = state.model.column(term.column).bounds
    return if (minimum == (term.coefficient.signum() > 0)) bounds.lower else bounds.upper
}

@Suppress("LongParameterList")
internal fun projectRowBound(
    state: LpExactState,
    row: Int,
    term: LpRowBoundTerm,
    terms: List<LpRowBoundTerm>,
    sum: RowEndpointSum,
    minimum: Boolean,
    meter: MixedCertificateMeter,
    rhs: BigFraction = state.model.rhs(row).value,
    companionRow: Int = -1,
    definingRows: IntArray? = null,
): LpRowBound? {
    val ownSide = rowEndpoint(state, term, minimum)
    if (sum.missing > if (ownSide == null) 1 else 0) return null
    val ownValue = ownSide?.let { meter.read(term.coefficient * meter.read(it.number.value)) } ?: BigFraction.ZERO
    val others = meter.read(sum.value - ownValue)
    val residual = meter.read(meter.read(rhs) - others)
    val upper = minimum == (term.coefficient.signum() > 0)
    val strict = sum.strict > if (ownSide?.strict == true) 1 else 0
    val bounds = state.model.column(term.column).bounds
    val previous = if (upper) bounds.upper else bounds.lower
    if (previous != null) {
        val contribution = meter.read(term.coefficient * meter.read(previous.number.value))
        val weaker = if (minimum) meter.less(contribution, residual) else meter.less(residual, contribution)
        if (weaker ||
            (contribution == residual && (!strict || previous.strict))
        ) return null
    }
    val normalized = meter.read(residual * meter.read(term.coefficient.reciprocal()))
    val origin = meter.read(state.model.column(term.column).origin.value)
    val value = meter.read(normalized + origin)
    meter.reserve(96L)
    return LpRowBound(state, row, term.column, upper, value, strict, minimum, terms,
        companionRow = companionRow, definingRows = definingRows)
}
