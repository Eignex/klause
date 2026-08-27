package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.DomainMaxRegret
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMedian
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainRandom
import com.eignex.klause.backtrack.selector.IndomainSplit
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.LargestDomain
import com.eignex.klause.backtrack.selector.LargestUpperBound
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SmallestLowerBound
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.lowering.flatzinc.FlatZincSearchHints
import com.eignex.klause.lowering.flatzinc.FlatZincSearchTier
import com.eignex.klause.lowering.flatzinc.FlatZincSearchVarSelector
import com.eignex.klause.lowering.flatzinc.FlatZincSearchValueSelector

/** Convert format-side search hints to `BacktrackParams` in the backtrack engine package. */
/** Convert format-side search hints into backtrack-ready parameters. */
fun FlatZincSearchHints.toBacktrackParams(
    numBoolVars: Int,
    numIntVars: Int,
): BacktrackParams = BacktrackPresets.conflictDriven().copy(
    variableSelector = fallbackVarSelector.toVariableSelector()
        .let { fallback -> TieredVariableSelector(this.tiers.map { it.toSearchTier() }, fallback = fallback) },
    valueSelector = toValueSelector(numBoolVars, numIntVars),
)

private fun FlatZincSearchHints.toValueSelector(
    numBoolVars: Int,
    numIntVars: Int,
): ValueSelector {
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

private fun FlatZincSearchTier.toSearchTier(): SearchTier = SearchTier(
    boolVars = boolVars,
    intVars = intVars,
    varSelect = varSelector.toTierVarSelect(),
    valueSelector = valueSelector.toValueSelector(),
)

private fun FlatZincSearchVarSelector.toTierVarSelect(): TierVarSelect = when (this) {
    FlatZincSearchVarSelector.InputOrder -> TierVarSelect.InputOrder
    FlatZincSearchVarSelector.SmallestDomain -> TierVarSelect.SmallestDomain
    FlatZincSearchVarSelector.LargestDomain -> TierVarSelect.LargestDomain
    FlatZincSearchVarSelector.SmallestLowerBound -> TierVarSelect.SmallestLowerBound
    FlatZincSearchVarSelector.LargestUpperBound -> TierVarSelect.LargestUpperBound
    FlatZincSearchVarSelector.MaxRegret -> TierVarSelect.MaxRegret
    FlatZincSearchVarSelector.RandomOrder -> TierVarSelect.RandomOrder
    FlatZincSearchVarSelector.DomWdeg -> TierVarSelect.SmallestDomain
}

private fun FlatZincSearchVarSelector.toVariableSelector(): VariableSelector = when (this) {
    FlatZincSearchVarSelector.InputOrder -> InputOrder
    FlatZincSearchVarSelector.SmallestDomain -> SmallestDomain
    FlatZincSearchVarSelector.LargestDomain -> LargestDomain
    FlatZincSearchVarSelector.SmallestLowerBound -> SmallestLowerBound
    FlatZincSearchVarSelector.LargestUpperBound -> LargestUpperBound
    FlatZincSearchVarSelector.MaxRegret -> DomainMaxRegret
    FlatZincSearchVarSelector.RandomOrder -> RandomVariable
    FlatZincSearchVarSelector.DomWdeg -> DomWdeg()
}

private fun FlatZincSearchValueSelector.toValueSelector(): ValueSelector = when (this) {
    FlatZincSearchValueSelector.IndomainMin -> IndomainMin
    FlatZincSearchValueSelector.IndomainMax -> IndomainMax
    FlatZincSearchValueSelector.IndomainMiddle -> IndomainMiddle
    FlatZincSearchValueSelector.IndomainMedian -> IndomainMedian
    FlatZincSearchValueSelector.IndomainSplit -> IndomainSplit
    FlatZincSearchValueSelector.IndomainRandom -> IndomainRandom
}
