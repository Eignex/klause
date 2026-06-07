package com.eignex.klause.choco

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.IndomainMax
import com.eignex.klause.solver.backtrack.IndomainMiddle
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.IndomainRandom
import com.eignex.klause.solver.backtrack.IndomainSplit
import com.eignex.klause.solver.backtrack.SearchTier
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredVariableHeuristic
import org.chocosolver.solver.search.strategy.Search
import org.chocosolver.solver.search.strategy.assignments.DecisionOperatorFactory
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMiddle
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMin
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainRandom
import org.chocosolver.solver.search.strategy.selectors.values.IntValueSelector
import org.chocosolver.solver.search.strategy.selectors.variables.AntiFirstFail
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder
import org.chocosolver.solver.search.strategy.selectors.variables.Largest
import org.chocosolver.solver.search.strategy.selectors.variables.Random
import org.chocosolver.solver.search.strategy.selectors.variables.Smallest
import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector
import org.chocosolver.solver.search.strategy.strategy.AbstractStrategy
import org.chocosolver.solver.variables.IntVar
import org.chocosolver.solver.variables.Variable

/**
 * Translate klause's annotation-derived tiered search (see
 * [com.eignex.klause.formats.flatzinc.FlatZincProgram.defaultBacktrackParams]) onto the
 * Choco model, for fixed-track comparisons where both solvers must follow the model
 * author's prescribed search. Each [SearchTier] becomes one `Search.intVarSearch` block
 * over the tier's variables with the matching variable / value selectors; Choco's default
 * search completes the remaining (introduced) variables, mirroring how klause's tiered
 * heuristic falls back once every tier is assigned. Non-tiered params (no recognised
 * annotation) leave Choco's default search untouched.
 */
internal fun applyFixedSearch(cm: ChocoModel, params: BacktrackParams, seed: Long) {
    val varH = params.variableHeuristic as? TieredVariableHeuristic ?: return
    val tiers = varH.tiers
    val strategies = ArrayList<AbstractStrategy<*>>(tiers.size + 1)
    for (tier in tiers) {
        val vars = ArrayList<IntVar>(tier.boolVars.size + tier.intVars.size)
        for (b in tier.boolVars) vars.add(cm.boolVars[b])
        for (i in tier.intVars) vars.add(cm.intVars[i])
        if (vars.isEmpty()) continue
        @Suppress("SpreadOperator") // per-solve setup, not a hot path
        strategies.add(
            Search.intVarSearch(
                varSelector(cm, tier, seed),
                valueSelector(tier, seed),
                DecisionOperatorFactory.makeIntEq(),
                *vars.toTypedArray(),
            ),
        )
    }
    if (strategies.isEmpty()) return
    // In Choco 6 `Search.defaultSearch` SETS the model's default in place; capture the
    // resulting strategy and append it so the introduced variables the tiers don't cover
    // are completed the same way Choco's free search would.
    Search.defaultSearch(cm.model)
    strategies.add(cm.model.solver.getSearch<Variable>())
    @Suppress("SpreadOperator") // per-solve setup, not a hot path
    cm.model.solver.setSearch(*strategies.toTypedArray())
}

private fun varSelector(cm: ChocoModel, tier: SearchTier, seed: Long): VariableSelector<IntVar> =
    when (tier.varSelect) {
        TierVarSelect.InputOrder -> InputOrder(cm.model)
        TierVarSelect.SmallestDomain -> FirstFail(cm.model)
        TierVarSelect.LargestDomain -> AntiFirstFail(cm.model)
        TierVarSelect.SmallestLowerBound -> Smallest()
        TierVarSelect.LargestUpperBound -> Largest()
        TierVarSelect.RandomOrder -> Random(seed)
    }

private fun valueSelector(tier: SearchTier, seed: Long): IntValueSelector = when (tier.valueHeuristic) {
    IndomainMin -> IntDomainMin()
    IndomainMax -> IntDomainMax()
    IndomainMiddle, IndomainSplit -> IntDomainMiddle(true)
    IndomainRandom -> IntDomainRandom(seed)
    else -> IntDomainMin()
}

/** True when [params] carries a tiered (annotation-derived) search Choco can mirror. */
internal fun hasTranslatableSearch(params: BacktrackParams?): Boolean =
    params?.variableHeuristic is TieredVariableHeuristic
