package com.eignex.klause.lp.engine

import com.eignex.util.numbers.BIG_ONE

private const val COMPACT_SCALAR_RADIUS = 8

// Stored normalized Long/IEEE data is authoritative; probe sides are absent, origins stay finite.
internal fun LpModel.authoritativeModel(): ExactLpModel? = authoritativeModel(null)

internal fun LpModel.authoritativeModel(
    structuralColumn: ((Int) -> ExactLpColumn?)?,
): ExactLpModel? {
    exactState?.let { return it.model }
    if (!finiteExactInput()) return null
    val dv = doubleView
    val integralRows = logicalIntegrality()
    val compactScalars = if (dv == null) arrayOfNulls<ExactLpNumber>(2 * COMPACT_SCALAR_RADIUS + 1) else null
    return ExactLpModel(
        List(n) { column ->
            buildList {
                if (compactScalars != null) {
                    forEachInColumn(column) { row, value ->
                        val number = if (value in -COMPACT_SCALAR_RADIUS.toLong()..COMPACT_SCALAR_RADIUS.toLong()) {
                            val index = value.toInt() + COMPACT_SCALAR_RADIUS
                            compactScalars[index] ?: ExactLpNumber.of(value).also { compactScalars[index] = it }
                        } else ExactLpNumber.of(value)
                        add(ExactLpEntry(row, number))
                    }
                } else {
                    forEachInColumnD(column) { row, value -> add(ExactLpEntry(row, ExactLpNumber.ofIeee(value))) }
                }
            }
        },
        dv?.let { view ->
            List(m) { view.shifts.value.rhs(it)?.let(ExactLpNumber::of) ?: ExactLpNumber.ofIeee(view.rhs[it]) }
        } ?: rhs.map(ExactLpNumber::of),
        List(numVars) { column ->
            // The adapter validates prepared columns against this normalized layout before import.
            (if (column < n && dv == null) structuralColumn?.invoke(column) else null) ?: ExactLpColumn(
                authoritativeBounds(column),
                origin = if (column >= n) {
                    ExactLpNumber.of(0L)
                } else {
                    dv?.let { ExactLpNumber.ofIeee(it.loShift[column]) } ?: ExactLpNumber.of(loShift[column])
                },
                integral = if (column < n) !colContinuous[column] else integralRows[column - n],
                tag = if (column < n) tag[column] else -1,
            )
        },
        List(m) { row ->
            ExactLpRow(
                rowGlobal[row],
                rowStrict[row],
                rowPremises[row]?.toExact(),
            )
        },
        ExactLpObjective(
            dv?.cost?.map(ExactLpNumber::ofIeee) ?: cost.map(ExactLpNumber::of),
            dv?.let { view ->
                val exact = view.exactObjConstant()
                if (exact == exactDouble(view.objConstant)) {
                    ExactLpNumber.ofIeee(view.objConstant)
                } else {
                    ExactLpNumber.of(exact)
                }
            } ?: ExactLpNumber.of(objConstant),
            sense = sense,
        ),
    )
}

private fun LpModel.logicalIntegrality(): BooleanArray {
    val integral = BooleanArray(m) { true }
    if (doubleView == null) {
        // Integral RHS and shifts preserve integrality unless a continuous column contributes.
        for (column in 0 until n) {
            if (!colContinuous[column]) continue
            forEachInColumn(column) { row, value -> if (value != 0L) integral[row] = false }
        }
        return integral
    }
    val rowOrigins = MutableList(m) { exactRhs(it) }
    for (column in 0 until n) {
        forEachRationalColumn(column) { row, value ->
            if (!value.isZero) {
                if (colContinuous[column] || value.den != BIG_ONE) integral[row] = false
                rowOrigins[row] += value * exactShift(column)
            }
        }
    }
    for (row in 0 until m) integral[row] = integral[row] && rowOrigins[row].den == BIG_ONE
    return integral
}

private fun LpModel.authoritativeBounds(column: Int): ExactLpBounds = ExactLpBounds(
    if (column < n && probeClampedLo[column]) {
        null
    } else {
        ExactLpSide(
            ExactLpNumber.of(0L),
            strict = column >= n && rowStrict[column - n],
        )
    },
    if (hasFiniteUpper(column) && (column >= n || !probeClampedHi[column])) {
        ExactLpSide(
            doubleView?.let { view ->
                view.shifts.value.upper(column)?.let(ExactLpNumber::of) ?: ExactLpNumber.ofIeee(view.upper[column])
            } ?: ExactLpNumber.of(upper[column]),
        )
    } else {
        null
    },
)
