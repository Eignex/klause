package com.eignex.klause.ir

/** A numerical declaration with its original rational bounds and coordinate identity. */
data class LinearSourceVariable(
    /** Original declaration name. */
    val name: String,
    /** Whether every accepted coordinate must be integral. */
    val integer: Boolean,
    /** Original finite lower side, or null for an absent side. */
    val lower: SemanticRational? = null,
    /** Original finite upper side, or null for an absent side. */
    val upper: SemanticRational? = null,
)

/** One original-unit term, indexed in declaration order. */
data class LinearSourceTerm(
    /** Declaration-order column identity. */
    val column: Int,
    /** Exact source coefficient before normalization. */
    val coefficient: SemanticRational,
)

/** An implication enabled when an integer source coordinate equals zero or one. */
data class LinearSourceIndicator(
    /** Declaration-order integer column identity. */
    val column: Int,
    /** Whether one, rather than zero, enables the row. */
    val whenOne: Boolean,
)

/** Original rational row sides; separate terms retain their source tolerance magnitudes. */
class LinearSourceRow(
    terms: List<LinearSourceTerm>,
    /** Original finite lower side, or null for an absent side. */
    val lower: SemanticRational? = null,
    /** Original finite upper side, or null for an absent side. */
    val upper: SemanticRational? = null,
    /** Optional integer implication guard. */
    val indicator: LinearSourceIndicator? = null,
) {
    internal val termValues: List<LinearSourceTerm> = terms.toList()

    /** Independent term container in original source order. */
    val terms: List<LinearSourceTerm> get() = termValues.toList()
}

/** Exact original affine objective, independent of a working scale or defining column. */
class LinearSourceObjective(
    terms: List<LinearSourceTerm>,
    /** Original constant term. */
    val constant: SemanticRational = SemanticRational.ZERO,
    /** Original source direction. */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
) {
    internal val termValues: List<LinearSourceTerm> = terms.toList()

    /** Independent objective term container in source order. */
    val terms: List<LinearSourceTerm> get() = termValues.toList()
}

/**
 * A retained linear source before denominator clearing, integer rounding or numerical projection.
 * Bounds are effective source declarations; [semantics] retains their captured interpretation.
 * Engine views may normalize these rows without changing their original tolerance or objective units.
 */
class LinearSourceProblem(
    variables: List<LinearSourceVariable>,
    rows: List<LinearSourceRow>,
    /** Original affine objective, or null for satisfaction. */
    val objective: LinearSourceObjective? = null,
    /** Effective interpretation captured before constructing an engine view. */
    override val semantics: EffectiveSourceSemantics = defaultIrSemantics,
) : SourceSemanticsOwner {
    internal val variableValues: List<LinearSourceVariable> = variables.toList()
    internal val rowValues: List<LinearSourceRow> = rows.toList()

    init {
        require(variableValues.map { it.name }.toSet().size == variableValues.size) {
            "linear source declaration names must be unique"
        }
        for (row in rowValues) {
            require(row.termValues.all { it.column in variableValues.indices }) { "invalid linear source column" }
            row.indicator?.let {
                require(it.column in variableValues.indices && variableValues[it.column].integer) {
                    "linear source indicator must name an integer declaration"
                }
            }
        }
        require(objective?.termValues.orEmpty().all { it.column in variableValues.indices }) {
            "invalid linear source objective column"
        }
    }

    /** Independent declaration container in source order. */
    val variables: List<LinearSourceVariable> get() = variableValues.toList()

    /** Independent original-row container. */
    val rows: List<LinearSourceRow> get() = rowValues.toList()

    /** Original integer declaration names mapped to integer output identities. */
    val intVarNames: Map<String, Int> get() = variableValues.filter { it.integer }
        .mapIndexed { id, variable -> variable.name to id }.toMap()

    /** Original continuous declaration names mapped to real output identities. */
    val realVarNames: Map<String, Int> get() = variableValues.filterNot { it.integer }
        .mapIndexed { id, variable -> variable.name to id }.toMap()
}
