package com.eignex.klause.lowering.flatzinc

/** Neutral search-annotation metadata extracted from `solve :: ..._search(...)` clauses. */
data class FlatZincSearchHints(
    /** Parsed per-tier variable sets and heuristics. */
    val tiers: List<FlatZincSearchTier>,
    /** Fallback variable heuristic when all search-tier vars are assigned. */
    val fallbackVarSelector: FlatZincSearchVarSelector = FlatZincSearchVarSelector.SmallestDomain,
    /** Fallback value heuristic when all search-tier vars are assigned. */
    val fallbackValueSelector: FlatZincSearchValueSelector = FlatZincSearchValueSelector.IndomainMin,
    /** `true` when a minimization or maximization objective is active and the value side is wrapped. */
    val solutionGuided: Boolean = false,
)

/** Variable selection in one FlatZinc search tier. */
data class FlatZincSearchTier(
    /** Bool vars in the annotated order, excluding constants. */
    val boolVars: IntArray,
    /** Int vars in the annotated order, excluding constants. */
    val intVars: IntArray,
    /** MiniZinc variable-selection strategy for this tier. */
    val varSelector: FlatZincSearchVarSelector = FlatZincSearchVarSelector.SmallestDomain,
    /** MiniZinc value-selection strategy for this tier. */
    val valueSelector: FlatZincSearchValueSelector = FlatZincSearchValueSelector.IndomainMin,
)

/** Parsed MiniZinc variable heuristic name. */
enum class FlatZincSearchVarSelector {
    /** `input_order` */
    InputOrder,
    /** `first_fail`, `most_constrained`, `dom_w_deg` */
    SmallestDomain,
    /** `anti_first_fail`, `occurrence` */
    LargestDomain,
    /** `dom_w_deg` (fallback only) */
    DomWdeg,
    /** `smallest` */
    SmallestLowerBound,
    /** `largest` */
    LargestUpperBound,
    /** `max_regret` */
    MaxRegret,
    /** `random_order` */
    RandomOrder,
}

/** Parsed MiniZinc value heuristic name. */
enum class FlatZincSearchValueSelector {
    IndomainMin,
    IndomainMax,
    IndomainMiddle,
    IndomainMedian,
    IndomainSplit,
    IndomainRandom,
}
