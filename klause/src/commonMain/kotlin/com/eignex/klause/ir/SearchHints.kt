package com.eignex.klause.ir

/** Ordered variable groups and branching intent for finite execution. */
data class SearchHints(
    /** Ordered variable groups and their selection intent. */
    val tiers: List<SearchGroup>,
    /** Fallback variable heuristic when all search-tier vars are assigned. */
    val fallbackVarSelector: VariableSelection = VariableSelection.SmallestDomain,
    /** Fallback value heuristic when all search-tier vars are assigned. */
    val fallbackValueSelector: ValueSelection = ValueSelection.Minimum,
    /** `true` when a minimization or maximization objective is active and the value side is wrapped. */
    val solutionGuided: Boolean = false,
)

/** Variable selection in one finite search group. */
data class SearchGroup(
    /** Bool vars in the requested order, excluding constants. */
    val boolVars: IntArray,
    /** Int vars in the requested order, excluding constants. */
    val intVars: IntArray,
    /** Finite variable-selection strategy for this tier. */
    val varSelector: VariableSelection = VariableSelection.SmallestDomain,
    /** Finite value-selection strategy for this tier. */
    val valueSelector: ValueSelection = ValueSelection.Minimum,
)

/** Variable-selection intent. */
enum class VariableSelection {
    /** Declaration order. */
    InputOrder,

    /** Smallest remaining domain. */
    SmallestDomain,

    /** Largest remaining domain. */
    LargestDomain,

    /** Domain size divided by conflict weight. */
    DomWdeg,

    /** Smallest lower bound. */
    SmallestLowerBound,

    /** Largest upper bound. */
    LargestUpperBound,

    /** Largest gap between the two smallest values. */
    MaxRegret,

    /** Random variable order. */
    RandomOrder,
}

/** Value-selection intent. */
enum class ValueSelection {
    /** Smallest value. */
    Minimum,

    /** Largest value. */
    Maximum,

    /** Value nearest the domain midpoint. */
    Middle,

    /** Median remaining value. */
    Median,

    /** Split the domain at its midpoint. */
    Split,

    /** Random remaining value. */
    Random,
}
