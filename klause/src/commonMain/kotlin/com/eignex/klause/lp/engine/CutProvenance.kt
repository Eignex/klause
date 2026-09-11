package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal enum class CutSourceKind { INTEGER, BOOLEAN, REAL, TERM }

internal data class CutSource(val kind: CutSourceKind, val id: Int) {
    init {
        require(id >= 0)
    }
}

internal class CutExpression(terms: Map<CutSource, BigFraction>, val constant: BigFraction = BigFraction.ZERO) {
    private val coefficients = terms.filterValues { !it.isZero }.toMap()
    val terms: Map<CutSource, BigFraction> get() = coefficients.toMap()

    override fun equals(other: Any?): Boolean =
        other is CutExpression && coefficients == other.coefficients && constant == other.constant

    override fun hashCode(): Int = 31 * coefficients.hashCode() + constant.hashCode()

    fun value(assignment: (CutSource) -> BigFraction): BigFraction =
        coefficients.entries.fold(constant) { value, (source, coefficient) -> value + coefficient * assignment(source) }
}

internal sealed interface CutPremise {
    data class Bound(
        val expression: CutExpression,
        val upper: Boolean,
        val value: BigFraction,
        val strict: Boolean = false,
    ) : CutPremise
    data class Integral(val expression: CutExpression) : CutPremise
    data class Fixed(val source: CutSource, val value: BigFraction) : CutPremise
    data class ObjectiveCutoff(val expression: CutExpression, val upper: BigFraction) : CutPremise
    data class Literal(val literal: Int) : CutPremise
    data class Row(val expression: CutExpression, val relation: Relation, val rhs: BigFraction) : CutPremise
}

internal data class CutProofFact(val premise: CutPremise, val global: Boolean)

internal data class CutWeightedRow(val row: CutPremise.Row, val multiplier: Long)

internal class CutRoundingRule(val divisor: Long, val mir: Boolean, val reduction: Long, rows: List<CutWeightedRow>) {
    private val snapshot = rows.toList()
    val rows: List<CutWeightedRow> get() = snapshot.toList()
}

internal class CutProvenance(
    val model: Any,
    val epoch: Long,
    facts: List<CutProofFact>,
    assumptions: Set<String> = emptySet(),
    rules: List<CutRoundingRule> = emptyList(),
) {
    private val factSnapshot = facts.distinct().toList()
    private val assumptionSnapshot = assumptions.toSet()
    private val ruleSnapshot = rules.toList()
    val facts: List<CutProofFact> get() = factSnapshot.toList()
    val assumptions: Set<String> get() = assumptionSnapshot.toSet()
    val rules: List<CutRoundingRule> get() = ruleSnapshot.toList()
    val global: Boolean get() = assumptionSnapshot.isEmpty() && factSnapshot.all { it.global }
}

internal data class CutColumnPremise(val column: Int, val lower: Long, val integral: Boolean)

internal class CutInputRow(
    val index: Int,
    val global: Boolean,
    val multiplier: Long,
    val rhs: BigFraction,
    val relation: Relation,
    columns: IntArray,
    coefficients: LongArray,
    premises: LpRowPremises?,
) {
    private val columnSnapshot = columns.copyOf()
    private val coefficientSnapshot = coefficients.copyOf()
    private val premiseSnapshot = premises?.let {
        LpRowPremises(
            it.vars.copyOf(),
            it.isUpper.copyOf(),
            it.thresholds.copyOf(),
            it.boolLits.copyOf(),
        )
    }
    val columns: IntArray get() = columnSnapshot.copyOf()
    val coefficients: LongArray get() = coefficientSnapshot.copyOf()
    val premises: LpRowPremises? get() = premiseSnapshot?.let {
        LpRowPremises(it.vars.copyOf(), it.isUpper.copyOf(), it.thresholds.copyOf(), it.boolLits.copyOf())
    }
}

internal class TableauCutProvenance(
    val model: LpModel,
    columns: List<CutColumnPremise>,
    rows: List<CutInputRow>,
    val divisor: Long,
    val mir: Boolean,
    val reduction: Long = 1,
) {
    private val columnSnapshot = columns.toList()
    private val rowSnapshot = rows.toList()
    val columns: List<CutColumnPremise> get() = columnSnapshot.toList()
    val rows: List<CutInputRow> get() = rowSnapshot.toList()
    fun reduced(divisor: Long): TableauCutProvenance = TableauCutProvenance(
        model,
        columnSnapshot,
        rowSnapshot,
        this.divisor,
        mir,
        divisor,
    )
}
