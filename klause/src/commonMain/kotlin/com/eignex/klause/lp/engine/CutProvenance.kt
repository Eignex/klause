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
    val storageUnits: Long get() = coefficients.size.toLong() * 3L + 1L
    val terms: Map<CutSource, BigFraction> get() = coefficients.toMap()

    override fun equals(other: Any?): Boolean =
        other is CutExpression && coefficients == other.coefficients && constant == other.constant

    override fun hashCode(): Int = 31 * coefficients.hashCode() + constant.hashCode()

    fun value(assignment: (CutSource) -> BigFraction): BigFraction =
        coefficients.entries.fold(constant) { value, (source, coefficient) -> value + coefficient * assignment(source) }

    fun remapSources(mapping: Map<CutSource, CutSource>): CutExpression {
        if (coefficients.keys.none { mapping[it]?.let { next -> next != it } == true }) return this
        val remapped = LinkedHashMap<CutSource, BigFraction>()
        for ((source, coefficient) in coefficients) {
            val next = mapping[source] ?: source
            remapped[next] = (remapped[next] ?: BigFraction.ZERO) + coefficient
        }
        return CutExpression(remapped, constant)
    }
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

internal fun CutPremise.remapSources(mapping: Map<CutSource, CutSource>): CutPremise = when (this) {
    is CutPremise.Bound -> copy(expression = expression.remapSources(mapping))
    is CutPremise.Integral -> copy(expression = expression.remapSources(mapping))
    is CutPremise.Excluded -> copy(source = mapping[source] ?: source)
    is CutPremise.Fixed -> copy(source = mapping[source] ?: source)
    is CutPremise.ObjectiveCutoff -> copy(expression = expression.remapSources(mapping))
    is CutPremise.Literal -> this
    is CutPremise.Row -> copy(expression = expression.remapSources(mapping))
}

internal data class CutProofFact(val premise: CutPremise, val global: Boolean)

internal data class CutWeightedRow(val row: CutPremise.Row, val multiplier: Long)

internal class CutRoundingRule(val divisor: Long, val mir: Boolean, val reduction: Long, rows: List<CutWeightedRow>) {
    private val snapshot = rows.toList()
    val storageUnits: Long = 3L + snapshot.sumOf { it.row.storageUnits() + 1L }
    val rows: List<CutWeightedRow> get() = snapshot.toList()
}

internal class CutAuxiliaryDefinition(
    role: List<Long>,
    required: List<Long>,
    val presentUpper: Long,
    val integralExtension: Boolean,
) {
    private val roleSnapshot = role.toList()
    private val requiredSnapshot = required.toList()
    val identityKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        "${roleSnapshot.joinToString(",")}|${requiredSnapshot.joinToString(",")}|$presentUpper|$integralExtension"
    }
    private val hash = listOf(roleSnapshot, requiredSnapshot, presentUpper, integralExtension).hashCode()
    val storageUnits: Long get() = roleSnapshot.size.toLong() + requiredSnapshot.size + 2L
    val role: List<Long> get() = roleSnapshot.toList()
    val required: List<Long> get() = requiredSnapshot.toList()

    override fun equals(other: Any?): Boolean = other is CutAuxiliaryDefinition &&
        roleSnapshot == other.roleSnapshot && requiredSnapshot == other.requiredSnapshot &&
        presentUpper == other.presentUpper && integralExtension == other.integralExtension
    override fun hashCode(): Int = hash
}

internal class CutProvenance(
    val model: Any,
    val epoch: Long,
    facts: List<CutProofFact>,
    assumptions: Set<String> = emptySet(),
    rules: List<CutRoundingRule> = emptyList(),
    val conclusion: CutPremise.Row? = null,
    auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> = emptyMap(),
) {
    private val factSnapshot = facts.distinct().toList()
    private val assumptionSnapshot = assumptions.toSet()
    private val ruleSnapshot = rules.toList()
    private val auxiliarySnapshot = auxiliaryDefinitions.toMap()
    val storageUnits: Long = factSnapshot.sumOf { it.premise.storageUnits() + 1L } +
        assumptionSnapshot.sumOf { it.length.toLong() } + ruleSnapshot.sumOf { it.storageUnits } +
        auxiliarySnapshot.values.sumOf { it.storageUnits + 2L } + (conclusion?.storageUnits() ?: 0L) + 3L
    val facts: List<CutProofFact> get() = factSnapshot.toList()
    val assumptions: Set<String> get() = assumptionSnapshot.toSet()
    val rules: List<CutRoundingRule> get() = ruleSnapshot.toList()
    val auxiliaryDefinitions: Map<CutSource, CutAuxiliaryDefinition> get() = auxiliarySnapshot.toMap()
    val global: Boolean get() = assumptionSnapshot.isEmpty() && factSnapshot.all { it.global }

    fun remapSources(mapping: Map<CutSource, CutSource>): CutProvenance {
        if (mapping.all { (previous, next) -> previous == next }) return this
        val definitions = LinkedHashMap<CutSource, CutAuxiliaryDefinition>()
        for ((source, definition) in auxiliarySnapshot) {
            val next = mapping[source] ?: source
            require(definitions[next]?.let { it == definition } != false) { "incompatible auxiliary definitions" }
            definitions[next] = definition
        }
        return CutProvenance(
            model, epoch,
            factSnapshot.map { it.copy(premise = it.premise.remapSources(mapping)) },
            assumptionSnapshot,
            ruleSnapshot.map { rule ->
                CutRoundingRule(rule.divisor, rule.mir, rule.reduction, rule.rows.map {
                    it.copy(row = it.row.copy(expression = it.row.expression.remapSources(mapping)))
                })
            },
            conclusion?.let { it.copy(expression = it.expression.remapSources(mapping)) },
            definitions,
        )
    }
}

private fun CutPremise.storageUnits(): Long = when (this) {
    is CutPremise.Bound -> expression.storageUnits + 3L
    is CutPremise.Integral -> expression.storageUnits + 1L
    is CutPremise.Row -> expression.storageUnits + 3L
    is CutPremise.ObjectiveCutoff -> expression.storageUnits + 2L
    is CutPremise.Fixed, is CutPremise.Excluded -> 3L
    is CutPremise.Literal -> 1L
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
    val exactPremises: ExactLpPremises? = null,
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
