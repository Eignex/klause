package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.SearchTier
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredValueSelector
import com.eignex.klause.solver.backtrack.TieredVariableSelector
import com.eignex.klause.solver.backtrack.selector.DomWdeg
import com.eignex.klause.solver.backtrack.selector.DomainMaxRegret
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.LargestDomain
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.SolutionGuided
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VariableSelector

/** Map `solve :: *_search(...)` annotations to [BacktrackParams]. */
internal fun FlatZincCompiler.compileSearchAnnotation(): BacktrackParams? {
    val blocks = model.solve.annotations.filter(::isSearchAnnotation).flatMap(::searchBlocksOf)
    val tiers = blocks.mapNotNull(::compileSearchBlock)
    if (tiers.isEmpty()) return null
    val firstBlockVarName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(1) as? FznExpr.Ident)?.name }
    val firstBlockValName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(2) as? FznExpr.Ident)?.name }
    val fallbackVar = firstBlockVarName?.let(::mapVariableStrategy)
        ?: SmallestDomain
    val fallbackVal = firstBlockValName?.let(::mapValueStrategy)
        ?: IndomainMin
    val tieredVal = TieredValueSelector(tiers, fallbackVal, numBoolVars, intDomains.size)
    // Optimization search keeps incumbents as value guidance.
    val wrappedValH = when (model.solve) {
        is FznSolve.Minimize, is FznSolve.Maximize -> SolutionGuided(tieredVal)
        is FznSolve.Satisfy -> tieredVal
    }
    val base = BacktrackPresets.conflictDriven()
    return base.copy(
        variableSelector = TieredVariableSelector(tiers, fallbackVar),
        valueSelector = wrappedValH,
    )
}

internal fun isSearchAnnotation(a: FznAnnotation): Boolean =
    a.name == "int_search" || a.name == "bool_search" || a.name == "set_search" || a.name == "seq_search"

/** Flatten one search annotation to concrete blocks. */
internal fun FlatZincCompiler.searchBlocksOf(a: FznAnnotation): List<FznAnnotation> = when (a.name) {
    "int_search", "bool_search", "set_search" -> listOf(a)

    "seq_search" -> {
        val list = (a.args.firstOrNull() as? FznExpr.ArrayLit)?.elements.orEmpty()
        list.filterIsInstance<FznExpr.AnnCall>()
            .flatMap { searchBlocksOf(FznAnnotation(it.name, it.args)) }
    }

    else -> emptyList()
}

/** Build one [SearchTier] from one search block. */
private fun FlatZincCompiler.compileSearchBlock(a: FznAnnotation): SearchTier? {
    if (a.args.size < 3) return null
    val bools = ArrayList<Int>()
    val ints = ArrayList<Int>()
    collectSearchVars(a.args[0], bools, ints)
    if (bools.isEmpty() && ints.isEmpty()) return null
    val varName = (a.args[1] as? FznExpr.Ident)?.name
    val valName = (a.args[2] as? FznExpr.Ident)?.name
    return SearchTier(
        boolVars = bools.toIntArray(),
        intVars = ints.toIntArray(),
        varSelect = varName?.let(::mapTierVarSelect) ?: TierVarSelect.InputOrder,
        valueSelector = valName?.let(::mapValueStrategy)
            ?: IndomainMin,
    )
}

/** Resolve a search variable expression to bool/int ids in the listed order. */
private fun FlatZincCompiler.collectSearchVars(e: FznExpr, bools: ArrayList<Int>, ints: ArrayList<Int>) {
    when (e) {
        is FznExpr.Ident -> {
            val name = e.name
            boolVars[name]?.let {
                bools.add(it)
                return
            }
            intVars[name]?.let {
                ints.add(it)
                return
            }
            floatVars[name]?.let {
                ints.add(it.varId)
                return
            }
            setVarsByName[name]?.let { layout ->
                for (b in layout.indicatorBoolIds) bools.add(b)
                return
            }
            when (val arr = arrays[name]) {
                is FlatZincArray.Vars -> when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> for (v in arr.varIds) bools.add(v)

                    FlatZincArray.Vars.ElementKind.Int,
                    FlatZincArray.Vars.ElementKind.Float,
                    -> for (v in arr.varIds) ints.add(v)
                }

                else -> {}
            }
        }

        is FznExpr.ArrayLit -> for (el in e.elements) collectSearchVars(el, bools, ints)

        is FznExpr.ArrayAccess -> {
            val arr = arrays[e.name] as? FlatZincArray.Vars ?: return
            val idx = e.index - 1
            if (idx !in arr.varIds.indices) return
            when (arr.elementKind) {
                FlatZincArray.Vars.ElementKind.Bool -> bools.add(arr.varIds[idx])

                FlatZincArray.Vars.ElementKind.Int,
                FlatZincArray.Vars.ElementKind.Float,
                -> ints.add(arr.varIds[idx])
            }
        }

        else -> {}
    }
}

internal fun mapTierVarSelect(name: String): TierVarSelect? = when (name) {
    "input_order" -> TierVarSelect.InputOrder
    "first_fail", "most_constrained", "dom_w_deg", "occurrence" -> TierVarSelect.SmallestDomain
    "anti_first_fail" -> TierVarSelect.LargestDomain
    "smallest" -> TierVarSelect.SmallestLowerBound
    "largest" -> TierVarSelect.LargestUpperBound
    "max_regret" -> TierVarSelect.MaxRegret
    "random_order" -> TierVarSelect.RandomOrder
    else -> null
}

internal fun mapVariableStrategy(name: String): VariableSelector? = when (name) {
    "input_order" -> InputOrder
    "first_fail", "most_constrained" -> SmallestDomain
    "dom_w_deg" -> DomWdeg()
    "anti_first_fail", "occurrence" -> LargestDomain
    "smallest" -> SmallestLowerBound
    "largest" -> LargestUpperBound
    "max_regret" -> DomainMaxRegret
    "random_order" -> RandomVariable
    else -> null
}

internal fun mapValueStrategy(name: String): ValueSelector? = when (name) {
    "indomain_min", "indomain" -> IndomainMin
    "indomain_max" -> IndomainMax
    "indomain_middle" -> IndomainMiddle
    "indomain_median" -> IndomainMedian
    "indomain_split" -> IndomainSplit
    "indomain_random" -> IndomainRandom
    else -> null
}
