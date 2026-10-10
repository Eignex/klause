package com.eignex.klause.formats.mps

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal fun MpsModel.integralDefinitions(source: MpsSourceNumbers): Array<IntDomain?> {
    val definitions = arrayOfNulls<IntDomain>(variables.size)
    for ((rowIndex, row) in constraints.withIndex()) {
        if (row.indicator != null) continue
        val (lower, upper) = source.constraintBounds[rowIndex]
        val rhs = lower?.fraction ?: continue
        if (upper?.fraction != rhs) continue
        val realEntries = row.indices.indices.filter { !variables[row.indices[it]].integer }
        if (realEntries.size != 1 || row.indices.size < 2) continue
        val entry = realEntries.single()
        val variable = row.indices[entry]
        if (variables[variable].lower != null || variables[variable].upper != null) continue
        val coefficients = source.constraintCoefficients[rowIndex]
        val pivot = coefficients[entry].fraction
        if (pivot.isZero) continue
        val constant = rhs * pivot.reciprocal()
        if (constant.den != BIG_ONE) continue
        var minimum = constant
        var maximum = constant
        var integral = true
        for (term in row.indices.indices) {
            if (term == entry) continue
            val coefficient = (coefficients[term].fraction * pivot.reciprocal()).negated()
            if (coefficient.isZero) continue
            val (lo, hi) = source.variableBounds[row.indices[term]]
            if (coefficient.den != BIG_ONE || lo == null || hi == null ||
                lo.fraction.den != BIG_ONE || hi.fraction.den != BIG_ONE ||
                !lo.fraction.num.fitsLong() || !hi.fraction.num.fitsLong() || lo.fraction > hi.fraction
            ) {
                integral = false
                break
            }
            val a = coefficient * lo.fraction
            val b = coefficient * hi.fraction
            minimum += if (a < b) a else b
            maximum += if (a < b) b else a
        }
        // The defining equality proves integrality; a bounded range keeps its finite projection exact.
        if (integral && minimum.num.fitsLong() && maximum.num.fitsLong()) {
            definitions[variable] = IntDomain(minimum.num.toLongExact(), maximum.num.toLongExact())
        }
    }
    return definitions
}
