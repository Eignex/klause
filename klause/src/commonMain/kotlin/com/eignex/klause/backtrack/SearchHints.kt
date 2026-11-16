package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.DomainMaxRegret
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMedian
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainRandom
import com.eignex.klause.backtrack.selector.IndomainSplit
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.LargestDomain
import com.eignex.klause.backtrack.selector.LargestUpperBound
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SmallestLowerBound
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection

/** Convert neutral search intent into backtrack-ready parameters. */
fun SearchHints.toBacktrackParams(numBoolVars: Int, numIntVars: Int): BacktrackParams =
    BacktrackParams(
        variableSelector = fallbackVarSelector.toVariableSelector()
            .let { fallback -> TieredVariableSelector(this.tiers.map { it.toSearchTier() }, fallback = fallback) },
        valueSelector = toValueSelector(numBoolVars, numIntVars),
    )

private fun SearchHints.toValueSelector(numBoolVars: Int, numIntVars: Int): ValueSelector {
    val tiers = tiers.map { it.toSearchTier() }
    val fallback = fallbackValueSelector.toValueSelector()
    val tiered = TieredValueSelector(
        tiers = tiers,
        fallback = fallback,
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
    )
    return if (solutionGuided) SolutionGuided(tiered) else tiered
}

private fun SearchGroup.toSearchTier(): SearchTier = SearchTier(
    boolVars = boolVars,
    intVars = intVars,
    varSelect = varSelector.toTierVarSelect(),
    valueSelector = valueSelector.toValueSelector(),
)

private fun VariableSelection.toTierVarSelect(): TierVarSelect = when (this) {
    VariableSelection.InputOrder -> TierVarSelect.InputOrder
    VariableSelection.SmallestDomain -> TierVarSelect.SmallestDomain
    VariableSelection.LargestDomain -> TierVarSelect.LargestDomain
    VariableSelection.SmallestLowerBound -> TierVarSelect.SmallestLowerBound
    VariableSelection.LargestUpperBound -> TierVarSelect.LargestUpperBound
    VariableSelection.MaxRegret -> TierVarSelect.MaxRegret
    VariableSelection.RandomOrder -> TierVarSelect.RandomOrder
    VariableSelection.DomWdeg -> TierVarSelect.SmallestDomain
}

private fun VariableSelection.toVariableSelector(): VariableSelector = when (this) {
    VariableSelection.InputOrder -> InputOrder
    VariableSelection.SmallestDomain -> SmallestDomain
    VariableSelection.LargestDomain -> LargestDomain
    VariableSelection.SmallestLowerBound -> SmallestLowerBound
    VariableSelection.LargestUpperBound -> LargestUpperBound
    VariableSelection.MaxRegret -> DomainMaxRegret
    VariableSelection.RandomOrder -> RandomVariable
    VariableSelection.DomWdeg -> DomWdeg()
}

private fun ValueSelection.toValueSelector(): ValueSelector = when (this) {
    ValueSelection.Minimum -> IndomainMin
    ValueSelection.Maximum -> IndomainMax
    ValueSelection.Middle -> IndomainMiddle
    ValueSelection.Median -> IndomainMedian
    ValueSelection.Split -> IndomainSplit
    ValueSelection.Random -> IndomainRandom
}
