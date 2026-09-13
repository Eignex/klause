package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction

internal enum class CutSourceKind { INTEGER, BOOLEAN, REAL, TERM, AUXILIARY }

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
    data class Excluded(val source: CutSource, val value: BigFraction) : CutPremise
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

internal data class CutFixing(val lower: CutPremise.Bound, val upper: CutPremise.Bound)

internal sealed interface CutLatticeResult {
    data class RoundedBound(
        val sourceUpper: Boolean,
        val threshold: BigFraction,
        val roundedThreshold: BigFraction,
        val strict: Boolean,
    ) : CutLatticeResult
    data class InfeasibleEquality(val threshold: BigFraction) : CutLatticeResult
}

internal sealed interface CutRowTransform {
    val input: CutPremise.Row
    val conclusion: CutPremise.Row

    class Algebraic(
        override val input: CutPremise.Row,
        override val conclusion: CutPremise.Row,
        val multiplier: BigFraction,
        val inputStrict: Boolean,
        val outputStrict: Boolean,
        fixings: List<CutFixing>,
    ) : CutRowTransform {
        private val fixingSnapshot = fixings.toList()
        val fixings: List<CutFixing> get() = fixingSnapshot.toList()
    }

    data class Lattice(
        override val input: CutPremise.Row,
        override val conclusion: CutPremise.Row,
        val integral: CutPremise.Integral,
        val result: CutLatticeResult,
    ) : CutRowTransform
}

internal class CutAuxiliaryDefinition(
    role: List<Long>,
    required: List<Long>,
    val presentUpper: Long,
    val integralExtension: Boolean,
) {
    private val roleSnapshot = role.toList()
    private val requiredSnapshot = required.toList()
    val role: List<Long> get() = roleSnapshot.toList()
    val required: List<Long> get() = requiredSnapshot.toList()

    override fun equals(other: Any?): Boolean = other is CutAuxiliaryDefinition &&
        roleSnapshot == other.roleSnapshot && requiredSnapshot == other.requiredSnapshot &&
        presentUpper == other.presentUpper && integralExtension == other.integralExtension
    override fun hashCode(): Int = listOf(roleSnapshot, requiredSnapshot, presentUpper, integralExtension).hashCode()
}

internal class CutProvenance(
    val model: Any,
    val epoch: Long,
    facts: List<CutProofFact>,
    assumptions: Set<String> = emptySet(),
    rules: List<CutRoundingRule> = emptyList(),
    val conclusion: CutPremise.Row? = null,
    transformations: List<CutRowTransform> = emptyList(),
    auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> = emptyMap(),
) {
    private val factSnapshot = facts.distinct().toList()
    private val assumptionSnapshot = assumptions.toSet()
    private val ruleSnapshot = rules.toList()
    private val transformationSnapshot = transformations.toList()
    private val auxiliarySnapshot = auxiliaryDefinitions.toMap()
    val facts: List<CutProofFact> get() = factSnapshot.toList()
    val assumptions: Set<String> get() = assumptionSnapshot.toSet()
    val rules: List<CutRoundingRule> get() = ruleSnapshot.toList()
    val transformations: List<CutRowTransform> get() = transformationSnapshot.toList()
    val auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> get() = auxiliarySnapshot.toMap()
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
