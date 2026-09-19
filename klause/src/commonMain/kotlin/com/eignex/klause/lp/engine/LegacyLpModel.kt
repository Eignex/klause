package com.eignex.klause.lp.engine

import com.ionspin.kotlin.bignum.integer.BigInteger

// Stored normalized Long/IEEE data is authoritative; probe sides are absent, origins stay finite.
internal fun LpModel.authoritativeModel(): ExactLpModel? {
    exactState?.let { return it.model }
    if (!finiteExactInput()) return null
    val dv = doubleView
    val integralRows = BooleanArray(m) { true }
    val rowOrigins = MutableList(m) { exactRhs(it) }
    for (column in 0 until n) {
        forEachRationalColumn(column) { row, value ->
            if (!value.isZero) {
                if (colContinuous[column] || value.den != BigInteger.ONE) integralRows[row] = false
                rowOrigins[row] += value * exactShift(column)
            }
        }
    }
    for (row in 0 until m) integralRows[row] = integralRows[row] && (rowOrigins[row].den == BigInteger.ONE)
    return ExactLpModel(
        List(n) { column ->
            buildList {
                if (dv == null) {
                    forEachInColumn(column) { row, value -> add(ExactLpEntry(row, ExactLpNumber.of(value))) }
                } else {
                    forEachInColumnD(column) { row, value -> add(ExactLpEntry(row, ExactLpNumber.ofIeee(value))) }
                }
            }
        },
        dv?.rhs?.map(ExactLpNumber::ofIeee) ?: rhs.map(ExactLpNumber::of),
        List(numVars) { column ->
            ExactLpColumn(
                legacyBounds(column),
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
                rowPremises[row]?.let { premise ->
                    ExactLpPremises(
                        premise.vars.indices.map {
                            ExactLpPremise(
                                premise.vars[it],
                                premise.isUpper[it],
                                ExactLpNumber.of(premise.thresholds[it]),
                            )
                        },
                        premise.boolLits.toList(),
                    )
                },
            )
        },
        ExactLpObjective(
            dv?.cost?.map(ExactLpNumber::ofIeee) ?: cost.map(ExactLpNumber::of),
            dv?.let { ExactLpNumber.ofIeee(it.objConstant) } ?: ExactLpNumber.of(objConstant),
            sense = sense,
        ),
    )
}

private fun LpModel.legacyBounds(column: Int): ExactLpBounds = ExactLpBounds(
    if (column < n && probeClampedLo[column]) {
        null
    } else {
        ExactLpSide(
            ExactLpNumber.of(0L),
            strict = column >= n && rowStrict[column - n],
        )
    },
    if (hasFiniteUpper(column) && (column >= n || !probeClampedHi[column])) {
        ExactLpSide(doubleView?.let { ExactLpNumber.ofIeee(it.upper[column]) } ?: ExactLpNumber.of(upper[column]))
    } else {
        null
    },
)
