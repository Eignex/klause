package com.eignex.klause.theory.qflra

import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.util.numbers.BigFraction

internal data class FixedSmtColumn(val value: BigFraction, val premise: SearchAtomPremise)

internal data class SmtTermSubstitution(val column: Int, val coefficient: BigFraction, val fixing: FixedSmtColumn)

internal data class NormalizedSmtTerm(
    val coefficients: Map<Int, BigFraction>,
    val scale: BigFraction,
    val offset: BigFraction,
    val substitutions: List<SmtTermSubstitution>,
) {
    fun sourceValue(value: BigFraction): BigFraction = scale * value + offset

    fun bound(threshold: BigFraction): BigFraction = (threshold - offset) * scale.reciprocal()

    fun premise(source: SearchAtomPremise): SearchAtomPremise = if (substitutions.isEmpty()) {
        source
    } else {
        SearchAtomPremise.All(listOf(source) + substitutions.map { it.fixing.premise })
    }
}

internal fun normalizeSmtTerm(
    expression: Map<Int, BigFraction>,
    fixed: Map<Int, FixedSmtColumn> = emptyMap(),
): NormalizedSmtTerm {
    val residual = LinkedHashMap<Int, BigFraction>()
    val substitutions = ArrayList<SmtTermSubstitution>()
    var offset = BigFraction.ZERO
    for ((column, coefficient) in expression.entries.sortedBy { it.key }) {
        if (coefficient.isZero) continue
        val fixing = fixed[column]
        if (fixing == null) {
            residual[column] = coefficient
        } else {
            offset += coefficient * fixing.value
            substitutions += SmtTermSubstitution(column, coefficient, fixing)
        }
    }
    val scale = residual.values.firstOrNull() ?: BigFraction.ONE
    return NormalizedSmtTerm(
        if (scale == BigFraction.ONE) residual else {
            val inverse = scale.reciprocal()
            residual.mapValues { (_, coefficient) -> coefficient * inverse }
        },
        scale,
        offset,
        substitutions,
    )
}
