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
    /** Parse `input_order` as a variable selection heuristic. */
    InputOrder,

    /** Parse `first_fail`, `most_constrained`, or `dom_w_deg` as a variable heuristic. */
    SmallestDomain,

    /** Parse `anti_first_fail` or `occurrence` as a variable heuristic. */
    LargestDomain,

    /** Parse `dom_w_deg` when used as fallback-only variable selection. */
    DomWdeg,

    /** Parse `smallest` as a variable selection heuristic. */
    SmallestLowerBound,

    /** Parse `largest` as a variable selection heuristic. */
    LargestUpperBound,

    /** Parse `max_regret` as a variable selection heuristic. */
    MaxRegret,

    /** Parse `random_order` as a variable selection heuristic. */
    RandomOrder,
}

/** Parsed MiniZinc value heuristic name. */
enum class FlatZincSearchValueSelector {
    /** Parse `indomain_min` as a value selection heuristic. */
    IndomainMin,

    /** Parse `indomain_max` as a value selection heuristic. */
    IndomainMax,

    /** Parse `indomain_middle` as a value selection heuristic. */
    IndomainMiddle,

    /** Parse `indomain_median` as a value selection heuristic. */
    IndomainMedian,

    /** Parse `indomain_split` as a value selection heuristic. */
    IndomainSplit,

    /** Parse `indomain_random` as a value selection heuristic. */
    IndomainRandom,
}
