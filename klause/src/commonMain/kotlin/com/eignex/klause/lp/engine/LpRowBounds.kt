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
) {
    val supportCount: Int get() = terms.size - 1
    fun belongsTo(model: LpModel): Boolean = model.exactState === authority && authority.rows.row(row).active

    fun integerBound(): Long? = when {
        upper && strict -> value.ceilLong()?.takeIf { it != Long.MIN_VALUE }?.minus(1L)
        upper -> value.floorLong()
        strict -> value.floorLong()?.takeIf { it != Long.MAX_VALUE }?.plus(1L)
        else -> value.ceilLong()
    }

    fun forEachSupport(action: (Int, Boolean) -> Unit) {
        for (term in terms) if (term.column != column) {
            action(term.column, minimum == (term.coefficient.signum() > 0))
        }
    }
}

internal data class LpRowBoundTerm(val column: Int, val coefficient: BigFraction)

internal fun mixedRowBounds(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
    meter: MixedCertificateMeter = MixedCertificateMeter(cancellation),
): List<LpRowBound> {
    val initialWork = meter.work
    val completed = ArrayList<LpRowBound>()
    try {
        meter.attempt(observer, "ROW_BOUNDS") {
            val state = model.exactState ?: return@attempt null
            meter.reserve(model.m.toLong() * 64L + 128L)
            val rows = Array(model.m) { ArrayList<LpRowBoundTerm>() }
            val mixed = BooleanArray(model.m)
            for (column in 0 until model.n) {
                meter.step()
                for (entry in state.model.columnEntries(column)) {
                    meter.step()
                    if (entry.number.value.isZero) continue
                    meter.reserve(32L)
                    rows[entry.row].add(LpRowBoundTerm(column, meter.read(entry.number.value)))
                    if (!state.model.column(column).integral) mixed[entry.row] = true
                }
            }
            for (row in rows.indices) {
                meter.step()
                if (!mixed[row] || !state.rows.row(row).active) continue
                meter.reserve(32L + (rows[row].size + 1L) * 8L)
                val terms = rows[row].toList() + LpRowBoundTerm(model.slackCol(row), BigFraction.ONE)
                val minimum = rowEndpointSum(state, terms, true, meter)
                val maximum = rowEndpointSum(state, terms, false, meter)
                for (term in terms) {
                    meter.step()
                    if (term.column >= model.n || !state.model.column(term.column).integral) continue
                    projectRowBound(state, row, term, terms, minimum, true, meter)?.let(completed::add)
                    projectRowBound(state, row, term, terms, maximum, false, meter)?.let(completed::add)
                }
            }
            completed
        }
    } catch (_: LpProjectionStop) {
        // Each retained projection has already checked its complete row and endpoint support.
    }
    observer?.observe(LpCertifier.INTEGER, completed.isNotEmpty(), LpCertifierCost.Metered(meter.work - initialWork))
    return if (cancellation()) emptyList() else completed
}

private data class RowEndpointSum(val value: BigFraction, val missing: Int, val strict: Int)

private fun rowEndpointSum(
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
private fun projectRowBound(
    state: LpExactState,
    row: Int,
    term: LpRowBoundTerm,
    terms: List<LpRowBoundTerm>,
    sum: RowEndpointSum,
    minimum: Boolean,
    meter: MixedCertificateMeter,
): LpRowBound? {
    val ownSide = rowEndpoint(state, term, minimum)
    if (sum.missing > if (ownSide == null) 1 else 0) return null
    val ownValue = ownSide?.let { meter.read(term.coefficient * meter.read(it.number.value)) } ?: BigFraction.ZERO
    val others = meter.read(sum.value - ownValue)
    val residual = meter.read(meter.read(state.model.rhs(row).value) - others)
    val normalized = meter.read(residual * meter.read(term.coefficient.reciprocal()))
    val origin = meter.read(state.model.column(term.column).origin.value)
    val value = meter.read(normalized + origin)
    val upper = minimum == (term.coefficient.signum() > 0)
    val strict = sum.strict > if (ownSide?.strict == true) 1 else 0
    val bounds = state.model.column(term.column).bounds
    val previous = if (upper) bounds.upper else bounds.lower
    if (previous != null) {
        val current = meter.read(previous.number.value + origin)
        val weaker = if (upper) meter.less(current, value) else meter.less(value, current)
        if (weaker ||
            (value == current && (!strict || previous.strict))
        ) return null
    }
    meter.reserve(80L)
    return LpRowBound(state, row, term.column, upper, value, strict, minimum, terms)
}
