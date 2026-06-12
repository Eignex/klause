package com.eignex.klause.choco

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.SearchTier
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredVariableHeuristic
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.LargestDomain
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import org.chocosolver.solver.search.strategy.Search
import org.chocosolver.solver.search.strategy.assignments.DecisionOperator
import org.chocosolver.solver.search.strategy.assignments.DecisionOperatorFactory
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMedian
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMiddle
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMin
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainRandom
import org.chocosolver.solver.search.strategy.selectors.values.IntValueSelector
import org.chocosolver.solver.search.strategy.selectors.variables.AntiFirstFail
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder
import org.chocosolver.solver.search.strategy.selectors.variables.Largest
import org.chocosolver.solver.search.strategy.selectors.variables.MaxRegret
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
                decisionOperator(tier),
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
        TierVarSelect.MaxRegret -> MaxRegret()
        TierVarSelect.RandomOrder -> Random(seed)
    }

private fun valueSelector(tier: SearchTier, seed: Long): IntValueSelector = when (tier.valueHeuristic) {
    IndomainMin -> IntDomainMin()

    IndomainMax -> IntDomainMax()

    // `indomain_middle` and `indomain_split` both bisect at the mean of the bounds (klause's IntNode
    // is bound-split, so its IndomainMiddle is a split too); IntDomainMiddle(true) picks that pivot.
    IndomainMiddle, IndomainSplit -> IntDomainMiddle(true)

    IndomainMedian -> IntDomainMedian()

    IndomainRandom -> IntDomainRandom(seed)

    else -> IntDomainMin()
}

/**
 * Decision operator for a tier, working around an LCG soundness bug: `makeIntEq` paired with
 * `IntDomainMiddle` produces a false UNSAT under LCG (`rasros/choco-lcg-false-unsat`). The split-style
 * value selectors (`indomain_middle` / `indomain_split`) therefore branch with `makeIntSplit`
 * (a sound domain bisection at the pivot — and the branching shape klause itself uses); the
 * assignment-style selectors (`indomain_min/max/median/random`) keep `makeIntEq`, which is sound with
 * any selector other than `IntDomainMiddle`.
 */
private fun decisionOperator(tier: SearchTier): DecisionOperator<IntVar> = when (tier.valueHeuristic) {
    IndomainMiddle, IndomainSplit -> DecisionOperatorFactory.makeIntSplit()
    else -> DecisionOperatorFactory.makeIntEq()
}

/** True when [params] carries a tiered (annotation-derived) search Choco can mirror. */
internal fun hasTranslatableSearch(params: BacktrackParams?): Boolean =
    params?.variableHeuristic is TieredVariableHeuristic
