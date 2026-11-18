package com.eignex.klause.lp.engine

import kotlin.math.abs
import kotlin.math.nextUp

private const val MAX_CUT_SLACK_TERMS = 4096

internal fun roundingCutSlack(proof: TableauCutProvenance): LpRowSlack {
    val view = FloatEvidenceModel(proof.model)
    val sides = SideSlack(view, LpBoundSlack.NONE)
    val weights = LinkedHashMap<Double, Double>()
    fun include(bands: LpRowSlack, multiplier: Double): Boolean {
        for (index in 0 until bands.size) {
            val weight = bands.multiplier(index)
            if (weight == 0.0) continue
            val magnitude = bands.magnitude(index)
            weights[magnitude] = ((weights[magnitude] ?: 0.0) + (weight * multiplier).nextUp()).nextUp()
            if (weights.size > MAX_CUT_SLACK_TERMS) return false
        }
        return true
    }
    // Shifting each negative integer premise by floor(delta) costs at most one CG rounding unit,
    // or two divisor-sized MIR units. A nonzero shift also covers the final coefficient gcd rounding.
    val rounding = if (proof.mir) (2.0 * proof.divisor.toDouble().nextUp() + 1.0).nextUp() else 1.0
    for (column in proof.columns) {
        if (!include(sides.bands(column.column, view.lower(column.column)), rounding)) return UNPRICED_CUT_SLACK
    }
    for (row in proof.rows) {
        // Equality logical columns are omitted from the cut and may move in either direction.
        val multiplier = if (row.relation == Relation.EQ) (rounding + abs(row.multiplier.toDouble()).nextUp()).nextUp()
        else rounding
        if (!include(sides.bands(view.n + row.index, 0.0), multiplier)) return UNPRICED_CUT_SLACK
    }
    return LpRowSlack(weights.values.toDoubleArray(), weights.keys.toDoubleArray())
}
