package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection

/** FlatZinc search-annotation metadata extracted from `solve :: ..._search(...)` clauses. */
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
    /** `input_order`. */
    InputOrder,

    /** `first_fail` or `most_constrained`; within a tier also `dom_w_deg` and `occurrence`. */
    SmallestDomain,

    /** `anti_first_fail`; as the fallback selector also `occurrence`. */
    LargestDomain,

    /** `dom_w_deg` as the fallback selector. */
    DomWdeg,

    /** `smallest`. */
    SmallestLowerBound,

    /** `largest`. */
    LargestUpperBound,

    /** `max_regret`. */
    MaxRegret,

    /** `random_order`. */
    RandomOrder,
}

/** Parsed MiniZinc value heuristic name. */
enum class FlatZincSearchValueSelector {
    /** `indomain_min` or `indomain`. */
    IndomainMin,

    /** `indomain_max`. */
    IndomainMax,

    /** `indomain_middle`. */
    IndomainMiddle,

    /** `indomain_median`. */
    IndomainMedian,

    /** `indomain_split`. */
    IndomainSplit,

    /** `indomain_random`. */
    IndomainRandom,
}

/** Translate parsed FlatZinc annotation policy into finite execution intent. */
fun FlatZincSearchHints.toSearchHints(): SearchHints = SearchHints(
    tiers = tiers.map { tier ->
        SearchGroup(
            tier.boolVars.copyOf(), tier.intVars.copyOf(),
            tier.varSelector.toSelection(), tier.valueSelector.toSelection(),
        )
    },
    fallbackVarSelector = fallbackVarSelector.toSelection(),
    fallbackValueSelector = fallbackValueSelector.toSelection(),
    solutionGuided = solutionGuided,
)

private fun FlatZincSearchVarSelector.toSelection(): VariableSelection = when (this) {
    FlatZincSearchVarSelector.InputOrder -> VariableSelection.InputOrder
    FlatZincSearchVarSelector.SmallestDomain -> VariableSelection.SmallestDomain
    FlatZincSearchVarSelector.LargestDomain -> VariableSelection.LargestDomain
    FlatZincSearchVarSelector.DomWdeg -> VariableSelection.DomWdeg
    FlatZincSearchVarSelector.SmallestLowerBound -> VariableSelection.SmallestLowerBound
    FlatZincSearchVarSelector.LargestUpperBound -> VariableSelection.LargestUpperBound
    FlatZincSearchVarSelector.MaxRegret -> VariableSelection.MaxRegret
    FlatZincSearchVarSelector.RandomOrder -> VariableSelection.RandomOrder
}

private fun FlatZincSearchValueSelector.toSelection(): ValueSelection = when (this) {
    FlatZincSearchValueSelector.IndomainMin -> ValueSelection.Minimum
    FlatZincSearchValueSelector.IndomainMax -> ValueSelection.Maximum
    FlatZincSearchValueSelector.IndomainMiddle -> ValueSelection.Middle
    FlatZincSearchValueSelector.IndomainMedian -> ValueSelection.Median
    FlatZincSearchValueSelector.IndomainSplit -> ValueSelection.Split
    FlatZincSearchValueSelector.IndomainRandom -> ValueSelection.Random
}
